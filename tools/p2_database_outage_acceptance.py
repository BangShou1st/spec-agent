#!/usr/bin/env python3
"""Crash/restart only the isolated acceptance PostgreSQL container during OIDC GA execution.

Refuses to run alongside Gradle test JVMs or the rotation drill. Restarts verified
acceptance workers because admission loss is deliberately latched until restart.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import uuid
import httpx

ROOT=Path(__file__).resolve().parents[1]
PG='spec-agent-p2-auth'


def command(args,timeout=60):
    return subprocess.run(args,capture_output=True,text=True,encoding='utf-8',errors='replace',
                          timeout=timeout,check=True).stdout.strip()


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--session',required=True);p.add_argument('--directory',type=Path,required=True)
    p.add_argument('--jar',type=Path,required=True);p.add_argument('--org',required=True)
    args=p.parse_args();directory=args.directory.resolve();jar=args.jar.resolve();org=str(uuid.UUID(args.org))
    base=(ROOT/'backend/build/p2-acceptance').resolve()
    if not directory.is_relative_to(base) or not jar.is_relative_to(base):raise ValueError('Isolated acceptance artifacts required')
    config=(directory/'workers.properties').read_text(encoding='utf-8')
    match=re.search(r'^spring.datasource.url=jdbc:postgresql://127\.0\.0\.1:5546/(spec_agent_p2_workers_[a-z0-9_]+)$',config,re.M)
    if not match:raise ValueError('Dedicated database required')
    database=match.group(1)
    def sql(statement):return command(['docker','exec',PG,'psql','-v','ON_ERROR_STOP=1','-U','spec_agent','-d',database,'-Atc',statement])
    if sql("select count(*) from runtime_tasks where state not in ('SUCCEEDED','FAILED','CANCELLED')")!='0':raise ValueError('Drain acceptance database first')
    ps="@(Get-CimInstance Win32_Process | Where-Object {$_.Name -eq 'java.exe' -and $_.CommandLine -match 'Gradle Test Executor'}) | Measure-Object | Select-Object -ExpandProperty Count"
    if command(['powershell','-NoProfile','-Command',ps])!='0':raise ValueError('Do not interrupt the PostgreSQL container while Gradle tests are running')
    ps="@(Get-NetTCPConnection -State Listen -LocalPort 8391,8392 -ErrorAction SilentlyContinue) | Measure-Object | Select-Object -ExpandProperty Count"
    if command(['powershell','-NoProfile','-Command',ps])!='0':raise ValueError('Wait for the key rotation drill to finish')
    workers=[]
    for name,port in [('A',8383),('B',8384)]:
        ps="$p=Get-NetTCPConnection -State Listen -LocalPort "+str(port)+"; Get-CimInstance Win32_Process -Filter ('ProcessId='+$p.OwningProcess) | Select-Object ProcessId,ExecutablePath,CommandLine | ConvertTo-Json -Compress"
        proc=json.loads(command(['powershell','-NoProfile','-Command',ps]))
        if str(jar).replace('\\','/').lower() not in proc['CommandLine'].replace('\\','/').lower() or not proc['ExecutablePath'].lower().endswith('java.exe'):
            raise ValueError('Unverified worker on port '+str(port))
        workers.append({'name':name,'port':port,'pid':proc['ProcessId'],'java':proc['ExecutablePath']})
    bsk=shutil.which('bsk')
    def browser(expression):
        result=json.loads(command([bsk,'evaluate','--session',args.session,'--json',expression],timeout=40))
        if not result.get('ok'):raise RuntimeError('Authenticated browser operation failed')
        return result['value']
    def create(label):
        return browser("""(async()=>{if(location.origin!=='http://127.0.0.1:8282')throw Error('Isolated origin required');
          const {apiClient}=await import('/src/shared/http/client.ts');const {accessSession}=await import('/src/features/access/accessSession.ts');
          const i=accessSession.identity;if(!i?.authenticated||i.mode!=='OIDC'||i.orgId!==ORG)throw Error('Expected OIDC fixture');
          const t=await apiClient.post('/global-assistant/threads');const r=await apiClient.post('/global-assistant/threads/'+t.threadId+'/runs',
          {message:LABEL,uiContext:{currentPage:'PROJECTS',selectedEntity:null}});return {runId:r.runId,threadId:t.threadId,userId:i.userId,orgId:i.orgId};})()""".replace('ORG',json.dumps(org)).replace('LABEL',json.dumps(label)))
    def facts(run):
        run=str(uuid.UUID(run))
        return json.loads(sql("""select json_build_object('runStatus',r.status,'errorCode',r.error_code,'taskState',t.state,
          'taskError',t.last_error_code,'epoch',t.execution_epoch,'modelCalls',e.model_calls,
          'calls',(select coalesce(json_agg(json_build_object('kind',kind,'status',status)),'[]') from ga_execution_calls where run_id=r.id),
          'events',(select coalesce(json_agg(type order by sequence),'[]') from global_assistant_run_events where run_id=r.id),
          'assistantMessages',(select count(*) from global_assistant_messages where run_id=r.id and role='ASSISTANT'),
          'capacity',(select count(*) from runtime_task_capacity where task_id=t.id)) from global_assistant_runs r
          join runtime_tasks t on t.ga_run_id=r.id left join ga_executions e on e.run_id=r.id where r.id='RUN'""".replace('RUN',run)))
    report={'database':database,'jarSha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'scenario':'real isolated PostgreSQL crash during MODEL RESERVED, fail closed, restart admission, fresh work completes'}
    started=[];db_stopped=False
    def restart_workers():
        for worker in workers:
            check=subprocess.run(['tasklist','/FI','PID eq '+str(worker['pid']),'/NH'],capture_output=True,text=True)
            if str(worker['pid']) in check.stdout:command(['taskkill','/PID',str(worker['pid']),'/F'])
        env=dict(os.environ);env['SPRING_CONFIG_ADDITIONAL_LOCATION']=(directory/'workers.properties').as_uri()
        for worker in workers:
            log=directory/(worker['name']+'-after-db-fault.log')
            with log.open('w',encoding='utf-8') as handle:
                proc=subprocess.Popen([worker['java'],'-jar',str(jar),'--server.port='+str(worker['port'])],cwd=ROOT/'backend',env=env,stdout=handle,stderr=subprocess.STDOUT)
            started.append(proc);worker['restartedPid']=proc.pid
        deadline=time.monotonic()+100
        while time.monotonic()<deadline:
            if any(proc.poll() is not None for proc in started):raise RuntimeError('Acceptance worker failed to restart')
            try:
                if all(httpx.get('http://127.0.0.1:'+str(w['port'])+'/api/health',timeout=2).status_code==200 for w in workers):return
            except httpx.HTTPError:pass
            time.sleep(1)
        raise TimeoutError('Workers did not recover admission after restart')
    try:
        fixture=create('P2 database crash acceptance. Reply directly without tools.');report['fixture']=fixture
        for _ in range(40):
            before=facts(fixture['runId'])
            if any(c['kind']=='MODEL' and c['status']=='RESERVED' for c in before['calls']):break
            time.sleep(.2)
        else:raise RuntimeError('Model call never became in flight; no database fault injected')
        report['beforeOutage']=before
        command(['docker','stop','-t','0',PG]);db_stopped=True
        print('Isolated PostgreSQL stopped; waiting beyond the 15s task lease',flush=True)
        time.sleep(22)
        report['healthDuringOutage']={str(w['port']):httpx.get('http://127.0.0.1:'+str(w['port'])+'/api/health',timeout=5).status_code for w in workers}
        command(['docker','start',PG]);db_stopped=False
        for _ in range(40):
            try:
                if sql('select 1')=='1':break
            except subprocess.CalledProcessError:pass
            time.sleep(1)
        else:raise TimeoutError('PostgreSQL failed to restart')
        report['healthBeforeWorkerRestart']={str(w['port']):httpx.get('http://127.0.0.1:'+str(w['port'])+'/api/health',timeout=5).status_code for w in workers}
        restart_workers()
        for _ in range(50):
            after=facts(fixture['runId'])
            if after['taskState']=='FAILED' and after['runStatus']=='FAILED':break
            time.sleep(1)
        report['afterRecovery']=after
        time.sleep(20);late=facts(fixture['runId']);report['afterLateReturn']=late
        command([bsk,'navigate','http://127.0.0.1:8282','--session',args.session])
        for _ in range(20):
            if browser("(async()=>{const {accessSession}=await import('/src/features/access/accessSession.ts');return !!accessSession.identity?.authenticated})()") is True:break
            time.sleep(.5)
        fresh=create('P2 after database recovery. Reply directly without tools.');report['freshFixture']=fresh
        for _ in range(50):
            fresh_facts=facts(fresh['runId'])
            if fresh_facts['runStatus'] not in ('CREATED','RUNNING'):break
            time.sleep(1)
        report['freshRun']=fresh_facts
        report['passed']=(all(s==503 for s in report['healthDuringOutage'].values())
            and all(s==503 for s in report['healthBeforeWorkerRestart'].values())
            and late['runStatus']=='FAILED' and late['taskState']=='FAILED' and late['capacity']==0
            and late['modelCalls']==before['modelCalls']==1 and late['assistantMessages']==0
            and late['events']==after['events'] and 'RUN_COMPLETED' not in late['events']
            and fresh_facts['runStatus']=='COMPLETED' and fresh_facts['capacity']==0)
        if not report['passed']:raise AssertionError('Database fault acceptance failed; inspect retained facts')
    finally:
        if db_stopped:command(['docker','start',PG])
        if not started:
            # Restore the isolated fleet even when an observation step failed after the crash.
            if 'beforeOutage' in report:restart_workers()
        report['workers']=[{k:v for k,v in w.items() if k!='java'} for w in workers]
        target=directory/'database-outage-result.json'
        target.write_text(json.dumps(report,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
        print(json.dumps({'passed':report.get('passed',False),'report':str(target)}),flush=True)


if __name__=='__main__':main()
