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

    /**
     * 旧单例的 validated_revision 只描述旧身份的验证,不描述新连接的
     * (org, provider_id, config_revision)。导入绝不能继承它——否则一条
     * 从未在当前修订号下证明过能力的连接会以"已验证"的样子出现并允许激活。
     */
    @Test void importedLegacyValidationIsNeverInheritedWithoutACurrentCapabilityProof() {
        try(var actor=ActorContextHolder.bind(ActorContext.oidc(user,org))) {
            String encrypted=crypto.encryptScoped("fixture-legacy-validated",org,"openrouter_settings",1);
            jdbc.update("""
                    INSERT INTO openrouter_settings(org_id,singleton_id,api_key,masked_suffix,selected_model,
                        config_revision,validated_revision,validated_at)
                    VALUES (?,1,?,'ated','fixed-free-model',3,3,CURRENT_TIMESTAMP)
                    """,org,encrypted);
            migrator.run(null);
            var row=connections.list().stream().filter(r->"OpenRouter".equals(r.displayName())).findFirst().orElseThrow();
            // 修订号仍然被复制(身份/失效语义不变),但验证状态一律不继承。
            assertThat(row.configRevision()).isEqualTo(3L);
            assertThat(row.validatedRevision()).isNull();
            assertThat(row.validated()).isFalse();
            assertThat(qualifications.status(row)).containsOnly(
                    Map.entry("CHAT","UNTESTED"),Map.entry("STRUCTURED","UNTESTED"),
                    Map.entry("NATIVE_TOOLS","UNTESTED"));
            // 没有当前能力证明就不允许激活。
            assertThatThrownBy(()->connections.activate(row)).isInstanceOf(ModelProviderException.class);
            assertThatThrownBy(()->connections.requireActivatable(row)).isInstanceOf(ModelProviderException.class);
            // 凭据仍然正确解密并重新加密到新作用域,且没有被销毁。
            assertThat(row.apiKey()).isEqualTo("fixture-legacy-validated");
        }
    }

    /** 重新在当前修订号下通过 STRUCTURED 验证后,导入的连接可以正常激活。 */
    @Test void importedConnectionActivatesOnlyAfterAFreshStructuredProof() throws Exception {
        try(var fake=new FakeModelProvider();var actor=ActorContextHolder.bind(ActorContext.oidc(user,org))) {
            String encrypted=crypto.encryptScoped("fixture-legacy-reproof",org,"openrouter_settings",1);
            jdbc.update("""
                    INSERT INTO openrouter_settings(org_id,singleton_id,api_key,masked_suffix,selected_model,
                        config_revision,validated_revision,validated_at)
                    VALUES (?,1,?,'roof','fixed-free-model',2,2,CURRENT_TIMESTAMP)
                    """,org,encrypted);
            migrator.run(null);
            var imported=connections.list().stream().filter(r->"OpenRouter".equals(r.displayName())).findFirst().orElseThrow();
            // 重新指向本地 Fake 提供方,再走一次真实的 validate() 能力探测。
            // 换了来源就必须显式给出新密钥,否则来源守卫会拒绝沿用旧密钥。
            var reproved=connections.update(imported.id(),new ModelProvidersService.Patch(null,null,
                    "CHAT_COMPLETIONS",fake.baseUrl(),"fixture-reproof-key","manual-unlisted","MANUAL"));
            reproved=connections.validate(reproved.id());
            assertThat(reproved.validated()).isTrue();
            assertThat(qualifications.status(reproved)).containsEntry("STRUCTURED","VERIFIED");
            connections.activate(reproved);
            assertThat(targets.activeTarget().orElseThrow().runtimeSettings()).isNotNull();
        }
    }

    /**
     * V109 必须修复"已经跑过 V104-V108 的数据库":那时导入行可能仍带着继承来的
     * validated_revision,而标记表会让 ApplicationRunner 永远不再碰它。
     */
    @Test void capabilityProofMigrationScrubsInheritedValidationButKeepsRealProofs() throws java.io.IOException {
        try(var actor=ActorContextHolder.bind(ActorContext.oidc(user,org))) {
            var inherited=connections.create(new ModelProvidersService.Patch(null,"Inherited proof",
                    "CHAT_COMPLETIONS","https://inherited.example/v1",null,"manual-unlisted","MANUAL"));
            var proven=connections.create(new ModelProvidersService.Patch(null,"Real proof",
                    "CHAT_COMPLETIONS","https://proven.example/v1",null,"manual-unlisted","MANUAL"));
            // 两条行都显示"已验证",但只有一条真的在当前修订号下有 STRUCTURED 证明。
            // legacy_source 在同一组织内唯一,两条行必须用不同的来源标记。
            jdbc.update("UPDATE model_providers SET validated_revision=config_revision,"
                    + "validated_at=CURRENT_TIMESTAMP,legacy_source='OPENROUTER' WHERE id=?",inherited.id());
            jdbc.update("UPDATE model_providers SET validated_revision=config_revision,"
                    + "validated_at=CURRENT_TIMESTAMP,legacy_source='CUSTOM' WHERE id=?",proven.id());
            jdbc.update("""
                    INSERT INTO model_connection_capabilities(org_id,provider_id,config_revision,capability,status)
                    VALUES (?,?,?,'STRUCTURED','VERIFIED')
                    """,org,proven.id(),proven.configRevision());
            jdbc.update(new String(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(
                    "src/main/resources/db/migration/V109__require_current_capability_proof.sql"))));
            assertThat(connections.require(inherited.id()).validated())
                    .as("an inherited flag without a current STRUCTURED proof must be scrubbed")
                    .isFalse();
            assertThat(connections.require(proven.id()).validated())
                    .as("a real current-revision proof must survive the migration")
                    .isTrue();
            // 凭据与配置不受迁移影响。
            assertThat(connections.require(inherited.id()).selectedModel()).isEqualTo("manual-unlisted");
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
            Mockito.when(selected.selection()).thenAnswer(i -> new ModelProviderSettingsService.Selection(selected.activeProvider(),null));
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

    @Test void anthropicConnectionQualifiesChatStructuredAndNativeToolsThroughTheEnvelope() throws Exception {
        try(var fake=new com.specagent.support.FakeAnthropicMessagesProvider();
                var actor=ActorContextHolder.bind(ActorContext.oidc(user,org))) {
            fake.toolShape = com.specagent.support.FakeAnthropicMessagesProvider.ToolShape.SINGLE;
            var record=connections.create(new ModelProvidersService.Patch(null,"Anthropic fixture",
                    "ANTHROPIC_MESSAGES",fake.baseUrl(),"fixture-anthropic-key",
                    "claude-fixture","MANUAL"));
            record=connections.validate(record.id());
            assertThat(record.validated()).isTrue();
            // 三项能力都经真实生产适配器验证:CHAT 文本、STRUCTUREED Envelope 桥接、
            // NATIVE_TOOLS 生产传输双轮(probe_echo,不触及业务工具)。
            var status=qualifications.status(record);
            assertThat(status).containsEntry("CHAT","VERIFIED")
                    .containsEntry("STRUCTURED","VERIFIED")
                    .containsEntry("NATIVE_TOOLS","VERIFIED");
            // Envelope 桥接发出的是官方 output_config.format,而不是伪造的 response_format。
            assertThat(fake.requests).isNotEmpty();
            assertThat(fake.rejections).isEmpty();
            // 激活门禁要求当前修订 STRUCTURED=VERIFIED;Anthropic 现在满足。
            connections.activate(record);
            var snapshot=(RuntimeCustomSettings)targets.activeTarget().orElseThrow().runtimeSettings();
            assertThat(snapshot.providerId()).isEqualTo(record.id());
            assertThat(snapshot.nativeToolsVerified()).isTrue();
            // GA 目标解析:UUID 连接 + NATIVE_TOOLS VERIFIED 升级为原生模式。
            var resolved=(GaExecutionModelTargetResolver.Ready)ga.resolveActive();
            assertThat(resolved.mode()).isEqualTo(GaExecutionMode.NATIVE_TOOLS);
            assertThat(resolved.target().settingsRevision()).isEqualTo(snapshot.bindingRevision());
            // 原生传输对冻结目标可用,身份头是 Anthropic 原生形状。
            new com.specagent.model.provider.AnthropicGaNativeTransport(custom, new ObjectMapper())
                    .complete(resolved.target(), chat(), Duration.ofSeconds(30), () -> true);
            assertThat(fake.apiKeys).containsOnly("fixture-anthropic-key");
            assertThat(fake.versions).containsOnly("2023-06-01");
        }
    }

    /**
     * PR-22 Review P1 回归:同一修订号下复验失败把 STRUCTURED 降级为
     * TEMPORARILY_UNAVAILABLE 后,过期的 validated_revision 不得继续为
     * "新 GA Run / 新 Project Agent Run / 直接运行时读取"开门。已冻结的
     * Run 按设计契约不重复施加能力门禁(GaExecutionModelTargetResolver.
     * requireFrozen 的既定语义),只做绑定一致性校验。
     */
    @Test void structuredDowngradeClosesNewRunAdmissionButKeepsFrozenBindings() throws Exception {
        try(var fake=new FakeModelProvider(); var actor=ActorContextHolder.bind(ActorContext.oidc(user,org))) {
            var a=connections.validate(create(fake,"Downgraded","fixture-downgrade").id());
            assertThat(a.validated()).isTrue();
            assertThat(qualifications.status(a)).containsEntry("STRUCTURED","VERIFIED");
            connections.activate(a);
            var project=new CustomInferenceGateway(custom,protocols,new ObjectMapper());
            var workspace=projects.createProject("Downgrade fixture");
            var boundRun=runs.create(workspace.id(),workspace.activeRouteId(),
                    com.specagent.agent.runtime.AgentRunTriggerType.DECISION_CYCLE,null,null);
            var gateway=new RoutingModelInferenceGateway(active,targets,mock(OpenCodeModelInferenceGateway.class),
                    mock(KiloInferenceGateway.class),project,bindings);
            gateway.complete(new ModelInferenceRequest(boundRun.id(),"DECISION",
                    List.of(new ModelInferenceMessage("user","hi")),64));
            var frozen=((GaExecutionModelTargetResolver.Ready)ga.resolveActive());

            // 配置修订号不变,供应商复验失败:能力降级,历史 validated_revision 保留。
            fake.status=429;
            var downgraded=connections.validate(a.id());
            assertThat(qualifications.status(downgraded)).containsEntry("STRUCTURED","TEMPORARILY_UNAVAILABLE");
            assertThat(downgraded.validated()).isTrue();
            int requests=fake.requests.size();

            // 新 GA Run 准入失败关闭,而不是靠过期 validated 标志放行。
            assertThat(ga.resolveActive())
                    .isInstanceOf(GaExecutionModelTargetResolver.Unavailable.class);
            // 新 Project Agent Run 无法取得运行时配置,也不产生任何供应商请求。
            assertThatThrownBy(()->targets.activeTarget())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("GA_MODEL_BINDING_MISMATCH")
                    .hasMessageNotContaining("fixture-downgrade");
            var fresh=new RoutingModelInferenceGateway(active,targets,mock(OpenCodeModelInferenceGateway.class),
                    mock(KiloInferenceGateway.class),project,bindings);
            var refusedRun=runs.create(workspace.id(),workspace.activeRouteId(),
                    com.specagent.agent.runtime.AgentRunTriggerType.DECISION_CYCLE,null,null);
            assertThatThrownBy(()->fresh.complete(new ModelInferenceRequest(refusedRun.id(),"DECISION",
                    List.of(new ModelInferenceMessage("user","hi")),64)))
                    .hasMessageContaining("MODEL_BINDING_MISMATCH")
                    .hasMessageNotContaining("fixture-downgrade");
            // 直接运行时配置读取同样不得绕过权威能力门禁。
            assertThatThrownBy(()->custom.requireRuntimeSettings()).hasMessage("GA_MODEL_BINDING_MISMATCH");
            assertThatThrownBy(()->custom.requireRuntimeSettings(downgraded.id())).hasMessage("GA_MODEL_BINDING_MISMATCH");
            // 激活门禁与运行时门禁现在得出同一结论。
            assertThatThrownBy(()->connections.requireActivatable(downgraded)).isInstanceOf(ModelProviderException.class);
            assertThat(fake.requests).hasSize(requests);

            // 已冻结 Run 只校验绑定一致性,不重复施加能力门禁,继续解析同一目标。
            var frozenSettings=custom.requireRuntimeSettings(frozen.target().settingsRevision());
            assertThat(frozenSettings.providerId()).isEqualTo(a.id());
            assertThat(ga.requireFrozen("CUSTOM",frozen.target().model(),
                    frozen.target().settingsRevision()).model()).isEqualTo(frozen.target().model());

            // 重新取得当前修订号的 STRUCTURED 证明后,新 Run 恢复合法准入。
            fake.status=200;
            var recoveredRow=connections.validate(a.id());
            assertThat(qualifications.status(recoveredRow)).containsEntry("STRUCTURED","VERIFIED");
            var recovered=((GaExecutionModelTargetResolver.Ready)ga.resolveActive());
            assertThat(recovered.target().model()).isEqualTo("manual-unlisted");
            assertThat(((RuntimeCustomSettings)targets.activeTarget().orElseThrow().runtimeSettings())
                    .providerId()).isEqualTo(a.id());
            var reopenedRun=runs.create(workspace.id(),workspace.activeRouteId(),
                    com.specagent.agent.runtime.AgentRunTriggerType.DECISION_CYCLE,null,null);
            fresh.complete(new ModelInferenceRequest(reopenedRun.id(),"DECISION",
                    List.of(new ModelInferenceMessage("user","hi")),64));
        }
    }

    /**
     * PR-22 Review P1 回归(资格身份不继承):能力证明属于
     * (org_id, provider UUID, config_revision) 三元组。同 URL/模型/修订号的
     * 另一条连接、别的组织、或漂移后的修订号,都不能让降级连接恢复准入。
     */
    @Test void downgradedConnectionInheritsNoProofFromAnotherIdentity() throws Exception {
        try(var fake=new FakeModelProvider(); var actor=ActorContextHolder.bind(ActorContext.oidc(user,org))) {
            var a=create(fake,"Identity A","fixture-identity");
            var b=create(fake,"Identity B","fixture-identity");
            connections.validate(a.id());
            connections.activate(a);
            fake.status=429;
            connections.validate(a.id());
            // B 在自己的 UUID 下拿到当前证明;A 的降级不污染 B,B 的证明也不救 A。
            fake.status=200;
            var verifiedB=connections.validate(b.id());
            assertThat(qualifications.status(verifiedB)).containsEntry("STRUCTURED","VERIFIED");
            assertThatThrownBy(()->custom.requireRuntimeSettings(a.id())).hasMessage("GA_MODEL_BINDING_MISMATCH");
            assertThat(custom.requireRuntimeSettings(b.id()).providerId()).isEqualTo(b.id());
            // 其他组织不能解析本组织的连接,能力行同样按 org 隔离。
            try(var otherOrg=ActorContextHolder.bind(ActorContext.oidc(user,other))) {
                assertThatThrownBy(()->custom.requireRuntimeSettings(a.id())).hasMessage("GA_MODEL_BINDING_MISMATCH");
                assertThatThrownBy(()->custom.requireRuntimeSettings(b.id())).hasMessage("GA_MODEL_BINDING_MISMATCH");
            }
            // 修订号漂移后,旧修订号的冻结引用按绑定不一致失败关闭,新修订号未证明不得准入。
            var drifted=connections.update(a.id(),new ModelProvidersService.Patch(null,null,null,null,null,
                    "another-model",null));
            assertThat(drifted.configRevision()).isEqualTo(2);
            assertThatThrownBy(()->custom.requireRuntimeSettings("custom-provider:"+a.id()+":1"))
                    .hasMessage("GA_MODEL_BINDING_MISMATCH");
            assertThatThrownBy(()->custom.requireRuntimeSettings(a.id())).hasMessage("GA_MODEL_BINDING_MISMATCH");
        }
    }
}
