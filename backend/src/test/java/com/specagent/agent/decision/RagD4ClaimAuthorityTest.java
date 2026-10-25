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

    // The current State Update cycle persists the immutable Answer it is processing
    // before the model call. That Answer is not lineage yet, but it is the canonical
    // evidence the runtime re-anchors every CONFIRMED claim to, so a model that cites
    // it (as the prompt requires) must not be rejected.
    static final String CURRENT_ANSWER="answer:55555555-5555-4555-8555-555555555555";
    AgentRequestEnvelope requestWithRetrievedAnswer(String sourceKind,String scope,
                                                    String authority,String originRoute) throws Exception {
        var json=new ObjectMapper();
        ObjectNode body=(ObjectNode)json.readTree(Files.readString(Path.of("../contracts/fixtures/agent-input-valid.json")));
        ObjectNode snapshot=(ObjectNode)body.get("snapshot");
        snapshot.withArray("allowedSourceRefs").add(CURRENT_ANSWER);
        var item=new LinkedHashMap<String,Object>(Map.of("sourceRef",CURRENT_ANSWER,
            "sourceKind",sourceKind,"scope",scope,"authority",authority,
            "content","用户本次回答","provenance",Map.of(),"location",Map.of(),
            "retrievalReason","current-route-memory"));
        if(originRoute!=null) item.put("originRouteId",originRoute);
        snapshot.set("retrievedContext",json.valueToTree(List.of(item)));
        return AgentContracts.read(json.writeValueAsString(body),AgentRequestEnvelope.class);
    }
    static final String CURRENT_ROUTE="66666666-6666-6666-6666-666666666666";
    @Test void currentRouteUserAuthoredAnswerConfirmsClaim() throws Exception {
        var req=requestWithRetrievedAnswer("ANSWER","ROUTE","USER_AUTHORED",CURRENT_ROUTE);
        assertThatCode(()->validate(req,"confirmed",List.of(CURRENT_ANSWER))).doesNotThrowAnyException();
    }
    @Test void currentRouteAnswerFromAnotherRouteStillCannotConfirm() throws Exception {
        var req=requestWithRetrievedAnswer("ANSWER","ROUTE","USER_AUTHORED",
            "88888888-8888-4888-8888-888888888888");
        assertThatThrownBy(()->validate(req,"confirmed",List.of(CURRENT_ANSWER)))
            .isInstanceOf(AgentContractException.class)
            .hasMessageContaining("canonical answer evidence");
    }
    @ParameterizedTest @ValueSource(strings={"ANSWER|PROJECT|USER_AUTHORED","ANSWER|ROUTE|ASSUMED","NODE|ROUTE|USER_AUTHORED"})
    void onlyCurrentRouteUserAuthoredAnswerConfirms(String variant) throws Exception {
        String[] parts=variant.split("\\|");
        var req=requestWithRetrievedAnswer(parts[0],parts[1],parts[2],CURRENT_ROUTE);
        assertThatThrownBy(()->validate(req,"confirmed",List.of(CURRENT_ANSWER)))
            .isInstanceOf(AgentContractException.class)
            .hasMessageContaining("canonical answer evidence");
        assertThatCode(()->validate(req,"assumed",List.of(CURRENT_ANSWER))).doesNotThrowAnyException();
    }
}
