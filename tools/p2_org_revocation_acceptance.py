#!/usr/bin/env python3
"""Real browser-skill OIDC identity switching + organization revocation during GA inference.

Uses only known isolated Keycloak fixture accounts, app client calls and metadata
queries. Never exports cookies/CSRF/tokens. Keeps the browser session open.
"""
import argparse,json,re,shutil,subprocess,time,uuid
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
APP='http://127.0.0.1:8282'
LOGOUT='http://127.0.0.1:8184/realms/spec-agent-p2/protocol/openid-connect/logout'


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--session',required=True);p.add_argument('--directory',type=Path,required=True)
    p.add_argument('--org',required=True);p.add_argument('--project',required=True);p.add_argument('--private-thread',required=True)
    a=p.parse_args();directory=a.directory.resolve();base=(ROOT/'backend/build/p2-acceptance').resolve()
    if not directory.is_relative_to(base):raise ValueError('Dedicated acceptance directory required')
    config=(directory/'workers.properties').read_text(encoding='utf-8')
    database=re.search(r'^spring.datasource.url=jdbc:postgresql://127\.0\.0\.1:5546/(spec_agent_p2_workers_[a-z0-9_]+)$',config,re.M).group(1)
    org,project,thread=[str(uuid.UUID(v)) for v in (a.org,a.project,a.private_thread)]
    bsk=shutil.which('bsk')
    def command(args,timeout=40):return subprocess.run(args,capture_output=True,text=True,encoding='utf-8',errors='replace',check=True,timeout=timeout).stdout.strip()
    def cli(*args):return command([bsk,*args,'--session',a.session])
    def observe():
        for _ in range(3):
            try:text=cli('observe','--max-tokens','2500')
            except subprocess.CalledProcessError as error:
                details=(error.stdout or '')+(error.stderr or '')
                if 'Document changed during observation' not in details and 'observation ref was not found' not in details:raise
                time.sleep(.1);continue
            if not text.startswith('error:'):return text
            time.sleep(.1)
        raise RuntimeError('Unable to obtain fresh browser observation')
    def evaluate(expr):
        r=json.loads(cli('evaluate','--json',expr))
        if not r.get('ok'):raise RuntimeError('Browser operation failed: '+str(r.get('error')))
        return r.get('value')
    def app(code):
        return evaluate("(async()=>{if(location.origin!=='"+APP+"')throw Error('Wrong origin');const {apiClient}=await import('/src/shared/http/client.ts');const access=await import('/src/features/access/accessSession.ts');"+code+"})()")
    def identity():return app('await access.refreshSession();const i=access.accessSession.identity;return i?{authenticated:i.authenticated,userId:i.userId,orgId:i.orgId,displayName:i.displayName}:null;')
    def ref(text,label):
        m=re.search(r'(@e\d+) '+label,text)
        if not m:raise RuntimeError('Expected observed control missing: '+label)
        return m.group(1)
    def login(username):
        cli('navigate',APP);observe()
        app('if(access.accessSession.identity?.authenticated)await access.logout();return true;')
        cli('navigate',LOGOUT);text=observe()
        m=re.search(r'(@e\d+) button "Logout"',text)
        if m:cli('click','--ref',m.group(1))
        elif 'logged out' not in text.lower():raise RuntimeError('Unexpected IdP logout page')
        cli('navigate',APP);text=observe()
        cli('click','--ref',ref(text,'link "使用组织账户登录"'))
        text=observe();cli('fill','--ref',ref(text,'textbox "Username or email"'),'--value',username)
        text=observe();cli('fill','--ref',ref(text,'textbox "Password"'),'--value','P2-Local-Test-2026!')
        text=observe();cli('click','--ref',ref(text,'button "Sign In"'))
        for _ in range(20):
            time.sleep(.1)
            try:
                observe();i=identity()
                if i and i['authenticated']:
                    if not i['displayName'].lower().startswith(username):raise RuntimeError('Wrong OIDC identity')
                    print('OIDC login '+username+' verified',flush=True);return i
            except RuntimeError:
                continue
        raise RuntimeError('OIDC login did not complete')
    def select(org_id):
        app('access.switchOrganization('+json.dumps(org_id)+');return true;')
        for _ in range(20):
            time.sleep(.1)
            try:
                observe();i=identity()
                if i and i['orgId']==org_id:return
            except RuntimeError:pass
        raise RuntimeError('Organization selection did not settle')
    def sql(query):return command(['docker','exec','spec-agent-p2-auth','psql','-v','ON_ERROR_STOP=1','-U','spec_agent','-d',database,'-Atc',query])
    def facts(run):
        run=str(uuid.UUID(run))
        return json.loads(sql("""select json_build_object('runStatus',r.status,'taskState',t.state,'modelCalls',e.model_calls,
          'calls',(select coalesce(json_agg(json_build_object('kind',kind,'status',status)),'[]') from ga_execution_calls where run_id=r.id),
          'events',(select coalesce(json_agg(type order by sequence),'[]') from global_assistant_run_events where run_id=r.id),
          'assistantMessages',(select count(*) from global_assistant_messages where run_id=r.id and role='ASSISTANT'),
          'capacity',(select count(*) from runtime_task_capacity where task_id=t.id),
          'pendingOutbox',(select count(*) from authorization_revocation_outbox where processed_at is null))
          from global_assistant_runs r join runtime_tasks t on t.ga_run_id=r.id left join ga_executions e on e.run_id=r.id where r.id='RUN'""".replace('RUN',run)))
    report={'database':database,'orgId':org,'projectId':project,'privateThreadId':thread,
            'scope':'real OIDC browser login; membership and private-resource checks use app HTTP client; not a complete five-user UI matrix'}
    removed=False
    try:
        bob=login('bob');report['bob']=bob
        other=app("return await apiClient.post('/organizations',{name:'P2 Bob isolation '+Date.now()});")
        report['bobOtherOrganization']=other['id']
        alice=login('alice');select(org);report['alice']=alice
        report['ownerPositiveControls']=app("await apiClient.get('/projects/"+project+"');await apiClient.get('/global-assistant/threads/"+thread+"');return {projectReadable:true,ownThreadReadable:true};")
        app("await apiClient.post('/organizations/"+org+"/members',{userId:"+json.dumps(bob['userId'])+",role:'OWNER'});return true;")
        app("await apiClient.delete('/projects/"+project+"/members/"+bob['userId']+"');return true;")
        login('bob');select(org)
        report['ownerWithoutProjectRole']=app("""const out={};for(const [name,path] of [
          ['project','/projects/PROJECT'],['privateThread','/global-assistant/threads/THREAD']]){
          try{await apiClient.get(path);out[name]={status:200}}catch(e){out[name]={status:e.status,code:e.code}}}
          const rows=await apiClient.get('/projects');out.projectListed=rows.some(p=>p.id==='PROJECT');
          try{await apiClient.put('/projects/PROJECT/members/'+access.accessSession.identity.userId,{role:'MANAGER'});out.selfGrant={status:200}}
          catch(e){out.selfGrant={status:e.status,code:e.code}}return out;""".replace('PROJECT',project).replace('THREAD',thread))
        if report['ownerWithoutProjectRole']['project']['status']!=404 or report['ownerWithoutProjectRole']['privateThread']['status']!=404 or report['ownerWithoutProjectRole']['projectListed'] or report['ownerWithoutProjectRole']['selfGrant']['status']!=200:
            raise AssertionError('Organization owner bypassed private/project authorization')
        report['afterExplicitSelfGrant']=app("await apiClient.get('/projects/"+project+"');try{await apiClient.get('/global-assistant/threads/"+thread+"');return {projectReadable:true,privateThreadStatus:200}}catch(e){return {projectReadable:true,privateThreadStatus:e.status}}")
        report['selfGrantAuditCount']=int(sql("select count(*) from security_audit_events where actor_user_id='"+bob['userId']+"' and resource_id='"+project+"' and action='PROJECT_ROLE'"))
        if report['afterExplicitSelfGrant']['privateThreadStatus']!=404 or report['selfGrantAuditCount']<1:raise AssertionError('Explicit governance must remain audited and cannot expose private conversations')
        login('alice');select(org)
        fixture=app("const t=await apiClient.post('/global-assistant/threads');const r=await apiClient.post('/global-assistant/threads/'+t.threadId+'/runs',{message:'P2 organization revocation while native inference is in flight. Direct response, no tools.',uiContext:{currentPage:'PROJECTS',selectedEntity:null}});return {runId:r.runId,threadId:t.threadId};")
        report['fixture']=fixture
        login('bob');select(org)
        before=facts(fixture['runId']);report['beforeRevocation']=before
        if before['runStatus']!='RUNNING' or not any(c['kind']=='MODEL' and c['status']=='RESERVED' for c in before['calls']):raise RuntimeError('Identity switch missed the in-flight window; no revocation injected')
        app("await apiClient.delete('/organizations/"+org+"/members/"+alice['userId']+"');return true;")
        removed=True
        print('Organization revocation submitted by second OWNER while MODEL RESERVED',flush=True)
        for _ in range(50):
            after=facts(fixture['runId'])
            if after['runStatus'] not in ('CREATED','RUNNING') and after['pendingOutbox']==0:break
            time.sleep(.5)
        report['afterRevocation']=after
        time.sleep(32);late=facts(fixture['runId']);report['afterOriginalModelReturn']=late
        report['passed']=(late['runStatus'] in ('FAILED','CANCELLED') and late['taskState'] in ('FAILED','CANCELLED','SUCCEEDED')
            and late['modelCalls']==before['modelCalls']==1 and late['assistantMessages']==0 and late['capacity']==0
            and late['pendingOutbox']==0 and late['events']==after['events'] and 'RUN_COMPLETED' not in late['events'])
        if not report['passed']:raise AssertionError('Organization revocation failed to fence the old model output')
    finally:
        if removed:
            app("await apiClient.post('/organizations/"+org+"/members',{userId:"+json.dumps(alice['userId'])+",role:'OWNER'});return true;")
            report['aliceMembershipRestoredThroughProductApi']=True
        target=directory/'organization-revocation-result.json'
        if target.exists():target.rename(directory/('organization-revocation-prior-'+str(time.time_ns())+'.json'))
        target.write_text(json.dumps(report,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
        print(json.dumps({'passed':report.get('passed',False),'report':str(target),'session':a.session}),flush=True)


if __name__=='__main__':main()
