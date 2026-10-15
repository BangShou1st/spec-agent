package com.specagent.agent.policy;

import com.specagent.common.ApiException;
import com.specagent.workspace.project.ProjectService;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentProposalVisibilityTest {
    private final AgentProposalRepository repository=mock(AgentProposalRepository.class);
    private final ProjectService projects=mock(ProjectService.class);
    private final AgentProposalService service=new AgentProposalService(repository);
    private final UUID proposalId=UUID.randomUUID(),project=UUID.randomUUID();

    private void savedInvisibleProposal() {
        service.setProjectAccess(projects);
        AgentProposal proposal=mock(AgentProposal.class);
        when(proposal.projectId()).thenReturn(project);
        when(repository.findById(proposalId)).thenReturn(Optional.of(proposal));
        when(projects.getProjectForActor(project)).thenThrow(ApiException.notFound("PROJECT_NOT_FOUND","Project not found"));
    }

    @Test void hiddenProposalReadIsIndistinguishableFromMissing() {
        savedInvisibleProposal();
        assertThat(service.getProposal(proposalId)).isEmpty();
    }

    @Test void hiddenAndMissingProposalDecisionHaveSameErrorContract() {
        savedInvisibleProposal();
        assertThatThrownBy(()->service.rejectProposal(proposalId,"user"))
                .isInstanceOf(ApiException.class).extracting(ex->((ApiException)ex).code()).isEqualTo("PROPOSAL_NOT_FOUND");
        when(repository.findById(proposalId)).thenReturn(Optional.empty());
        assertThatThrownBy(()->service.rejectProposal(proposalId,"user"))
                .isInstanceOf(ApiException.class).extracting(ex->((ApiException)ex).code()).isEqualTo("PROPOSAL_NOT_FOUND");
        verify(repository,never()).findByIdForUpdate(any());
        verify(projects,never()).authorizeWrite(any());
    }

    @Test void authenticationAndMembershipErrorsAreNotHiddenAsNotFound() {
        savedInvisibleProposal();
        doThrow(ApiException.unauthorized("AUTH_REQUIRED","Login required")).when(projects).getProjectForActor(project);
        assertThatThrownBy(()->service.getProposal(proposalId)).isInstanceOf(ApiException.class)
                .extracting(ex->((ApiException)ex).code()).isEqualTo("AUTH_REQUIRED");
    }
}
