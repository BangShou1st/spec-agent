# 历史数据库事故只读审计方案（未执行，等待独立授权）

当前状态：**NOT RUN / 连接权限未授权**。本文不是执行授权；本轮未打开任何数据库连接。事故报告中的连接和提交属于历史披露，当前数据库影响仍 UNKNOWN。

## 授权范围与只读边界

授权应指定实例/数据库、schema、可见组织范围、审计账户、窗口、允许输出及保存位置。需要已有的仅 SELECT 账户，不使用应用账户或超级用户，不创建账户、不更改权限、不绕过 RLS。先由数据库所有者确认实例身份和已有备份/服务端日志是否存在；不要为取证启动生产应用、Flyway 或旧测试。

以下查询按人工审核后的顺序逐条执行，每条使用独立短事务，避免持有长期快照。先完成 Q01/Q02；schema、列、实际对象类型或身份不符就停在该项，不能猜目标或自行修表。模板采用 psql 的安全变量引用：`:"audit_schema"` 为标识符，`:'incident_project_id'` 等为值；变量只能由获授权人员明确设置，不提供自动发现后执行或 SQL 生成器。

每条 Q 查询包在以下会话控制中（这些控制语句仅改变会话/事务状态，不写业务数据）：

```sql
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '5s';
SET LOCAL lock_timeout = '500ms';
SET LOCAL idle_in_transaction_session_timeout = '30s';
SET LOCAL search_path = pg_catalog;
-- 单条 Q 查询
ROLLBACK;
```

只运行原生 SELECT / WITH SELECT；无 DML/DDL、SELECT INTO、FOR UPDATE/SHARE、序列、自定义函数、扩展、COPY、EXPLAIN ANALYZE 或修复操作。先确认查询目标是预期普通表，且不存在需要额外审查的 RLS 函数/视图。READ ONLY 不等于零资源消耗：SELECT 仍有访问锁、I/O、日志及可能的物理 hint-bit 更新；不承诺物理磁盘无痕。任何超时/权限错误都 ROLLBACK 并停该项，不自动重试或提权。

参数：audit_schema；从历史证据取得的 incident_project_id、incident_run_id、incident_thread_id（缺少就跳过依赖项）；from_wall/to_wall（目标库 timestamp 无时区列的历史壁钟）；from_utc/to_utc（timestamptz 的绝对时刻）。事故报告为 2026-10-20 23:36–23:37 Asia/Shanghai，可请求扩大到 23:34–23:42；对应 UTC 15:34–15:42。**当前 SHOW TimeZone 不证明事故时 JDBC/服务器时区**，必须先核实再填写 wall 参数，不擅自把 UTC 套在无时区列上。

## 查询清单

### Q01：当前连接身份与只读状态

```sql
SELECT current_database() AS database_name, session_user, current_user,
       inet_server_addr()::text AS server_address, inet_server_port() AS server_port,
       current_setting('transaction_read_only') AS read_only,
       current_setting('TimeZone') AS current_timezone;
```

表：无，仅 PostgreSQL 内置函数。预期：一行身份、端点、read_only=on。风险：代理端点不是密码学实例身份；不得把此结果当事故会话身份。错误目标立即断开，不继续其他查询。

### Q02：schema、列、对象类型与 RLS 配置

```sql
SELECT c.table_name, c.column_name, c.data_type, c.is_nullable,
       p.relkind, p.relrowsecurity, p.relforcerowsecurity
FROM information_schema.columns c
JOIN pg_catalog.pg_namespace n ON n.nspname = c.table_schema
JOIN pg_catalog.pg_class p ON p.relnamespace = n.oid AND p.relname = c.table_name
WHERE c.table_schema = :'audit_schema'
  AND c.table_name IN ('flyway_schema_history','projects','project_members','agent_runs',
      'agent_run_events','model_providers','opencode_settings','openrouter_settings','kilo_settings',
      'custom_provider_settings','search_settings','embedding_service_settings','embedding_service_revisions',
      'connections','connection_credentials','authorization_scopes','global_assistant_runs',
      'global_assistant_run_events','global_assistant_pending_turns','runtime_tasks','executor_ownership',
      'routes','nodes','answers','answer_patches','context_snapshots','agent_proposals',
      'capability_invocations','graph_operations','security_audit_events','node_relations',
      'spec_snapshots','route_inherited_answers','agent_input_projections','agent_run_continuation_checks')
ORDER BY c.table_name, c.ordinal_position;
```

