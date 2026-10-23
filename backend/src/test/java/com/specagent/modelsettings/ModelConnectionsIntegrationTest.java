package com.specagent.modelsettings;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import org.mockito.Mockito;
import org.mockito.ArgumentMatchers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.access.*;
import com.specagent.access.identity.IdentityRepository;
import com.specagent.assistant.runtime.GaExecutionModelTargetResolver;
import com.specagent.assistant.runtime.GaExecutionMode;
import com.specagent.model.contract.*;
import com.specagent.model.provider.*;
import com.specagent.support.FakeModelProvider;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** L5 actual SQL/crypto/qualification/Java gateway, with isolated Fake HTTP providers. */
@SpringBootTest @ActiveProfiles("test") @Transactional
class ModelConnectionsIntegrationTest {
    @Autowired ModelProvidersService connections;
    @Autowired CustomProviderSettingsService custom;
    @Autowired ModelProviderSettingsService active;
    @Autowired ActiveModelTargetService targets;
    @Autowired GaExecutionModelTargetResolver ga;
    @Autowired ChatCompletionsGaTextTransport transport;
    @Autowired ProtocolAdapterRegistry protocols;
    @Autowired ModelConnectionCapabilities qualifications;
    @Autowired IdentityRepository identities;
    @Autowired JdbcTemplate jdbc;
    @Autowired ModelProviderViewService views;
    @Autowired LegacyModelConnectionMigrator migrator;
    @Autowired ModelCredentialCrypto crypto;
    @Autowired com.specagent.workspace.project.ProjectService projects;
    @Autowired com.specagent.agent.runtime.AgentRunService runs;
    @Autowired com.specagent.common.RunModelBindingPort bindings;
    UUID org,user,other,member;
    @BeforeEach void actors() {
        org=UUID.randomUUID();other=UUID.randomUUID();user=UUID.randomUUID();member=UUID.randomUUID();
        identities.insertUserIfAbsent(user,"Model owner");identities.insertUserIfAbsent(member,"Model member");
        identities.insertOrganizationIfAbsent(org,"Model org","STANDARD");
        identities.insertOrganizationIfAbsent(other,"Other org","STANDARD");
        identities.upsertMembership(org,user,OrgRole.OWNER);identities.upsertMembership(other,user,OrgRole.OWNER);
        identities.upsertMembership(org,member,OrgRole.MEMBER);
    }
    @AfterEach void clear(){ActorContextHolder.clear();}
    ModelProviderRecord create(FakeModelProvider fake,String name,String key) {
        return connections.create(new ModelProvidersService.Patch(null,name,"CHAT_COMPLETIONS",fake.baseUrl(),
                key,"manual-unlisted","MANUAL"));
    }
    GaModelContract.Request chat() {
        return new GaModelContract.Request(GaModelContract.VERSION,UUID.randomUUID(),1,UUID.randomUUID(),
                UUID.randomUUID(),"AGENT",UUID.randomUUID(),List.of(new GaModelContract.Message("user","hi",null,null)),
                List.of(),"none",64,false);
    }
    @Test void twoProvidersQualifyAndAgentsUseTheSameUuidWithoutChangingPython() throws Exception {
        try(var first=new FakeModelProvider();var second=new FakeModelProvider();
                var actor=ActorContextHolder.bind(ActorContext.oidc(user,org))) {
            var a=create(first,"A","fixture-a");var b=create(second,"B","fixture-b");
            a=connections.validate(a.id());b=connections.validate(b.id());
            assertThat(a.validated()).isTrue();assertThat(b.validated()).isTrue();
            assertThat(qualifications.status(a)).containsEntry("NATIVE_TOOLS","VERIFIED");
            connections.activate(a);
            var snapshot=(RuntimeCustomSettings)targets.activeTarget().orElseThrow().runtimeSettings();
            assertThat(snapshot.providerId()).isEqualTo(a.id());
            var project=new CustomInferenceGateway(custom,protocols,new ObjectMapper());
            var workspace=projects.createProject("Model binding fixture");
            var run=runs.create(workspace.id(),workspace.activeRouteId(),
                    com.specagent.agent.runtime.AgentRunTriggerType.DECISION_CYCLE,null,null);
            var projectRequest=new ModelInferenceRequest(run.id(),"DECISION",List.of(new ModelInferenceMessage("user","hi")),64);
            var gateway=new RoutingModelInferenceGateway(active,targets,mock(OpenCodeModelInferenceGateway.class),
                    mock(KiloInferenceGateway.class),project,bindings);
            gateway.complete(projectRequest);
            var frozen=((GaExecutionModelTargetResolver.Ready)ga.resolveActive());
            assertThat(frozen.mode()).isEqualTo(GaExecutionMode.NATIVE_TOOLS);
            connections.activate(b);
            // A fresh gateway simulates another instance with an empty local LRU.
            var restarted=new RoutingModelInferenceGateway(active,targets,mock(OpenCodeModelInferenceGateway.class),
                    mock(KiloInferenceGateway.class),project,bindings);
            restarted.complete(projectRequest);
            assertThat(bindings.connectionReference(run.id())).contains(snapshot.bindingRevision());
            var bTarget=((GaExecutionModelTargetResolver.Ready)ga.resolveActive()).target();
            transport.complete(bTarget,chat(),Duration.ofSeconds(30),()->true);
            var old=ga.requireFrozen("CUSTOM",frozen.target().model(),frozen.target().settingsRevision());
            transport.complete(old,chat(),Duration.ofSeconds(30),()->true);
            assertThat(first.keys).containsOnly("Bearer fixture-a");
            assertThat(second.keys).containsOnly("Bearer fixture-b");
            connections.delete(a.id());
            int count=first.requests.size();
            assertThatThrownBy(()->restarted.complete(projectRequest)).hasMessageContaining("MODEL_BINDING_MISMATCH");
            assertThat(first.requests).hasSize(count);
            assertThat(connections.require(b.id()).id()).isEqualTo(b.id());
        }
    }
    @Test void modelRevisionInvalidatesAllQualificationsAndStoredKeysAreEncrypted() throws Exception {
        try(var fake=new FakeModelProvider();var actor=ActorContextHolder.bind(ActorContext.oidc(user,org))) {
            var row=connections.validate(create(fake,"A","fixture-secret").id());
            var cipher=jdbc.queryForObject("SELECT api_key FROM model_providers WHERE id=?",String.class,row.id());
            assertThat(cipher).startsWith("enc:v2:").doesNotContain("fixture-secret");
            var changed=connections.update(row.id(),new ModelProvidersService.Patch(null,null,null,null,null,"new-model",null));
            assertThat(changed.validated()).isFalse();
            assertThat(qualifications.status(changed).values()).containsOnly("UNTESTED");
        }
    }
    @Test void rateLimitsRemainTemporaryAndDoNotGrantQualification() throws Exception {
        try(var fake=new FakeModelProvider();var actor=ActorContextHolder.bind(ActorContext.oidc(user,org))) {
            var row=create(fake,"A","fixture-secret");fake.status=429;row=connections.validate(row.id());
            assertThat(row.validated()).isFalse();
            assertThat(qualifications.status(row).values()).containsOnly("TEMPORARILY_UNAVAILABLE");
            assertThat(fake.requests).hasSize(3);
        }
    }
    @Test void chatAndStructuredSuccessNeverGrantUnverifiedNativeTools() throws Exception {
        try(var fake=new FakeModelProvider();var actor=ActorContextHolder.bind(ActorContext.oidc(user,org))) {
            fake.badCall=true;
            var row=connections.validate(create(fake,"Text only","fixture-secret").id());
            assertThat(row.validated()).isTrue();
            assertThat(qualifications.status(row)).containsEntry("STRUCTURED","VERIFIED")
                    .containsEntry("NATIVE_TOOLS","TEMPORARILY_UNAVAILABLE");
            connections.activate(row);
            var selected=(GaExecutionModelTargetResolver.Ready)ga.resolveActive();
            assertThat(selected.mode()).isEqualTo(GaExecutionMode.TEXT_CHAT);
            var base=chat();
            var withTools=new GaModelContract.Request(base.protocolVersion(),base.runId(),base.executionEpoch(),
                    base.leaseId(),base.callId(),base.callType(),base.modelBindingId(),base.messages(),
                    List.of(new GaModelContract.Tool("probe_echo","echo",Map.of("type","object"))),"auto",64,false);
            int requests=fake.requests.size();
            assertThatThrownBy(()->transport.complete(selected.target(),withTools,Duration.ofSeconds(10),()->true))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThat(fake.requests).hasSize(requests);
        }
    }
    @Test void foreignOrganizationAndMembersCannotEditOrProbeCredentials() throws Exception {
        try(var fake=new FakeModelProvider()) {
            UUID id;
            try(var actor=ActorContextHolder.bind(ActorContext.oidc(user,org))){id=create(fake,"A","fixture-a").id();}
            try(var actor=ActorContextHolder.bind(ActorContext.oidc(user,other))) {
                assertThatThrownBy(()->connections.require(id)).isInstanceOf(RuntimeException.class);
                assertThat(views.listView().providers()).noneMatch(row->id.toString().equals(row.id()));
            }
            try(var actor=ActorContextHolder.bind(ActorContext.oidc(member,org))) {
                assertThatThrownBy(()->connections.discover(null,"CHAT_COMPLETIONS",fake.baseUrl(),"key"))
                        .isInstanceOf(AccessDeniedException.class);
                assertThatThrownBy(()->connections.validate(id)).isInstanceOf(AccessDeniedException.class);
            }
            assertThat(fake.requests).isEmpty();
        }
    }
    @Test void importReencryptsLegacyCredentialOnceAndDeletedConnectionStaysDeleted() {
        try(var actor=ActorContextHolder.bind(ActorContext.oidc(user,org))) {
            String encrypted=crypto.encryptScoped("fixture-legacy",org,"openrouter_settings",1);
            jdbc.update("""
                    INSERT INTO openrouter_settings(org_id,singleton_id,api_key,masked_suffix,selected_model,config_revision)
                    VALUES (?,1,?,'gacy','fixed-free-model',1)
                    """,org,encrypted);
            migrator.run(null);migrator.run(null);
            var row=connections.list().stream().filter(r->"OpenRouter".equals(r.displayName())).findFirst().orElseThrow();
            assertThat(row.preset()).isEqualTo(ModelProvider.CUSTOM);
            assertThat(row.apiKey()).isEqualTo("fixture-legacy");
            assertThat(row.baseUrl()).isEqualTo("https://openrouter.ai/api/v1");
            connections.delete(row.id());migrator.run(null);
            assertThat(connections.list()).isEmpty();
        }
    }
    @Test void onlyTwoBuiltinPresetsAreExposed() {
        try(var actor=ActorContextHolder.bind(ActorContext.oidc(user,org))) {
            assertThat(views.listView().providers()).extracting(ModelProviderViewService.ProviderView::preset)
                    .containsExactly("OPENCODE_ZEN","KILO");
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value=ModelProvider.class,names={"OPENCODE_ZEN","KILO"})
    void builtinRunKeepsItsProviderAcrossSelectionSwitchAndInstanceRestart(ModelProvider provider) {
        try(var actor=ActorContextHolder.bind(ActorContext.oidc(user,org))) {
            var workspace=projects.createProject("Builtin binding "+provider);
            var run=runs.create(workspace.id(),workspace.activeRouteId(),
                    com.specagent.agent.runtime.AgentRunTriggerType.DECISION_CYCLE,null,null);
            var selected=mock(ModelProviderSettingsService.class);
            Mockito.when(selected.activeProvider()).thenReturn(provider);
            var legacyCustom=mock(CustomRuntimeSettingsPort.class);
            var resolver=new ActiveModelTargetService(selected,
                    ()->new RuntimeOpenCodeSettings("fixture-zen","zen-model"),
                    ()->new RuntimeKiloSettings("fixture-kilo","kilo-model"),legacyCustom);
            var zen=mock(OpenCodeModelInferenceGateway.class);var kilo=mock(KiloInferenceGateway.class);
            var customGateway=mock(CustomInferenceGateway.class);
            var first=new RoutingModelInferenceGateway(selected,resolver,zen,kilo,customGateway,bindings);
            var request=new ModelInferenceRequest(run.id(),"DECISION",List.of(new ModelInferenceMessage("user","hi")),64);
            first.complete(request);
            Mockito.when(selected.activeProvider()).thenReturn(ModelProvider.CUSTOM);
            var second=new RoutingModelInferenceGateway(selected,resolver,zen,kilo,customGateway,bindings);
            second.complete(request);
            assertThat(bindings.connectionReference(run.id())).contains("builtin:"+provider.name());
            if(provider==ModelProvider.KILO) {
                Mockito.verify(kilo,Mockito.times(2)).complete(ArgumentMatchers.eq(request),
                        ArgumentMatchers.any(RuntimeKiloSettings.class));
                Mockito.verifyNoInteractions(zen);
            } else {
                Mockito.verify(zen,Mockito.times(2)).complete(ArgumentMatchers.eq(request),
                        ArgumentMatchers.any(RuntimeOpenCodeSettings.class));
                Mockito.verifyNoInteractions(kilo);
            }
            Mockito.verifyNoInteractions(legacyCustom,customGateway);
        }
    }
}
