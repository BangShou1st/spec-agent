// Evaluate through browser-skill in the isolated acceptance front end after real OIDC login.
// Uses the app API client; never exports cookies, CSRF values or credentials.
// This is authenticated concurrent API submission evidence, not a full UI flow.
(async () => {
  if (window.location.origin !== 'http://127.0.0.1:8282') throw new Error('Isolated acceptance origin required');
  const {apiClient} = await import('/src/shared/http/client.ts');
  const {accessSession} = await import('/src/features/access/accessSession.ts');
  const actor = accessSession.identity;
  if (!actor?.authenticated || actor.mode !== 'OIDC' || actor.orgId !== '1c9daeb6-dd85-4944-9802-fe49e51d44f1') throw new Error('Expected isolated OIDC identity');
  const count = 4;
  const created = await Promise.all(Array.from({length: count}, async (_, index) => {
    const t = await apiClient.post('/global-assistant/threads');
    const r = await apiClient.post('/global-assistant/threads/' + t.threadId + '/runs',
      {message: 'P2 GA native fixture acceptance ' + index, uiContext: {currentPage: 'PROJECTS', selectedEntity: null}});
    return {threadId: t.threadId, runId: r.runId, status: r.status};
  }));
  return {actor: {userId: actor.userId, orgId: actor.orgId, source: actor.mode}, runs: created};
})()