表：information_schema/pg_catalog。预期：元数据清单，用于确认后续列是否存在；若有 RLS，先记录账户可见范围及审核其策略，不绕过。风险：不同部署 schema、版本或权限会缺列/不可见；不把不可见解释为空表。

### Q03：Flyway 版本与事故窗口（不运行 Flyway）

```sql
SELECT installed_rank, version, description, type, script, checksum, installed_on, success
FROM :"audit_schema".flyway_schema_history
WHERE installed_on >= :'from_wall'::timestamp AND installed_on < :'to_wall'::timestamp
   OR version IN ('97','98','99')
ORDER BY installed_rank;
```

表：flyway_schema_history。预期：V98/V99 是否已执行、记录时间和校验值，供已有备份/部署日志比对。风险：历史记录可被修复/基线操作改变，当前成功记录不证明事故期间没有其他 DML；不运行 repair/validate/migrate。checksum 不等同本地 SHA256，不能直接数值比较。

### Q04：外键、触发器与删除传播元数据

```sql
SELECT r.relname AS table_name, con.conname, con.contype,
       con.confrelid::regclass::text AS referenced_table, con.confdeltype,
       con.convalidated
FROM pg_catalog.pg_constraint con
JOIN pg_catalog.pg_class r ON r.oid = con.conrelid
JOIN pg_catalog.pg_namespace n ON n.oid = r.relnamespace
WHERE n.nspname = :'audit_schema' AND con.contype IN ('f','c')
ORDER BY r.relname, con.conname;
```

表：pg_constraint/pg_class/pg_namespace。预期：FK 删除级联及约束状态元数据，不执行约束表达式。风险：元数据只描述当前结构，不能恢复已删除行；全 schema 清单可暴露结构，产物限制在授权人员范围。

### Q05：非系统触发器清单（不执行触发器）

```sql
SELECT r.relname AS table_name, t.tgname, t.tgenabled,
       pn.nspname AS function_schema, p.proname AS function_name
FROM pg_catalog.pg_trigger t
JOIN pg_catalog.pg_class r ON r.oid = t.tgrelid
JOIN pg_catalog.pg_namespace n ON n.oid = r.relnamespace
JOIN pg_catalog.pg_proc p ON p.oid = t.tgfoid
JOIN pg_catalog.pg_namespace pn ON pn.oid = p.pronamespace
WHERE n.nspname = :'audit_schema' AND NOT t.tgisinternal
ORDER BY r.relname, t.tgname;
```

表：pg_trigger/pg_class/pg_proc/pg_namespace。预期：验证 connections 版本触发器等影响边界。风险：触发器可能已变更，存在不等于事故执行过；不导出用户函数正文。

### Q06：定位事故项目（仅已知精确名称）

```sql
SELECT id, created_at, updated_at
FROM :"audit_schema".projects
WHERE title = 'eval-P3B-C03-NL-base-p3b-c03-nl-title-33501'
ORDER BY created_at LIMIT 20;
```

表：projects。预期：残留项目 UUID/时间，不输出正文。风险：名称可能复用；零行也可能表示已删除，不能证明从未创建。无 UUID 时回查已存在的日志/备份，不能扩大为读取所有项目正文。

### Q07：已知项目关联残留计数

