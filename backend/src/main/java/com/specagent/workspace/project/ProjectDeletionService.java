package com.specagent.workspace.project;

import com.specagent.common.Maps;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文件名:ProjectDeletionService.java
 *
 * 用途:项目删除的事务性负责方。按外键安全的顺序删除持久化的项目
 * 及其名下的所有行。外键关系图(除特别说明外均通过 {@code project_id}
 * 关联):routes、nodes(parent/supersedes 自引用外键)、answers(route、
 * node)、answer_patches(route、node、answers)、agent_runs(route、node、
 * 通过 produced_answer_id 关联 answers、通过 produced_patch_id 关联
 * answer_patches、parent_run_id/root_run_id 自引用外键)、
 * agent_run_events 与 continuation_checks(经 agent_runs)、
 * agent_proposals(逻辑上的 project_id)、context_snapshots(route、node;
 * 级联到 agent_input_projections)、spec_snapshots(route、node、context)、
 * route_inherited_answers(经 routes)、node_relations(经 nodes)、
 * graph_operations、capability_invocations、skill_activations。
 * GA 会话表按应用域隔离、不属于任何项目,因此保持不动。
 *
 * 删除顺序为叶子优先:先删 events/checks,再删 runs(先清空其
 * 自引用外键 parent_run_id/root_run_id——runs 引用 answers、
 * answer_patches、nodes 和 routes,所以必须先于它们删除),然后是
 * proposals、继承答案(引用 answers)、specs、contexts(级联投影)、
 * patches、answers、relations、operations、invocations、activations,
 * 再删 routes(先清自引用外键)、nodes(先清自引用外键),最后删项目行。
 * 任何一步失败都回滚整个事务,不产生孤儿行。存在任何非终态 agent_runs
 * (排队、运行或处理中间态)时会以冲突错误阻止删除,保证活跃的执行
 * 不会被中途拆掉;先锁定项目行再检查,让并发入队与删除在项目行锁上
 * 串行化——入队路径同样先取项目行锁,检查通过后不会再有新任务插入。
 */
@Service
public class ProjectDeletionService {

    /**
     * agent_runs 的全部非终态状态码(与 {@code AgentRunStatus.code()} 的
     * 小写形式一致)。删除保护必须覆盖整个处理链:任务在认领后仍然会经过
     * running/context_built/model_called/reflected/persisted 多个中间状态,
     * 只挡 running 会放过已被旧执行器认领的任务。
     * 本包被架构规则禁止依赖 {@code com.specagent.agent..}(Runtime Kernel
     * 边界),因此这里以字面量维护,由
     * {@code ProjectDeletionStatusGuardTest} 对照真实枚举防止漂移。
     */
    public static final List<String> NON_TERMINAL_RUN_STATUSES = List.of(
            "created", "running", "context_built", "model_called", "reflected", "persisted");

    private final NamedParameterJdbcTemplate jdbc;
    private final ProjectRepository projects;

    public ProjectDeletionService(NamedParameterJdbcTemplate jdbc, ProjectRepository projects) {
        this.jdbc = jdbc;
        this.projects = projects;
    }

    @Transactional
    public void deleteProject(UUID projectId) {
        projects.lockById(projectId);
        Map<String, Object> guardParams = new HashMap<>();
        guardParams.put("projectId", projectId);
        guardParams.put("statuses", NON_TERMINAL_RUN_STATUSES);
        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE project_id = :projectId "
                        + "AND status IN (:statuses)",
                guardParams, Integer.class);
        if (active != null && active > 0) {
            throw new IllegalStateException("Project has non-terminal agent runs");
        }
        Map<String, Object> p = Maps.of("projectId", projectId);
        jdbc.update("DELETE FROM agent_run_events WHERE run_id IN (SELECT id FROM agent_runs WHERE project_id = :projectId)", p);
        jdbc.update("DELETE FROM agent_run_continuation_checks WHERE run_id IN (SELECT id FROM agent_runs WHERE project_id = :projectId)", p);
        // agent_runs 必须先于它引用的行(answers、answer_patches、nodes、
        // routes)删除,且其自引用外键(parent_run_id、root_run_id)要先
        // 清空,否则后续的删除会违反外键约束。
        jdbc.update("UPDATE agent_runs SET parent_run_id = NULL, root_run_id = NULL WHERE project_id = :projectId", p);
        jdbc.update("DELETE FROM agent_runs WHERE project_id = :projectId", p);
        jdbc.update("DELETE FROM agent_proposals WHERE project_id = :projectId", p);
        jdbc.update("DELETE FROM route_inherited_answers WHERE branch_route_id IN (SELECT id FROM routes WHERE project_id = :projectId)", p);
        jdbc.update("DELETE FROM spec_snapshots WHERE project_id = :projectId", p);
        jdbc.update("DELETE FROM context_snapshots WHERE project_id = :projectId", p);
        jdbc.update("DELETE FROM answer_patches WHERE project_id = :projectId", p);
        jdbc.update("DELETE FROM answers WHERE project_id = :projectId", p);
        jdbc.update("DELETE FROM node_relations WHERE project_id = :projectId", p);
        jdbc.update("DELETE FROM graph_operations WHERE project_id = :projectId", p);
        jdbc.update("DELETE FROM capability_invocations WHERE project_id = :projectId", p);
        jdbc.update("DELETE FROM skill_activations WHERE project_id = :projectId", p);
        jdbc.update("UPDATE routes SET supersedes_route_id = NULL, source_route_id = NULL WHERE project_id = :projectId", p);
        jdbc.update("DELETE FROM routes WHERE project_id = :projectId", p);
        jdbc.update("UPDATE nodes SET parent_node_id = NULL, supersedes_node_id = NULL WHERE project_id = :projectId", p);
        jdbc.update("DELETE FROM nodes WHERE project_id = :projectId", p);
        jdbc.update("DELETE FROM retrieval_scope_grants WHERE corpus_id = :projectId", p);
        jdbc.update("DELETE FROM retrieval_index_jobs WHERE corpus_id = :projectId", p);
        jdbc.update("DELETE FROM retrieval_index_heads WHERE corpus_id = :projectId", p);
        jdbc.update("DELETE FROM retrieval_index_generations WHERE corpus_id = :projectId", p);
        int deleted = jdbc.update("DELETE FROM projects WHERE id = :projectId", Maps.of("projectId", projectId));
        if (deleted != 1) {
            throw new IllegalArgumentException("Project not found: " + projectId);
        }
    }
}
