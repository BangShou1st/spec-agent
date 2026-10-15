#!/usr/bin/env python3
"""Isolated P2 OIDC two-JVM/real-Python acceptance infrastructure.

Copies the dedicated OIDC acceptance database into a NEW database. Never drops
or replaces a database/container. Does not authenticate by exporting browser
cookies: authenticated scenarios are issued through browser-skill against the
front-end proxy. Starting processes alone is not scenario acceptance.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import time
import uuid
import httpx

ROOT = Path(__file__).resolve().parents[1]
BACKEND = ROOT / 'backend'
ARTIFACTS = BACKEND / 'build' / 'p2-acceptance'
PG = 'spec-agent-p2-auth'
SOURCE = 'spec_agent_p2_oidc_v91'
JAR = ARTIFACTS / 'runtime-current-connection.jar'
PROPERTIES = ARTIFACTS / 'v91-oidc.properties'
PORTS = {'A': 8383, 'B': 8384, 'brain': 8103}
DB_PREFIX = 'spec_agent_p2_workers_'

def command(args, **kwargs):
    return subprocess.run(args, capture_output=True, text=True, encoding='utf-8',
                          errors='strict', timeout=60, check=True, **kwargs).stdout.strip()

def sql(database, statement):
    return command(['docker', 'exec', PG, 'psql', '-v', 'ON_ERROR_STOP=1',
                    '-U', 'spec_agent', '-d', database, '-Atc', statement])

def prepare(directory, database):
    if not re.fullmatch(DB_PREFIX + '[a-z0-9_]+', database):
        raise ValueError('Dedicated acceptance database prefix required')
    if sql('postgres', "select count(*) from pg_database where datname='" + database + "'") != '0':
        raise ValueError('Refusing to replace or overwrite an existing database')
    if sql(SOURCE, "select mode || ':' || protocol_version from security_mode") != 'OIDC:13':
        raise ValueError('Source must be the accepted isolated OIDC protocol13 database')
    active = sql(SOURCE, "select count(*) from runtime_tasks where state not in ('SUCCEEDED','FAILED','CANCELLED')")
    if active != '0':
        raise ValueError('Source contains active tasks; refusing to clone live executions')
    dump = '/tmp/' + database + '.dump'
    command(['docker', 'exec', PG, 'pg_dump', '-U', 'spec_agent', '-d', SOURCE,
             '-Fc', '--exclude-table-data=spring_session*', '-f', dump])
    command(['docker', 'exec', PG, 'createdb', '-U', 'spec_agent', '-O', 'spec_agent', database])
    command(['docker', 'exec', PG, 'pg_restore', '-U', 'spec_agent', '-d', database,
             '--exit-on-error', dump])
    if sql(database, 'select count(*) from spring_session') != '0':
        raise ValueError('Session data must not be copied into the acceptance database')
    base = PROPERTIES.read_text(encoding='utf-8')
    settings = {
        'spring.datasource.url': 'jdbc:postgresql://127.0.0.1:5546/' + database,
        'spec.agent.brain.enabled': 'true',
        'spec.agent.brain.worker.enabled': 'true',
        'spec.agent.brain.worker.poll-interval-ms': '200',
        'spec.agent.brain.engine': 'fake',
        'spec.agent.brain.internal-secret': 'dev-internal-secret',
        'spec.agent.execution.multi-worker-enabled': 'true',
        'spec.agent.execution.claim-interval-ms': '200',
        'spec.agent.execution.lease-seconds': '15',
        'spec.agent.execution.heartbeat-seconds': '3',
        'spec.agent.execution.expiry-scan-interval-ms': '1000',
        'spec.global-assistant.brain-base-url': 'http://127.0.0.1:8103',
        'spec.agent.skill.builtin.seed-enabled': 'false',
        'spec.agent.skill.local-mirror-enabled': 'false',
        'spec.embedding-settings.worker-enabled': 'false',
        'spec.agent.retrieval.embedding.worker.enabled': 'false',
    }
    lines = [line for line in base.splitlines() if line.split('=', 1)[0] not in settings]
    lines.extend(key + '=' + value for key, value in settings.items())
    (directory / 'workers.properties').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    manifest = {'database': database, 'sourceDatabase': SOURCE,
                'securityMode': 'OIDC', 'securityProtocol': 13,
                'jar': str(JAR), 'jarSha256': hashlib.sha256(JAR.read_bytes()).hexdigest(),
                'ports': PORTS, 'workerEnabled': True, 'modelInference': 'fake',
                'workspaceDecisionEngine': 'fake', 'pythonMode': 'broker',
                'sessionDataCopied': False, 'stageCompleted': False,
                'scenarioResults': [], 'state': 'PREPARED'}
    save(directory, manifest)
    return manifest

def save(directory, manifest):
    (directory / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

def wait_health(name, proc, port, path, timeout=180):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if proc.poll() is not None:
            raise RuntimeError(name + ' exited with ' + str(proc.returncode))
        try:
            result = httpx.get('http://127.0.0.1:' + str(port) + path, timeout=3)
            if result.status_code == 200:
                return result.json()
        except httpx.HTTPError:
            pass
        time.sleep(1)
    raise TimeoutError(name + ' did not become healthy')

def serve(directory, manifest):
    processes = {}
    handles = []
    def start(name, args, cwd, env):
        fh = (directory / (name + '.log')).open('w', encoding='utf-8')
        handles.append(fh)
        proc = subprocess.Popen(args, cwd=str(cwd), env=env, stdout=fh, stderr=subprocess.STDOUT)
        processes[name] = proc
        manifest.setdefault('processes', {})[name] = {'pid': proc.pid, 'port': PORTS[name]}
        save(directory, manifest)
        print('START ' + name + ' pid=' + str(proc.pid), flush=True)
        return proc
    env = dict(os.environ)
    env.pop('SPEC_AGENT_TAVILY_API_KEY', None)
    env['SPEC_AGENT_BRAIN_INTERNAL_SECRET'] = 'dev-internal-secret'
    try:
        java = shutil.which('java')
        if not java:
            raise RuntimeError('Java executable unavailable')
        for name in ('A', 'B'):
            proc = start(name, [java, '-jar', str(JAR),
                '--spring.config.additional-location=' + (directory / 'workers.properties').as_uri(),
                '--server.port=' + str(PORTS[name])], BACKEND, env)
            wait_health(name, proc, PORTS[name], '/api/health')
            print('READY ' + name, flush=True)
        brain_env = dict(env, SPEC_AGENT_BRAIN_MODEL_MODE='broker',
                         SPEC_AGENT_INTERNAL_BROKER_URL='http://127.0.0.1:8383/internal/v1/model-inference')
        py = ROOT / 'agent-brain' / '.venv' / 'Scripts' / 'python.exe'
        proc = start('brain', [str(py), '-m', 'uvicorn', 'spec_agent_brain.app:create_app',
                    '--factory', '--host', '127.0.0.1', '--port', str(PORTS['brain'])],
                    ROOT / 'agent-brain', brain_env)
        health = wait_health('brain', proc, PORTS['brain'], '/health')
        manifest['pythonExecutionProtocols'] = health.get('gaExecutionProtocolVersions')
        manifest['state'] = 'READY'
        save(directory, manifest)
        print('READY two OIDC JVM Workers + real Python; database=' + manifest['database'], flush=True)
        while True:
            for name, proc in processes.items():
                if proc.poll() is not None:
                    raise RuntimeError(name + ' terminated unexpectedly')
            if (directory / 'stop.requested').exists():
                manifest['state'] = 'STOPPED'
                break
            time.sleep(2)
    except KeyboardInterrupt:
        manifest['state'] = 'STOPPED'
    except BaseException as error:
        manifest['state'] = 'FAILED'
        manifest['error'] = str(error)
        raise
    finally:
        for proc in processes.values():
            if proc.poll() is None:
                if os.name == 'nt':
                    subprocess.run(['taskkill','/PID',str(proc.pid),'/T','/F'], capture_output=True, timeout=15)
                else:
                    proc.terminate()
        for proc in processes.values():
            try:
                proc.wait(timeout=15)
            except subprocess.TimeoutExpired:
                proc.kill(); proc.wait(timeout=5)
        for fh in handles:
            fh.close()
        save(directory, manifest)

def observe_workspace(directory):
    directory = directory.resolve()
    if ARTIFACTS.resolve() not in directory.parents:
        raise ValueError('Observation directory must be within isolated acceptance artifacts')
    manifest = json.loads((directory / 'manifest.json').read_text(encoding='utf-8'))
    database = manifest['database']
    if not re.fullmatch(DB_PREFIX + '[a-z0-9_]+', database):
        raise ValueError('Dedicated acceptance database required')
    dispatch = json.loads((directory / 'oidc-workspace-dispatch.json').read_text(encoding='utf-8'))
    if dispatch.get('ok') is not True:
        raise ValueError('Browser batch did not report success')
    value = dispatch['value']
    if value['actor']['source'] != 'OIDC':
        raise ValueError('OIDC browser identity required')
    user = str(uuid.UUID(value['actor']['userId']))
    org = str(uuid.UUID(value['actor']['orgId']))
    run_ids = [str(uuid.UUID(run['runId'])) for run in value['runs']]
    if len(run_ids) != len(set(run_ids)) or not run_ids:
        raise ValueError('Distinct submitted runs required')
    ids = ','.join("'" + run + "'" for run in run_ids)
    task_ids = 'select id from runtime_tasks where workspace_run_id in (' + ids + ')'
    rows = json.loads(sql(database, "select json_agg(r) from (select t.workspace_run_id as run_id,"
        "t.state,t.actor_source,t.actor_user_id,t.actor_org_id,t.worker_id,t.attempt_count,"
        "t.last_error_code,r.status as business_status from runtime_tasks t join agent_runs r "
        "on r.id=t.workspace_run_id where t.workspace_run_id in (" + ids + ") order by t.created_at) r"))
    workers = int(sql(database, 'select count(distinct worker_id) from runtime_task_attempts where task_id in (' + task_ids + ')'))
    overlap = int(sql(database, 'select count(*) from runtime_task_attempts a join runtime_task_attempts b '
        'on a.worker_id<b.worker_id and a.started_at<coalesce(b.ended_at,now()) '
        'and b.started_at<coalesce(a.ended_at,now()) where a.task_id in (' + task_ids + ') '
        'and b.task_id in (' + task_ids + ')'))
    passed = len(rows) == len(run_ids) and workers == 2 and overlap > 0 and all(
        row['state'] == 'SUCCEEDED' and row['business_status'] == 'completed'
        and row['actor_source'] == 'OIDC' and row['actor_user_id'] == user
        and row['actor_org_id'] == org and row['attempt_count'] == 1 for row in rows)
    readiness = {}
    for name, port in manifest['ports'].items():
        path = '/health' if name == 'brain' else '/api/health'
        result = httpx.get('http://127.0.0.1:' + str(port) + path, timeout=5)
        readiness[name] = result.status_code == 200
    evidence = {'scenario': 'OIDC workspace actor reconstruction and two-worker parallel execution',
        'passed': passed, 'submitted': len(run_ids), 'rows': rows,
        'distinctWorkers': workers, 'overlapPairs': overlap, 'liveHealth': readiness,
        'database': database, 'jarSha256': manifest['jarSha256'],
        'submission': 'browser-skill evaluate calling the application API client after real OIDC login',
        'limitations': ['Workspace deterministic fake engine; does not exercise Python model calls',
                       'No observed peak-capacity claim', 'No revocation/late-output/SSE proof in this scenario',
                       'Not a full UI flow or full P2 completion']}
    (directory / 'workspace-evidence.json').write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: evidence[k] for k in ['passed','submitted','distinctWorkers','overlapPairs','liveHealth']}), flush=True)
    if not passed or not all(readiness.values()):
        raise RuntimeError('OIDC workspace scenario acceptance failed')


def main():
    global JAR
    parser = argparse.ArgumentParser()
    parser.add_argument('--database', default=DB_PREFIX + uuid.uuid4().hex[:12])
    parser.add_argument('--observe', type=Path)
    parser.add_argument('--resume', type=Path)
    parser.add_argument('--jar', type=Path)
    parser.add_argument('--native-fake', action='store_true')
    args = parser.parse_args()
    if args.jar:
        JAR = args.jar.resolve()
        if ARTIFACTS.resolve() not in JAR.parents or not JAR.is_file():
            raise ValueError('Isolated accepted jar required')
    if args.observe:
        observe_workspace(args.observe)
        return
    for port in PORTS.values():
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', port))
    if args.resume:
        directory = args.resume.resolve()
        if ARTIFACTS.resolve() not in directory.parents:
            raise ValueError('Isolated resume directory required')
        manifest = json.loads((directory / 'manifest.json').read_text(encoding='utf-8'))
        database = manifest['database']
        if not re.fullmatch(DB_PREFIX + '[a-z0-9_]+', database):
            raise ValueError('Dedicated resume database required')
        if sql(database, 'select mode || chr(58) || protocol_version from security_mode') != 'OIDC:13':
            raise ValueError('Resume security admission mismatch')
        if sql(database, "select count(*) from runtime_tasks where state not in ('SUCCEEDED','FAILED','CANCELLED')") != '0':
            raise ValueError('Drain active tasks before acceptance restart')
        manifest.setdefault('historicalProcessRuns', []).append({k:manifest.get(k) for k in ['jar','jarSha256','processes']})
        manifest['jar'] = str(JAR)
        manifest['jarSha256'] = hashlib.sha256(JAR.read_bytes()).hexdigest()
        flag = 'fake' if args.native_fake else 'disabled'
        settings = directory / 'workers.properties'
        lines = [line for line in settings.read_text(encoding='utf-8').splitlines()
                 if not line.startswith('spec.agent.testing.ga-native-transport=')]
        lines.append('spec.agent.testing.ga-native-transport=' + flag)
        settings.write_text('\n'.join(lines)+'\n',encoding='utf-8')
        manifest['nativeGaFixture'] = flag
        manifest['state'] = 'PREPARED'
        stop_file = directory / 'stop.requested'
        if stop_file.exists(): stop_file.unlink()
        save(directory,manifest)
        serve(directory,manifest)
        return
    if args.native_fake:
        raise ValueError('Enable native fixture explicitly on a prepared/resumed acceptance directory')
    directory = ARTIFACTS / args.database
    directory.mkdir(parents=True, exist_ok=False)
    if not JAR.is_file() or not PROPERTIES.is_file():
        raise RuntimeError('Current accepted isolated jar/properties missing')
    manifest = prepare(directory, args.database)
    print('PREPARED ' + str(directory), flush=True)
    serve(directory, manifest)

if __name__ == '__main__':
    main()
