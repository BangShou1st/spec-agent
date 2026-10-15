#!/usr/bin/env python3
"""Kill a verified isolated acceptance JVM during a real OIDC GA model call.

Authenticated submissions use browser-skill and the application's HTTP client;
no browser session credentials leave the browser. This checks honest failure,
not successful GA checkpoint recovery (which remains disabled by default).
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

ROOT = Path(__file__).resolve().parents[1]


def command(args, timeout=30):
    result = subprocess.run(args, capture_output=True, text=True, encoding='utf-8',
                            errors='replace', timeout=timeout, check=True)
    return result.stdout.strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--session', required=True)
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--org', required=True)
    args = parser.parse_args()
    directory, jar = args.directory.resolve(), args.jar.resolve()
    acceptance = (ROOT / 'backend/build/p2-acceptance').resolve()
    if not directory.is_relative_to(acceptance) or not jar.is_relative_to(acceptance):
        raise ValueError('Only isolated acceptance artifacts are allowed')
    text = (directory / 'workers.properties').read_text(encoding='utf-8')
    match = re.search(r'^spring.datasource.url=jdbc:postgresql://127\.0\.0\.1:5546/(spec_agent_p2_workers_[a-z0-9_]+)$', text, re.M)
    if not match or 'spec.agent.security.mode=OIDC' not in text:
        raise ValueError('Dedicated isolated OIDC database required')
    database = match.group(1)
    def sql(statement):
        return command(['docker', 'exec', 'spec-agent-p2-auth', 'psql', '-v', 'ON_ERROR_STOP=1',
                        '-U', 'spec_agent', '-d', database, '-Atc', statement])
    if sql("select count(*) from runtime_tasks where state not in ('SUCCEEDED','FAILED','CANCELLED')") != '0':
        raise ValueError('Acceptance database must be drained before injecting a fault')
    org = str(__import__('uuid').UUID(args.org))
    workers = {}
    for name, port in [('A', 8383), ('B', 8384)]:
        ps = "$p=Get-NetTCPConnection -State Listen -LocalPort " + str(port) + "; Get-CimInstance Win32_Process -Filter ('ProcessId='+$p.OwningProcess) | Select-Object ProcessId,ExecutablePath,CommandLine | ConvertTo-Json -Compress"
        process = json.loads(command(['powershell', '-NoProfile', '-Command', ps]))
        if str(jar).replace('\\','/').lower() not in process['CommandLine'].replace('\\','/').lower() or not process['ExecutablePath'].lower().endswith('java.exe'):
            raise ValueError('Refusing to kill an unverified JVM on port ' + str(port))
        ids = []
        for log_path in sorted(directory.glob(name+'*.log'), key=lambda path:path.stat().st_mtime):
            for line in log_path.read_text(encoding='utf-8', errors='replace').splitlines():
                if re.search(r'\bINFO\s+'+str(process['ProcessId'])+r'\s', line):
                    ids.extend(re.findall(r'Worker polling opened:.*?\(worker ([a-f0-9-]{36})\)', line))
        if not ids:
            raise ValueError('Missing live process worker UUID in '+name+' logs')
        workers[ids[-1]] = {'name': name, 'port': port, 'pid': process['ProcessId'], 'java': process['ExecutablePath']}
    expression = """(async()=>{
      if(location.origin!=='http://127.0.0.1:8282')throw Error('Isolated origin required');
      const {apiClient}=await import('/src/shared/http/client.ts');
      const {accessSession}=await import('/src/features/access/accessSession.ts');
      const i=accessSession.identity;
      if(!i?.authenticated||i.mode!=='OIDC'||i.orgId!==ORG)throw Error('Expected isolated OIDC identity');
      const t=await apiClient.post('/global-assistant/threads');
      const r=await apiClient.post('/global-assistant/threads/'+t.threadId+'/runs',
        {message:'P2 kill-worker acceptance: directly answer in one paragraph, do not call tools',uiContext:{currentPage:'PROJECTS',selectedEntity:null}});
      return {threadId:t.threadId,runId:r.runId,userId:i.userId,orgId:i.orgId};
    })()""".replace('ORG', json.dumps(org))
    result = json.loads(command([shutil.which('bsk'), 'evaluate', '--session', args.session, '--json', expression]))
    if not result.get('ok'):
        raise RuntimeError('Browser submission failed: ' + str(result.get('error')))
    fixture = result['value']
    run = str(__import__('uuid').UUID(fixture['runId']))
    query = """select json_build_object('runStatus',r.status,'errorCode',r.error_code,
      'taskState',t.state,'taskError',t.last_error_code,'epoch',t.execution_epoch,'workerId',t.worker_id,
      'actorSource',t.actor_source,'actorUserId',t.actor_user_id,'actorOrgId',t.actor_org_id,
      'modelCalls',e.model_calls,
      'calls',(select coalesce(json_agg(json_build_object('kind',kind,'status',status)),'[]') from ga_execution_calls where run_id=r.id),
      'events',(select coalesce(json_agg(type order by sequence),'[]') from global_assistant_run_events where run_id=r.id),
      'assistantMessages',(select count(*) from global_assistant_messages where run_id=r.id and role='ASSISTANT'),
      'capacity',(select count(*) from runtime_task_capacity where task_id=t.id))
      from global_assistant_runs r join runtime_tasks t on t.ga_run_id=r.id
      left join ga_executions e on e.run_id=r.id where r.id='RUN'""".replace('RUN', run)
    before = None
    for _ in range(40):
        value = sql(query)
        if value:
            before = json.loads(value)
            if any(c['kind'] == 'MODEL' and c['status'] == 'RESERVED' for c in before['calls']):
                break
        time.sleep(0.2)
    else:
        raise RuntimeError('Did not reach an in-flight native model call; no process was killed')
    victim = workers[before['workerId']]
    print('Killing verified worker ' + victim['name'] + ' during MODEL RESERVED', flush=True)
    command(['taskkill', '/PID', str(victim['pid']), '/F'])
    report = {'database': database, 'jarSha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
              'fixture': fixture, 'beforeKill': before, 'victim': {k:v for k,v in victim.items() if k!='java'},
              'passed': False, 'scenario': 'GA crash during unresolved model call; honest failure without replay'}
    try:
        # Keep the victim down past lease expiry; only its surviving peer can converge this Run.
        deadline = time.monotonic() + 75
        after = None
        while time.monotonic() < deadline:
            after = json.loads(sql(query))
            if after['taskState'] in ('FAILED', 'CANCELLED', 'SUCCEEDED') and after['runStatus'] not in ('CREATED', 'RUNNING'):
                break
            time.sleep(1)
        report['afterConvergence'] = after
        time.sleep(20)  # Wait for the original Python/provider continuation before checking late output.
        report['afterLateReturn'] = json.loads(sql(query))
        check = report['afterLateReturn']
        report['passed'] = (check['runStatus'] == 'FAILED' and check['taskState'] == 'FAILED'
                            and check['modelCalls'] == before['modelCalls'] == 1
                            and check['assistantMessages'] == 0 and check['capacity'] == 0
                            and 'RUN_FAILED' in check['events'] and 'RUN_COMPLETED' not in check['events']
                            and check['events'] == after['events'])
    finally:
        # Restore only the verified acceptance worker, even if an observation fails.
        env = dict(os.environ)
        env['SPRING_CONFIG_ADDITIONAL_LOCATION'] = (directory / 'workers.properties').as_uri()
        with (directory / (victim['name'] + '-after-kill.log')).open('a', encoding='utf-8') as output:
            proc = subprocess.Popen([victim['java'], '-jar', str(jar), '--server.port='+str(victim['port'])],
                                    cwd=ROOT/'backend', env=env, stdout=output, stderr=subprocess.STDOUT)
        report['restartedPid'] = proc.pid
        target = directory / 'kill-worker-result.json'
        target.write_text(json.dumps(report, indent=2, ensure_ascii=False)+'\n', encoding='utf-8')
        print(json.dumps({'passed': report['passed'], 'report': str(target), 'restartedPid': proc.pid}))
    if not report['passed']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
