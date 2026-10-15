#!/usr/bin/env python3
"""Isolated OIDC fleet key rotation, coordinated rollback and backup-restore drill.

Only creates new databases; never changes the source fleet or its keys. Product
startup migrators perform the rewrite. Independent Java probes use product crypto
readers and require both model and MCP fixtures, without emitting secret material.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import time
import httpx

ROOT = Path(__file__).resolve().parents[1]
PG = 'spec-agent-p2-auth'


def command(args, **kwargs):
    return subprocess.run(args, capture_output=True, text=True, encoding='utf-8', errors='replace',
                          timeout=90, check=True, **kwargs).stdout.strip()


def sql(db, statement):
    return command(['docker','exec',PG,'psql','-v','ON_ERROR_STOP=1','-U','spec_agent','-d',db,'-Atc',statement])


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-directory',type=Path,required=True)
    parser.add_argument('--jar',type=Path,required=True)
    parser.add_argument('--database',required=True)
    args=parser.parse_args()
    directory=args.source_directory.resolve();jar=args.jar.resolve()
    base=(ROOT/'backend/build/p2-acceptance').resolve()
    if not directory.is_relative_to(base) or not jar.is_relative_to(base):raise ValueError('Isolated artifacts required')
    database=args.database
    if not re.fullmatch('spec_agent_p2_rotation_[a-z0-9_]+',database):raise ValueError('Dedicated rotation database required')
    restore=database+'_restore'
    for db in (database,restore):
        if sql('postgres',"select count(*) from pg_database where datname='"+db+"'")!='0':raise ValueError('Refusing to replace '+db)
    for port in (8391,8392):
        with socket.socket() as sock:
            if sock.connect_ex(('127.0.0.1',port))==0:raise ValueError('Acceptance port occupied')
    config={line.split('=',1)[0]:line.split('=',1)[1] for line in (directory/'workers.properties').read_text(encoding='utf-8').splitlines() if '=' in line and not line.startswith('#')}
    source=config['spring.datasource.url'].rsplit('/',1)[-1]
    if not re.fullmatch('spec_agent_p2_workers_[a-z0-9_]+',source):raise ValueError('Dedicated source required')
    if sql(source,"select count(*) from runtime_tasks where state not in ('SUCCEEDED','FAILED','CANCELLED')")!='0':raise ValueError('Drain source before snapshot')
    old=config.get('spec.agent.secret.master-key')
    if not old:
        profile=(ROOT/'backend/src/main/resources/application-test.yml').read_text(encoding='utf-8')
        old=re.search(r'^\s+master-key:\s*([A-Za-z0-9+/=]+)\s*$',profile,re.M).group(1)
    old_fp=hashlib.sha256(base64.b64decode(old)).hexdigest()
    if sql(source,'select primary_key_fingerprint from credential_key_admission where id=1')!=old_fp:raise ValueError('Source is not using the expected test-profile key')
    new=base64.b64encode(os.urandom(32)).decode();new_fp=hashlib.sha256(base64.b64decode(new)).hexdigest()
    output=base/database;output.mkdir(exist_ok=False)
    dump='/tmp/'+database+'.dump'
    command(['docker','exec',PG,'pg_dump','-U','spec_agent','-d',source,'-Fc','--exclude-table-data=spring_session*','-f',dump])
    def clone(db):
        command(['docker','exec',PG,'createdb','-U','spec_agent','-O','spec_agent',db])
        command(['docker','exec',PG,'pg_restore','-U','spec_agent','-d',db,'--exit-on-error',dump])
    clone(database)
    java=Path('E:/Java/jdk-21.0.10/bin/java.exe')
    driver=next((Path.home()/'.gradle/caches/modules-2/files-2.1/org.postgresql/postgresql').rglob('postgresql-*.jar'))
    classpath=os.pathsep.join([str(ROOT/'backend/build/classes/java/main'),str(driver)])
    processes=[]
    report={'sourceDatabase':source,'database':database,'restoredDatabase':restore,
            'jarSha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'scenarios':[]}
    def start(name,db,port,key,fallback=''):
        values=dict(config)
        values.update({'spring.datasource.url':'jdbc:postgresql://127.0.0.1:5546/'+db,
                       'spec.agent.brain.worker.enabled':'false','spec.agent.secret.master-key':key,
                       'spec.agent.secret.master-key-fallback':fallback})
        properties=output/(name+'.properties')
        properties.write_text('\n'.join(k+'='+v for k,v in values.items())+'\n',encoding='utf-8')
        env=dict(os.environ);env['SPRING_CONFIG_ADDITIONAL_LOCATION']=properties.as_uri()
        log=output/(name+'.log')
        with log.open('w',encoding='utf-8') as handle:
            proc=subprocess.Popen([str(java),'-jar',str(jar),'--server.port='+str(port)],cwd=ROOT/'backend',env=env,stdout=handle,stderr=subprocess.STDOUT)
        processes.append(proc)
        return proc,log
    def ready(proc,port):
        deadline=time.monotonic()+100
        while time.monotonic()<deadline:
            if proc.poll() is not None:raise RuntimeError('Acceptance JVM exited; inspect its isolated log')
            try:
                # API-only /api/health intentionally reports AGENT_WORKER_UNAVAILABLE (503).
                # This drill needs DB/process readiness, not execution-worker readiness.
                if httpx.get('http://127.0.0.1:'+str(port)+'/actuator/health',timeout=2).status_code==200:
                    time.sleep(2);return
            except httpx.HTTPError:pass
            time.sleep(1)
        raise TimeoutError('Acceptance JVM did not become ready')
    def stop(proc):
        if proc.poll() is None:proc.terminate();proc.wait(timeout=20)
    def probe(db,key):
        env=dict(os.environ);env.update({'P2_PROBE_JDBC_URL':'jdbc:postgresql://127.0.0.1:5546/'+db,
            'P2_PROBE_MASTER_KEY':key,'P2_PROBE_DB_PASSWORD':config.get('spring.datasource.password','p2_isolated_test')})
        return json.loads(command([str(java),'--class-path',classpath,str(ROOT/'tools/P2CredentialRotationProbe.java')],env=env))
    try:
        rejected,log=start('wrong-fleet-key',database,8391,new)
        rejected.wait(timeout=100)
        reason='Credential key differs from the registered fleet key' in log.read_text(encoding='utf-8',errors='replace')
        if rejected.returncode==0 or not reason:raise AssertionError('Fleet key mismatch was not rejected for the expected reason')
        report['scenarios'].append({'name':'different fleet primary rejected before serving','passed':True})
        # Maintenance window in the NEW database only: no instance is running while admission changes.
        sql(database,"update credential_key_admission set primary_key_fingerprint='"+new_fp+"' where id=1")
        first,_=start('rotate-new-with-old-fallback',database,8391,new,old);ready(first,8391)
        first_probe=probe(database,new)
        second,_=start('second-instance-new-only',database,8392,new);ready(second,8392)
        second_probe=probe(database,new)
        report['scenarios'].append({'name':'new primary plus old fallback migrates model and MCP; two JVMs start and new-only product readers decrypt','passed':True,'firstReader':first_probe,'secondReader':second_probe})
        stop(first);stop(second)
        sql(database,"update credential_key_admission set primary_key_fingerprint='"+old_fp+"' where id=1")
        rollback,_=start('rollback-old-with-new-fallback',database,8391,old,new);ready(rollback,8391)
        report['scenarios'].append({'name':'coordinated key rollback, product readers need only old primary afterwards','passed':True,'reader':probe(database,old)})
        stop(rollback)
        clone(restore)
        restored,_=start('restore-pre-rotation-backup',restore,8392,old);ready(restored,8392)
        report['scenarios'].append({'name':'restore pre-rotation PostgreSQL backup into a different database; startup and decryption succeed','passed':True,'reader':probe(restore,old)})
        report['passed']=True
    except Exception as error:
        report['passed']=False;report['failureType']=type(error).__name__
        raise
    finally:
        for proc in processes:stop(proc)
        report['limitations']=['Synthetic credentials; no provider or MCP network calls.',
            'Coordinated maintenance window required; not a rolling-key rotation guarantee.',
            'Backup restoration and compatible-current-binary key rollback; not permission to downgrade multi-organization data to an unsafe old binary.']
        (output/'key-rotation-result.json').write_text(json.dumps(report,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
        print(json.dumps({'passed':report['passed'],'report':str(output/'key-rotation-result.json')}),flush=True)


if __name__=='__main__':main()
