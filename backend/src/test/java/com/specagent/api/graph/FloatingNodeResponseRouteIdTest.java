package com.specagent.api.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.graph.GraphCommandService;
import com.specagent.node.Node;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Floating node creation: the response's {@code routeId} must be null because
 * a floating draft is route-less graph content. The creation-context route id
 * still lives in the operation log; only the response shape changes.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FloatingNodeResponseRouteIdTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ProjectService projectService;
    @Autowired private GraphCommandService commandService;
    @Autowired private RouteRepository routeRepository;
    @Autowired private ObjectMapper objectMapper;

    private MockMvc mockMvc;
    private Project project;
    private Route route;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        project = projectService.createProject("Floating Response RouteId 测试");
        route = routeRepository.findById(project.activeRouteId()).orElseThrow();
    }

    @Test
    void floatingNodeResponseHasNullRouteId() throws Exception {
        // First, add a root so the route has content; floating drafts are
        // always route-less regardless of any pre-existing content.
        commandService.createRootDraftNode(
                project.id(), route.id(), "NOTE", Map.of("text", "root"));

        mockMvc.perform(post("/api/v1/projects/{pid}/floating-nodes", project.id())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsBytes(
                                Map.of(
                                        "routeId", route.id().toString(),
                                        "subtype", "IDEA",
                                        "content", Map.of("text", "a floating idea")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.routeId").doesNotExist());
    }
}