```sql
SELECT 'projects' AS table_name, count(*) AS visible_rows FROM :"audit_schema".projects WHERE id=:'incident_project_id'::uuid
UNION ALL SELECT 'project_members',count(*) FROM :"audit_schema".project_members WHERE project_id=:'incident_project_id'::uuid
UNION ALL SELECT 'routes',count(*) FROM :"audit_schema".routes WHERE project_id=:'incident_project_id'::uuid
UNION ALL SELECT 'nodes',count(*) FROM :"audit_schema".nodes WHERE project_id=:'incident_project_id'::uuid
UNION ALL SELECT 'answers',count(*) FROM :"audit_schema".answers WHERE project_id=:'incident_project_id'::uuid
UNION ALL SELECT 'answer_patches',count(*) FROM :"audit_schema".answer_patches WHERE project_id=:'incident_project_id'::uuid
UNION ALL SELECT 'agent_runs',count(*) FROM :"audit_schema".agent_runs WHERE project_id=:'incident_project_id'::uuid
UNION ALL SELECT 'context_snapshots',count(*) FROM :"audit_schema".context_snapshots WHERE project_id=:'incident_project_id'::uuid
UNION ALL SELECT 'agent_proposals',count(*) FROM :"audit_schema".agent_proposals WHERE project_id=:'incident_project_id'::uuid
UNION ALL SELECT 'capability_invocations',count(*) FROM :"audit_schema".capability_invocations WHERE project_id=:'incident_project_id'::uuid
UNION ALL SELECT 'graph_operations',count(*) FROM :"audit_schema".graph_operations WHERE project_id=:'incident_project_id'::uuid
UNION ALL SELECT 'node_relations',count(*) FROM :"audit_schema".node_relations WHERE project_id=:'incident_project_id'::uuid
UNION ALL SELECT 'spec_snapshots',count(*) FROM :"audit_schema".spec_snapshots WHERE project_id=:'incident_project_id'::uuid
UNION ALL SELECT 'agent_run_events',count(*) FROM :"audit_schema".agent_run_events WHERE run_id IN (SELECT id FROM :"audit_schema".agent_runs WHERE project_id=:'incident_project_id'::uuid)
UNION ALL SELECT 'agent_run_continuation_checks',count(*) FROM :"audit_schema".agent_run_continuation_checks WHERE run_id IN (SELECT id FROM :"audit_schema".agent_runs WHERE project_id=:'incident_project_id'::uuid)
UNION ALL SELECT 'agent_input_projections',count(*) FROM :"audit_schema".agent_input_projections WHERE snapshot_id IN (SELECT id FROM :"audit_schema".context_snapshots WHERE project_id=:'incident_project_id'::uuid)
UNION ALL SELECT 'route_inherited_answers',count(*) FROM :"audit_schema".route_inherited_answers WHERE branch_route_id IN (SELECT id FROM :"audit_schema".routes WHERE project_id=:'incident_project_id'::uuid) OR answer_id IN (SELECT id FROM :"audit_schema".answers WHERE project_id=:'incident_project_id'::uuid);
```

表：列出的项目关联表，先按 Q02 确认其列；UUID 必须来源于已有证据。预期：逐表可见残留数。风险：索引/权限/RLS 影响耗时与完整性；依赖父行定位的计数在父行已删除后不能找到历史关联，零残留不等于历史零写入。没有目标 UUID 时该查询 NOT RUN。

### Q08：已知 run 的事件类型与时间，不读 payload/trace

```sql
SELECT event_type, count(*) AS visible_rows, min(created_at), max(created_at)
FROM :"audit_schema".agent_run_events
WHERE run_id=:'incident_run_id'::uuid
GROUP BY event_type ORDER BY event_type;
```

表：agent_run_events。预期：是否仍有事件及大致顺序；若已清理则可能为空。风险：不包含服务器 XID/已删除记录，不能重建逐条提交或回滚。

### Q09：当前 V99 兼容性分布（需 V98 列存在）

