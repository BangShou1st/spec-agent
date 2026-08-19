package com.specagent.route;

import com.specagent.common.Maps;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class RouteInheritedAnswerRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final RowMapper<RouteInheritedAnswer> rowMapper = (rs, rowNum) ->
            new RouteInheritedAnswer(
                    rs.getObject("branch_route_id", UUID.class),
                    rs.getInt("ordinal"),
                    rs.getObject("node_id", UUID.class),
                    rs.getObject("answer_id", UUID.class),
                    rs.getObject("owner_route_id", UUID.class));

    public RouteInheritedAnswerRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void saveAll(List<RouteInheritedAnswer> references) {
        for (RouteInheritedAnswer reference : references) {
            jdbcTemplate.update("""
                    INSERT INTO route_inherited_answers
                        (branch_route_id, ordinal, node_id, answer_id, owner_route_id)
                    VALUES (:branchRouteId, :ordinal, :nodeId, :answerId, :ownerRouteId)
                    """, Maps.of(
                    "branchRouteId", reference.branchRouteId(),
                    "ordinal", reference.ordinal(),
                    "nodeId", reference.nodeId(),
                    "answerId", reference.answerId(),
                    "ownerRouteId", reference.ownerRouteId()));
        }
    }

    public List<RouteInheritedAnswer> findByBranchRouteId(UUID branchRouteId) {
        return jdbcTemplate.query("""
                SELECT branch_route_id, ordinal, node_id, answer_id, owner_route_id
                FROM route_inherited_answers
                WHERE branch_route_id = :branchRouteId
                ORDER BY ordinal
                """, Maps.of("branchRouteId", branchRouteId), rowMapper);
    }
}
