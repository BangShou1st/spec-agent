package com.specagent.capability;

import com.specagent.access.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import java.sql.ResultSet;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** SQL submission and row mapping only. PostgreSQL execution, locks and Flyway NOT RUN. */
class RebuildWaitRepositoryOfflineTest {
    final NamedParameterJdbcTemplate jdbc=mock(NamedParameterJdbcTemplate.class);
    final AuthorizationGuard guard=mock(AuthorizationGuard.class);
    final RebuildWaitRepository repository=new RebuildWaitRepository(jdbc,guard);
    @Test void bindingRejectsMissingChangedOrCrossProjectActualTaskBeforePending() {
        var task=new RebuildWaitRepository.Task(UUID.randomUUID(),8,UUID.randomUUID(),UUID.randomUUID(),"RUNNING");
        assertThatThrownBy(()->repository.bind(UUID.randomUUID(),task)).hasMessage("REBUILD_WAIT_BINDING_REJECTED");
        verify(jdbc).update(argThat((String sql)->sql.contains("e.project_id=i.project_id")
                && sql.contains("e.readiness_revision=:revision") && sql.contains("i.status='RUNNING'")),anyMap());
    }
    @Test void selectConstrainsActualProjectOrganizationAndOnlyExistingLiveTask() {
        UUID project=UUID.randomUUID(),org=UUID.randomUUID();repository.select(project,org);
        verify(jdbc).query(argThat((String sql)->sql.contains("project_id=:project AND corpus_id=:project AND org_id=:org")
                && sql.contains("'QUEUED','RUNNING','READY'") && sql.contains("FOR SHARE")),eq(Map.of("project",project,"org",org)),any(RowMapper.class));
    }
    @Test void boundOutcomeJoinsExactGenerationRevisionProjectAndOrganization() throws Exception {
        UUID id=UUID.randomUUID(),generation=UUID.randomUUID(),project=UUID.randomUUID(),org=UUID.randomUUID();
        when(jdbc.query(anyString(),anyMap(),any(RowMapper.class))).thenAnswer(call->{
            String sql=call.getArgument(0);assertThat(sql).contains("o.generation_id=b.generation_id", "o.readiness_revision=b.readiness_revision",
                    "o.org_id=e.org_id AND o.project_id=e.project_id","e.project_id=e.corpus_id");
            var rs=mock(ResultSet.class);when(rs.getObject("id",UUID.class)).thenReturn(generation);
            when(rs.getObject("project_id",UUID.class)).thenReturn(project);when(rs.getObject("org_id",UUID.class)).thenReturn(org);
            when(rs.getLong("readiness_revision")).thenReturn(9L);when(rs.getString("state")).thenReturn(null);
            RowMapper<?> mapper=call.getArgument(2);return List.of(mapper.mapRow(rs,0));
        });
        var task=repository.bound(id).orElseThrow();assertThat(task.revision()).isEqualTo(9);assertThat(task.state()).isNull();
    }
    @Test void boundedRotatingScanCannotBeStarvedByRepeatedRevokedOrUnavailableOwner() {
        repository.pending(17);
        verify(jdbc).query(argThat((String sql)->sql.contains("last_checked_at=clock_timestamp()")
                && sql.contains("o.readiness_revision=b.readiness_revision") && sql.contains("ORDER BY b.last_checked_at,i.id")
                && sql.contains("LIMIT :limit FOR UPDATE OF b SKIP LOCKED")),eq(Map.of("limit",17)),any(RowMapper.class));
        assertThatThrownBy(()->repository.pending(0)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void absentPersistedDelegationCannotClaimAProject() {
        assertThatThrownBy(()->repository.authorize(UUID.randomUUID(),UUID.randomUUID())).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(guard);
    }
    @Test void persistedUserMustPassLiveAuthorizationAfterSharedScopeLocks() {
        UUID run=UUID.randomUUID(),project=UUID.randomUUID(),org=UUID.randomUUID();var actor=ActorContext.oidc(UUID.randomUUID(),org);
        when(jdbc.query(anyString(),anyMap(),any(RowMapper.class))).thenReturn(List.of(actor));
        doThrow(AccessDeniedException.forbidden(AccessReason.MEMBERSHIP_DISABLED,"revoked")).when(guard).requireProjectFor(actor,project,Capability.PROJECT_WRITE);
        assertThatThrownBy(()->repository.authorize(run,project)).isInstanceOf(AccessDeniedException.class);
        var order=inOrder(guard);order.verify(guard).lockSharedFailClosed(anyList());order.verify(guard).requireProjectFor(actor,project,Capability.PROJECT_WRITE);
    }
}
