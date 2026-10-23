package com.specagent.agent.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.specagent.agent.protocol.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/** Java final authority: allowed retrieval refs and confidence do not confer confirmation. */
class RagD4ClaimAuthorityTest {
    static final String REF="node:11111111-1111-4111-8111-111111111111";
    AgentRequestEnvelope request(String authority) throws Exception {
        var json=new ObjectMapper();
        ObjectNode body=(ObjectNode)json.readTree(Files.readString(Path.of("../contracts/fixtures/agent-input-valid.json")));
        ObjectNode snapshot=(ObjectNode)body.get("snapshot");
        snapshot.withArray("allowedSourceRefs").add(REF);
        snapshot.set("retrievedContext",json.valueToTree(List.of(Map.of("sourceRef",REF,"sourceKind","NODE","scope","PROJECT",
            "authority",authority,"content","合成检索证据","provenance",Map.of(),"location",Map.of(),"retrievalReason","synthetic"))));
        return AgentContracts.read(json.writeValueAsString(body),AgentRequestEnvelope.class);
    }
    void validate(AgentRequestEnvelope req,String status,List<String> refs) {
        var response=new AgentResponseEnvelope("agent-decision.v2",req.runId(),
            new StateUpdateResult(List.of(new ProposedClaim("constraint","合成断言",status,1.0,refs))),null,null,new UsageView(1,List.of()),Map.of());
        AgentBrainResponseValidator.validateStateUpdate(req,response);
    }
    @ParameterizedTest @ValueSource(strings={"EXTERNAL_EVIDENCE","ASSUMED","UNRESOLVED","REJECTED","DERIVED","CONFIRMED","USER_AUTHORED"})
    void retrievedAuthorityAloneCannotCreateCurrentRouteConfirmedClaim(String authority) throws Exception {
        var req=request(authority);
        assertThatThrownBy(()->validate(req,"confirmed",List.of(REF))).isInstanceOf(AgentContractException.class)
            .hasMessageContaining("canonical answer evidence");
        assertThatCode(()->validate(req,"assumed",List.of(REF))).doesNotThrowAnyException();
    }
    @Test void canonicalAnswerEvidenceRemainsAllowed() throws Exception {
        var req=request("EXTERNAL_EVIDENCE");
        assertThatCode(()->validate(req,"confirmed",List.of("answer:99999999-9999-9999-9999-999999999999"))).doesNotThrowAnyException();
    }
    @Test void legacyImplicitCurrentAnswerGroundingIsPreserved() throws Exception {
        var req=request("EXTERNAL_EVIDENCE");
        assertThatCode(()->validate(req,"confirmed",List.of())).doesNotThrowAnyException();
    }
}
