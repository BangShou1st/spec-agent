package com.specagent.runtime.execution;

import com.specagent.common.Maps;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 文件名:RuntimeTaskInputStore.java
 *
 * 用途:执行输入的持久化载荷读写(design §6.1)。
 *
 * <p><b>为什么必须持久化。</b>全局助手当前通过 dispatch 的方法参数在内存里传递
 * message 与 UI context。参数只存在于调用栈上:进程崩溃后任务还在,输入却没了,
 * 接管者无法重建同一次执行。因此入队事务必须把执行输入一起落库,再对外应答。
 *
 * <p><b>绝不保存凭证。</b>UI context 只保存页面名与被校验过的项目 id;正文长度由
 * 调用方在写入前约束。本类不做任何校验之外的解释。
 */
@Repository
public class RuntimeTaskInputStore {

    private final NamedParameterJdbcTemplate jdbc;

    public RuntimeTaskInputStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 写入任务的持久化执行输入(必须在入队事务内完成)。 */
    public void write(UUID taskId, String inputJson) {
        jdbc.update("UPDATE runtime_tasks SET input_payload = CAST(:payload AS jsonb) "
                + "WHERE id = :taskId", Maps.of("taskId", taskId, "payload", inputJson));
    }

    /** 读取任务的持久化执行输入;未写入时为空。 */
    public Optional<String> read(UUID taskId) {
        List<String> payloads = jdbc.queryForList(
                "SELECT input_payload::text FROM runtime_tasks WHERE id = :taskId",
                Maps.of("taskId", taskId), String.class);
        return payloads.stream().filter(payload -> payload != null && !payload.isBlank())
                .findFirst();
    }

    /** 读取任务的输入引用与版本(与载荷互补,供核对记录使用)。 */
    public Optional<String> readInputRef(UUID taskId) {
        return jdbc.queryForList("SELECT input_ref FROM runtime_tasks WHERE id = :taskId",
                Maps.of("taskId", taskId), String.class).stream().findFirst();
    }
}