```sql
SELECT status,
       CASE WHEN model_binding_fingerprint IS NULL THEN 'NULL'
            WHEN btrim(model_binding_fingerprint)='legacy-unbound' THEN 'LEGACY_SENTINEL'
            WHEN btrim(model_binding_fingerprint) ~ '^[0-9a-f]{64}$' THEN 'SHA256_SHAPED'
            ELSE 'OTHER' END AS fingerprint_class,
       count(*) AS visible_rows
FROM :"audit_schema".agent_runs
GROUP BY status, fingerprint_class ORDER BY status, fingerprint_class;
```

表：agent_runs。预期：created、活动、终态与指纹类别的现状分布，不输出实际指纹。风险：全表聚合可能超时；SHA256_SHAPED 不证明绑定真实，当前哨兵不能判断是何时/哪次部署写入，也无法恢复被覆盖值。不得据此自动重绑或改哨兵。

### Q10：凭据格式聚合（不输出密文、尾缀或 keyId）

```sql
SELECT 'model_providers' AS table_name,
       count(*) FILTER (WHERE api_key IS NULL OR api_key='') AS empty_rows,
       count(*) FILTER (WHERE api_key LIKE 'enc:v1:%') AS v1_rows,
       count(*) FILTER (WHERE api_key LIKE 'enc:v2:%') AS v2_rows,
       count(*) FILTER (WHERE api_key IS NOT NULL AND api_key<>'' AND api_key NOT LIKE 'enc:v1:%' AND api_key NOT LIKE 'enc:v2:%') AS other_rows
FROM :"audit_schema".model_providers;
```

表：model_providers；经 Q02 确认后可按完全相同的聚合形状分别审核 opencode_settings、openrouter_settings、kilo_settings、custom_provider_settings、search_settings、embedding_service_settings、embedding_service_revisions（每次一表、一短事务，不动态拼接执行）。预期：格式行数，与已有脱敏备份相比较。风险：即使聚合也需要列读取权限；不授权导出原文。没有事故前基线，就不能从当前格式推断事故重加密了多少行。时间列不是每次 api_key 更新都会变化，不作为充分证据。

### Q11：connection 版本/凭据元数据窗口

```sql
SELECT 'connections' AS table_name, count(*) AS visible_rows,
       min(updated_at) AS earliest, max(updated_at) AS latest,
       min(configuration_version) AS min_version, max(configuration_version) AS max_version
FROM :"audit_schema".connections
WHERE updated_at>=:'from_wall'::timestamp AND updated_at<:'to_wall'::timestamp
UNION ALL
SELECT 'connection_credentials',count(*),min(updated_at),max(updated_at),NULL::bigint,NULL::bigint
FROM :"audit_schema".connection_credentials
WHERE updated_at>=:'from_wall'::timestamp AND updated_at<:'to_wall'::timestamp;
```

表：connections/connection_credentials。预期：事故窗口内变更元数据，不读 config/secret/ref/掩码。风险：版本是当前值，不是事件历史；时区与其他并发写入需关联服务端日志后才能归因。

### Q12：授权范围变化聚合

```sql
SELECT split_part(scope_key,':',2) AS scope_family, count(*) AS visible_rows,
       min(version), max(version), min(updated_at), max(updated_at)
FROM :"audit_schema".authorization_scopes
WHERE updated_at>=:'from_utc'::timestamptz AND updated_at<:'to_utc'::timestamptz
GROUP BY scope_family ORDER BY scope_family;
```

表：authorization_scopes。预期：org/project 等族的窗口内版本元数据。风险：ensure 可以只插入初始 version=0；现值无法区分事故与并发授权操作，输出不含 scope_key 中的身份。

### Q13：全局助手被恢复/终态化的时间窗口

```sql
SELECT status,error_code,count(*) AS visible_rows,min(started_at),max(completed_at)
FROM :"audit_schema".global_assistant_runs
WHERE (started_at>=:'from_wall'::timestamp AND started_at<:'to_wall'::timestamp)
   OR (completed_at>=:'from_wall'::timestamp AND completed_at<:'to_wall'::timestamp)
GROUP BY status,error_code ORDER BY status,error_code;
```

