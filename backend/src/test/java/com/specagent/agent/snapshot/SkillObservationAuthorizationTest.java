package com.specagent.agent.snapshot;

import com.specagent.agent.protocol.CapabilityResultView;
import com.specagent.skill.runtime.*;
import com.specagent.access.AccessDeniedException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SkillObservationAuthorizationTest {
    private final SkillResourceService resources=mock(SkillResourceService.class);
    private final RunAttributionLookupPort context=mock(RunAttributionLookupPort.class);
    private final SkillObservationAuthorization policy=new SkillObservationAuthorization(resources,context);
    private final UUID project=UUID.randomUUID(),run=UUID.randomUUID(),version=UUID.randomUUID();
    private CapabilityResultView result(String status,Map<String,Object> content,Map<String,Object> provenance) {
        return new CapabilityResultView("test","skill.activate",status,content,List.of(),provenance);
    }
    @Test void safeFailureReasonDoesNotRequirePrivateSourceReuse() {
        assertThat(policy.visible(project,result("FAILED",Map.of("reason","refused"),Map.of()))).isTrue();
        verifyNoInteractions(resources);
    }
    @Test void failureStatusCannotSmuggleInstructionsAsAnOrdinaryFailure() {
        assertThat(policy.visible(project,result("FAILED",Map.of("instructions","private"),Map.of()))).isFalse();
    }
    @Test void validSourceIsVerifiedAgainstCurrentExecutingRun() {
        when(context.currentExecutingRun()).thenReturn(run);
        assertThat(policy.visible(project,result("SUCCEEDED",Map.of("instructions","private"),
                Map.of("versionId",version.toString())))).isTrue();
        verify(resources).requireActivatedVersion(project,run,version);
    }
    @Test void unactivatedVersionIsNotAVisiblePromptObservation() {
        when(context.currentExecutingRun()).thenReturn(run);
        doThrow(new SkillResourceRejectedException("refused")).when(resources).requireActivatedVersion(project,run,version);
        assertThat(policy.visible(project,result("SUCCEEDED",Map.of(),Map.of("versionId",version.toString())))).isFalse();
    }
    @Test void lostRoleDoesNotContinueBuildingOtherPromptContent() {
        when(context.currentExecutingRun()).thenReturn(run);
        doThrow(AccessDeniedException.notVisible("refused")).when(resources).requireActivatedVersion(project,run,version);
        assertThatThrownBy(()->policy.visible(project,result("SUCCEEDED",Map.of(),Map.of("versionId",version.toString()))))
                .isInstanceOf(AccessDeniedException.class);
    }
    @Test void sourceRefusalHasStableRunFailureCodeAndSafeUserCopy() {
        var failure=new FrozenSourceAuthorizationRefusedException();
        assertThat(com.specagent.agent.runtime.RunFailureReasons.reasonCode(failure))
                .isEqualTo("FROZEN_INPUT_SOURCE_NOT_AUTHORIZED");
        assertThat(com.specagent.agent.runtime.RunFailureReasons.userCopyFor(failure.getMessage())).isNotBlank();
    }
}
