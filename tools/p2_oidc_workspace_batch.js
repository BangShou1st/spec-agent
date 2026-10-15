// Evaluate through browser-skill in the isolated acceptance front end after OIDC login.
// Uses the app API client; never exports cookies, CSRF values or credentials.
// This is concurrent API submission evidence, not a full UI flow.
(async () => {
  if (window.location.origin !== 'http://127.0.0.1:8282') throw new Error('Isolated acceptance origin required');
  const {apiClient} = await import('/src/shared/http/client.ts');
  const {accessSession} = await import('/src/features/access/accessSession.ts');
  const actor = accessSession.identity;
  if (!actor?.authenticated || actor.mode !== 'OIDC' || actor.orgId !== '1c9daeb6-dd85-4944-9802-fe49e51d44f1') throw new Error('Expected isolated OIDC identity');
  const prepared = await Promise.all(Array.from({length:8}, async (_, index) => {
    const p = await apiClient.post('/projects', {title:'P2 OIDC Worker '+Date.now()+' '+index});
    const n = await apiClient.post('/projects/'+p.id+'/nodes', {routeId:p.activeRouteId,subtype:'IDEA',content:{text:'测试 OIDC 主体的后台执行 '+index}});
    return {projectId:p.id,routeId:p.activeRouteId,nodeId:n.id};
  }));
  const runs = await Promise.all(prepared.map(async p => {
    const r = await apiClient.post('/projects/'+p.projectId+'/nodes/'+p.nodeId+'/query', {routeId:p.routeId,question:'请分析这个节点'});
    return {...p,runId:r.runId};
  }));
  return {actor:{userId:actor.userId,orgId:actor.orgId,source:actor.mode},runs};
})()