表：global_assistant_runs。预期：窗口内失败/取消/运行分布及机器码。风险：恢复可能处理窗口前的旧 run，故同时看完成时间；其他实例的正常失败也会出现，不能直接归因于本次 C03。

### Q14：待交接 turn 的状态变化

```sql
SELECT status,count(*) AS visible_rows,min(created_at),max(claimed_at),max(consumed_at),max(discarded_at)
FROM :"audit_schema".global_assistant_pending_turns
WHERE (created_at>=:'from_wall'::timestamp AND created_at<:'to_wall'::timestamp)
   OR (claimed_at>=:'from_wall'::timestamp AND claimed_at<:'to_wall'::timestamp)
   OR (consumed_at>=:'from_wall'::timestamp AND consumed_at<:'to_wall'::timestamp)
   OR (discarded_at>=:'from_wall'::timestamp AND discarded_at<:'to_wall'::timestamp)
GROUP BY status ORDER BY status;
```

表：global_assistant_pending_turns。预期：可能恢复交接的数量；绝不输出 message/ui_context。风险：一行多个状态变化未形成完整历史；归因仍需原始日志与已知线程 UUID。

### Q15：安全审计事件与调度元数据

```sql
SELECT action,resource_type,outcome,reason_code,count(*) AS visible_rows,min(created_at),max(created_at)
FROM :"audit_schema".security_audit_events
WHERE created_at>=:'from_utc'::timestamptz AND created_at<:'to_utc'::timestamptz
GROUP BY action,resource_type,outcome,reason_code ORDER BY resource_type,action;
```

表：security_audit_events。预期：授权/拒绝等聚合机器码。风险：安全事件不是全量 SQL 审计，没有事件不能证明未执行写入。资源 ID/身份不在默认输出。

### Q16：执行器与任务级运行现状

```sql
SELECT id,epoch,updated_at FROM :"audit_schema".executor_ownership;
```

表：executor_ownership，预期一行代次和更新时间。风险：当前 epoch 无法还原历史代次，不能据此认定事故启动过 worker。

### Q17：事故窗口内任务状态聚合

```sql
SELECT family,state,last_error_code,count(*) AS visible_rows,min(created_at),max(heartbeat_at)
FROM :"audit_schema".runtime_tasks
WHERE (created_at>=:'from_utc'::timestamptz AND created_at<:'to_utc'::timestamptz)
   OR (heartbeat_at>=:'from_utc'::timestamptz AND heartbeat_at<:'to_utc'::timestamptz)
GROUP BY family,state,last_error_code ORDER BY family,state;
```

表：runtime_tasks。预期：任务族和状态元数据，不读 input_ref、recovery_reason 或业务内容。风险：事故清理可能级联删除任务；快照与其他实例行为混合，不能证明恰好一次执行。

## 非 SQL 证据与判定规则

请数据库所有者提供**已经存在**的 23:34–23:42 服务端连接/事务/语句日志和事故前备份的脱敏导出；不在原库安装审计扩展、不启用新日志、不读 WAL 文件或恢复备份。本轮方案不授权 pg_read_file、pg_waldump、逻辑复制槽、PITR、COPY TO 或创建任何对象。若后续需要这些能力，另作授权和方案。

必须把 session/backend PID、用户、目标库、应用进程和时间关联后，才能将某条写入归因事故。项目数据清理后现库零行不证明没有提交；事件、凭据、全局助手及授权范围可能受影响而不属于 C03 项目清理范围。READ ONLY 当前快照不能恢复已覆盖凭据或已删除行；缺少原始日志/基线时，部分历史影响可能永久无法精确确认。调查结论可以是范围收窄或仍 UNKNOWN，不以测试通过宣布事故闭环。

下一授权请求应只覆盖此只读方案的明确目标和输出；迁移验收须另行授权新隔离库，数据库修复/回滚与管理员建库删库均不包含在内。特别不能直接运行 MigrationV99ModelBindingBackfillTest：它会连接 postgres 管理库、CREATE DATABASE，最后 DROP ... WITH (FORCE)。
