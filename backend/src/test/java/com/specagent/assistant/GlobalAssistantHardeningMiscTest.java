package com.specagent.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.specagent.capability.CapabilityAdapter;
import com.specagent.capability.CapabilityDescriptor;
import com.specagent.capability.CapabilityInvocation;
import com.specagent.capability.CapabilityResult;
import com.specagent.capability.SideEffectClass;
import com.specagent.assistant.model.GlobalAssistantContext;
import com.specagent.assistant.runtime.GlobalAssistantContextBuilder;
import com.specagent.assistant.conversation.GlobalAssistantConversationService;
import com.specagent.assistant.conversation.GlobalAssistantThread;
import com.specagent.assistant.model.GlobalAssistantPromptRenderer;
import com.specagent.assistant.tool.GlobalAssistantCatalogService;
import com.specagent.assistant.tool.GlobalAssistantToolCatalog;
import com.specagent.assistant.tool.GlobalProjectSummaryQueryService;
import com.specagent.workspace.node.NodeRepository;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.RouteRepository;
import com.specagent.workspace.spec.SpecSnapshotRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * FIX I/N/O/P/Q: failure transparency, catalog starvation, descriptor and
 * observation projection, bounded executor configuration.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class GlobalAssistantHardeningMiscTest {
    @TestConfiguration
    static class ManyUnrelatedCapabilities implements
            org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor {
        @Override
        public void postProcessBeanDefinitionRegistry(
                org.springframework.beans.factory.support.BeanDefinitionRegistry registry) {
            for (int i = 0; i < 40; i++) {
                registry.registerBeanDefinition("flood-capability-" + i,
                        org.springframework.beans.factory.support.BeanDefinitionBuilder
                                .genericBeanDefinition(FloodAdapter.class)
                                .addConstructorArgValue("flood." + i)
                                .getBeanDefinition());
            }
        }
        @Override
        public void postProcessBeanFactory(
                org.springframework.beans.factory.config.ConfigurableListableBeanFactory beanFactory) {
        }
    }
    public static class FloodAdapter implements CapabilityAdapter {
        private final String id;
        public FloodAdapter(String id) {
            this.id = id;
        }
        @Override
        public CapabilityDescriptor descriptor() {
            return new CapabilityDescriptor(id, "1", "unrelated filler capability " + id,
                    Map.of(), Map.of(), true, SideEffectClass.NONE, List.of(), List.of());
        }
        @Override
        public CapabilityResult invoke(CapabilityInvocation invocation) {
            return new CapabilityResult(invocation.invocationId(), invocation.invocationKey(),
                    id, CapabilityResult.Status.SUCCEEDED, Map.of(), List.of(), Map.of(), List.of());
        }
    }
    @Autowired GlobalAssistantCatalogService catalog;
    @Autowired GlobalAssistantContextBuilder contextBuilder;
    @Autowired GlobalAssistantConversationService conversations;
    @Autowired GlobalAssistantPromptRenderer renderer;
    @Autowired ProjectService projects;
    @Autowired Executor gaExecutor;
    @Test
    void summaryRepositoryFailureIsNeverReportedAsNoSpec() {
        ProjectService projectService = Mockito.mock(ProjectService.class);
        RouteRepository routes = Mockito.mock(RouteRepository.class);
        NodeRepository nodes = Mockito.mock(NodeRepository.class);
        SpecSnapshotRepository specs = Mockito.mock(SpecSnapshotRepository.class);
        UUID projectId = UUID.randomUUID();
        Project project = new Project(projectId, "Broken", null, null,
                java.time.Instant.now(), java.time.Instant.now());
        Mockito.when(projectService.getProject(projectId)).thenReturn(java.util.Optional.of(project));
        java.util.UUID routeId = java.util.UUID.randomUUID();
        java.time.Instant now = java.time.Instant.now();
        com.specagent.workspace.route.Route route = new com.specagent.workspace.route.Route(routeId, projectId, null, null,
                com.specagent.workspace.route.RouteLifecycleStatus.OPEN, "label", null, null, null, null, now, now);
        Mockito.when(routes.findByProject(projectId)).thenReturn(List.of(route));
        Mockito.when(nodes.findByProject(projectId)).thenReturn(List.of());
        Mockito.when(specs.findByRoute(routeId)).thenThrow(new IllegalStateException("db down"));
        var service = new GlobalProjectSummaryQueryService(projectService, routes, nodes, specs);
        assertThatThrownBy(() -> service.summarize(projectId)).isInstanceOf(IllegalStateException.class);
    }
    @Autowired com.specagent.capability.CapabilityRegistry registry;
    @Test
    void gaCatalogSurvivesUnrelatedCapabilityFlood() {
        assertThat(registry.descriptorsFor(com.specagent.capability.CapabilityQueryContext.empty()).size())
                .as("flood must exceed the old global truncation window")
                .isGreaterThan(32);
        var ids = catalog.modelCatalog().stream().map(CapabilityDescriptor::capabilityId).toList();
        assertThat(ids).containsExactlyInAnyOrder(
                "project.create", "project.search", "project.list_recent", "project.get_summary",
                "skill.import", "skill.import.discover");
    }
    @Test
    void toolDescriptorsProjectStructuredSchemas() {
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantContext context = contextBuilder.build(thread.id(), "do something",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        String rendered = renderer.render(context, List.of()).get(1).content();
        assertThat(rendered).contains("inputSchema");
        assertThat(rendered).contains("sideEffectClass");
        assertThat(rendered).contains("outputSchema");
        assertThat(rendered).doesNotContain("benchmark");
    }
    @Test
    void observationsRenderAsStableJson() {
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantContext context = contextBuilder.build(thread.id(), "do something",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        Map<String, Object> observation = new java.util.LinkedHashMap<>();
        observation.put("capabilityId", "project.search");
        observation.put("ok", true);
        observation.put("candidates", List.of(Map.of("projectId", "abc", "title", "Demo")));
        String rendered = renderer.render(context, List.of(observation)).get(1).content();
        assertThat(rendered).contains("\"capabilityId\"");
        assertThat(rendered).contains("\"candidates\"");
        assertThat(rendered).doesNotContain("{capabilityId=");
    }
    @Test
    void executorIsBoundedAndManaged() {
        assertThat(gaExecutor).isInstanceOf(ThreadPoolTaskExecutor.class);
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) gaExecutor;
        assertThat(pool.getMaxPoolSize()).isLessThanOrEqualTo(4);
        assertThat(pool.getQueueCapacity()).isLessThanOrEqualTo(50);
    }
    @Autowired com.specagent.assistant.conversation.GlobalAssistantRunRepository runRepository;
    @Autowired com.specagent.assistant.runtime.GlobalAssistantRunLifecycleService runLifecycle;
    @Autowired com.specagent.assistant.runtime.TurnHandoffService handoff;
    @Autowired com.specagent.assistant.conversation.ThreadActivityService activitySvc;
    @Autowired com.specagent.assistant.conversation.ConversationDeleteService deletes;
    @Test
    void schedulingRejectionFailsClosedAndReleasesSlot() {
        java.util.concurrent.Executor rejecting = runnable -> {
            throw new java.util.concurrent.RejectedExecutionException("saturated");
        };
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<com.specagent.assistant.runtime.GlobalAssistantRuntime> provider =
                Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        var dispatcher = new com.specagent.assistant.runtime.RunDispatcher(rejecting, provider, runLifecycle, runRepository);
        var service = new com.specagent.assistant.runtime.GlobalAssistantApplicationService(
                conversations, runRepository, runLifecycle, handoff, activitySvc, deletes, dispatcher);
        GlobalAssistantThread thread = conversations.createThread();
        var first = service.createRun(thread.id(), "hello",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        assertThat(runRepository.findById(first.id()).orElseThrow().status().name())
                .isEqualTo("FAILED");
        var second = service.createRun(thread.id(), "again",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        assertThat(runRepository.findById(second.id()).orElseThrow().status().name())
                .isEqualTo("FAILED");
    }
}
