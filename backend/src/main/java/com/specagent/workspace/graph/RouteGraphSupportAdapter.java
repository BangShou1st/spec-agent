package com.specagent.workspace.graph;

import com.specagent.workspace.route.RouteGraphSupportPort;
import com.specagent.workspace.route.RouteOperationKind;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Graph-side adapter for {@link RouteGraphSupportPort}. Thin delegation to the
 * existing graph operation journal and provenance validator; the user actor
 * and journal type mapping live here so the route domain does not depend on
 * graph types.
 */
@Service
public class RouteGraphSupportAdapter implements RouteGraphSupportPort {

    private final GraphOperationRepository graphOperationRepository;
    private final GraphInvariantValidator graphInvariantValidator;

    public RouteGraphSupportAdapter(GraphOperationRepository graphOperationRepository,
                                    GraphInvariantValidator graphInvariantValidator) {
        this.graphOperationRepository = graphOperationRepository;
        this.graphInvariantValidator = graphInvariantValidator;
    }

    @Override
    public void validateRouteProvenance(UUID routeId) {
        graphInvariantValidator.validateRouteProvenance(routeId);
    }

    @Override
    public void appendRouteOperation(UUID projectId, RouteOperationKind kind,
                                     List<UUID> relatedIds,
                                     Map<String, Object> before,
                                     Map<String, Object> after) {
        graphOperationRepository.append(projectId, GraphOperation.Actor.USER,
                switch (kind) {
                    case ROUTE_LIFECYCLE -> GraphOperation.Type.ROUTE_LIFECYCLE;
                    case ROUTE_FORK -> GraphOperation.Type.ROUTE_FORK;
                    case ROUTE_START -> GraphOperation.Type.ROUTE_START;
                    case ROUTE_REANSWER -> GraphOperation.Type.ROUTE_REANSWER;
                    case ROUTE_REGENERATE -> GraphOperation.Type.ROUTE_REGENERATE;
                },
                relatedIds, before, after);
    }
}
