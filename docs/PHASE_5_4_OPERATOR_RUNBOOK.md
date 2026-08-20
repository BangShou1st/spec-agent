# Phase 5.4 Operator Runbook — OpenCode Live Runtime

给开发者 / operator 的实用操作手册：怎么准备本地 secret、怎么跑标准回归、
怎么跑真实 live smoke、怎么确认 secret 不会提交、怎么清理 dev DB 残留、
怎么诊断 provider 故障、怎么读 AgentRun trace。

> 这不是产品文案。所有命令都在本地开发/测试环境运行。
> 所有 SQL 清理语句都带环境警告，不要在生产环境执行。

---

## 1. 需要哪些环境变量

| 变量 | 作用 | 默认回归需要 | live smoke 需要 |
| --- | --- | --- | --- |
| `SPEC_AGENT_MODEL_GATEWAY` | gateway 选择器：`fake`（默认）或 `opencode` | 否（默认 fake） | **必须 `opencode`** |
| `SPEC_AGENT_OPENCODE_KEY` | OpenCode Zen API key | 否 | **必须** |
| `SPEC_AGENT_OPENCODE_MODEL` | 选中的模型 id（必须是当前 free model，id 以 `-free` 结尾） | 否 | 否（默认 `mimo-v2.5-free`） |
| `SPEC_AGENT_CREDENTIAL_MASTER_KEY` | 凭据加密主密钥（本地/生产 profile 需要；**test profile 使用固定的 test-only key**，见 `backend/src/test/resources/application-test.yml`） | 否 | 否（test profile） |

其他可选：

| 变量 | 作用 |
| --- | --- |
| `SPEC_AGENT_OPENCODE_SETTINGS_TIMEOUT_SECONDS` | 仅模型目录/credential probe 的 bounded timeout 秒数，默认 45；production completion 不设置 Spec Agent timeout |
| `SPEC_AGENT_DB_HOST` / `SPEC_AGENT_DB_PORT` / `SPEC_AGENT_DB_NAME` / `SPEC_AGENT_DB_USER` / `SPEC_AGENT_DB_PASSWORD` | dev/test 数据库连接，默认 `localhost:5434/spec_agent`（docker-compose） |

## 2. 准备 .local-secrets.env

文件位于 `backend/.local-secrets.env`，已被 `.gitignore` 忽略（`backend/.local-secrets.env`）。
格式为 shell 可 source 的 KEY=VALUE 行：

```bash
SPEC_AGENT_MODEL_GATEWAY=opencode
SPEC_AGENT_OPENCODE_KEY=sk-your-key-here
SPEC_AGENT_OPENCODE_MODEL=mimo-v2.5-free
# 仅非 test profile 需要；test 回归不需要
SPEC_AGENT_CREDENTIAL_MASTER_KEY=your-local-master-key
```

不要提交这个文件。提交前确认：

```bash
git status --short            # 不应出现 .local-secrets.env
git check-ignore backend/.local-secrets.env   # 应输出该路径
```

## 3. 标准回归（默认零公网）

```powershell
cd E:\spec-agent\backend
cmd //c "gradlew.bat test"
```

说明：

- 默认 gateway 是 `fake`，回归测试对公网 **0 请求**。
- live smoke 是 env-gated（`@EnabledIfEnvironmentVariable` + `LiveSmokeEnvironment` 预检）：
  未设置 `SPEC_AGENT_OPENCODE_KEY` 时自动 skip。注意：**如果 shell 已 source 过
  `.local-secrets.env`，live smoke 会真的跑**。跑标准回归前不要 source secrets，
  或者用一个干净 shell。

检查结果：

- `BUILD SUCCESSFUL`、0 failures；skipped 数量 = env-gated live smoke 方法数
  （当前 3 个：`OpenCodeZenLiveSmokeTest` 2 个 + `OpenCodeZenRealFullLoopSmokeTest` 1 个）。

## 4. Live smoke（真实 OpenCode，公网请求）

### Git Bash / Linux

```bash
cd /e/spec-agent/backend
set -a
source .local-secrets.env
set +a
./gradlew.bat test --no-daemon --tests com.specagent.model.gateway.OpenCodeZenLiveSmokeTest
./gradlew.bat test --no-daemon --tests com.specagent.model.gateway.OpenCodeZenRealFullLoopSmokeTest
```

### PowerShell

```powershell
cd E:\spec-agent\backend
Get-Content .local-secrets.env | ForEach-Object {
  if ($_ -match '^\s*([A-Z_]+)=(.*)$') { Set-Item -Path "Env:$($matches[1])" -Value $matches[2] }
}
.\gradlew.bat test --no-daemon --tests com.specagent.model.gateway.OpenCodeZenLiveSmokeTest
.\gradlew.bat test --no-daemon --tests com.specagent.model.gateway.OpenCodeZenRealFullLoopSmokeTest
```

### 预检输出（每轮 live smoke 都打印）

```
=== live smoke environment ===
gateway selector: opencode
selected model: mimo-v2.5-free
key masked: ••••abcd
```

缺环境变量时输出 BLOCKED 并 skip（**不会 PASS**）：

```
BLOCKED: missing SPEC_AGENT_OPENCODE_KEY
BLOCKED: SPEC_AGENT_MODEL_GATEWAY must be opencode (found: unset)
BLOCKED: selected model must end with -free (found: some-paid-model)
```

### Live smoke 保证

- 运行前不依赖 dev DB 是否已有 credential（自己 seed）。
- 运行中不打印完整 key，只打印 masked suffix。
- 运行后 `@Transactional` rollback：不残留 `provider_credentials`、project、
  node、answer、patch、spec 行。
- 例外：若 live smoke 中途失败，`AgentRunFailureService`（`REQUIRES_NEW`）写入的
  FAILED `agent_runs` 行是设计行为（失败必须可查询），不会回滚。清理方法见第 6 节。

## 5. 确认 secret 不会进入代码 / 日志 / trace

```bash
git diff --stat
git diff | grep -iE "sk-|Bearer|master-key=|encrypted_secret" || echo "no secret patterns in diff"
git status --short          # 不应出现 .local-secrets.env
```

- 代码内只允许出现测试用的假 key（如 `sk-test-key`、`sk-integration-secret-ab12`）。
- `OpenCodeModelException` 的 message 与 trace 不包含 API key / Authorization 头。
- `AgentRun.trace` 只包含步骤标记与 `failed:provider:<category>`，不包含完整 prompt / model output / 用户回答。

## 6. 清理 dev/test DB 的 credential 残留

> **ONLY FOR LOCAL DEVELOPMENT/TEST DATABASE.**
> Before cleanup, confirm you are connected to the local dev/test DB
> (docker-compose 的 PostgreSQL，默认端口 5434，容器名 spec-agent-postgres).
> **Never run this against production.**

查看残留（只显示 masked suffix，不显示密文）：

```sql
-- LOCAL DEV/TEST ONLY. Do not run against production.
select provider, masked_suffix, created_at from provider_credentials;
```

清理：

```sql
-- LOCAL DEV/TEST ONLY.
delete from provider_credentials where provider = 'opencode';
```

或通过 docker 直接执行：

```powershell
docker exec spec-agent-postgres psql -U spec_agent -d spec_agent -c "select provider, masked_suffix, created_at from provider_credentials;"
docker exec spec-agent-postgres psql -U spec_agent -d spec_agent -c "delete from provider_credentials where provider = 'opencode';"
```

可选：清理测试跑出来的残留 run / project（仅在需要时，同样只针对 dev/test）：

```sql
-- LOCAL DEV/TEST ONLY.
delete from agent_runs where project_id in (select id from projects where name like 'opencode wiring%' or name like '%smoke%');
```

> 注意：标准回归会自行隔离 credential 状态（相关测试在 `@BeforeEach` 删除
> opencode credential），所以残留不会导致默认 `gradlew test` 失败；第 6 节只是
> 保持 dev DB 干净的可选操作。

## 7. 诊断 provider 故障

所有 OpenCode 故障都映射为 `OpenCodeModelException`，带 `OpenCodeModelErrorCategory`：

| 类别 | 触发条件 | 含义 |
| --- | --- | --- |
| `AUTHENTICATION` | HTTP 401 / 403 | key 无效 / 无权限 |
| `RATE_LIMITED` | HTTP 429 | 限流，稍后重试 |
| `SERVER_ERROR` | HTTP 5xx | provider 临时不可用（outage） |
| `PROVIDER_REQUEST_ERROR` | 其他 4xx | 请求本身被拒 |
| `TIMEOUT` | 请求超时 | 网络或 provider 慢 |
| `CONNECTION` | 连接失败 / 中断 | 网络问题 |
| `INVALID_RESPONSE` | 响应不是合法 JSON / 缺 action / 缺 output | provider 或模型行为异常 |
| `EMPTY_CONTENT` | 成功但内容为空 | 模型返回空 |
| `INVALID_MODEL` | 选中模型不是 `-free` | 配置错误 |
| `NOT_CONFIGURED` | 缺 credential / 缺 model 配置 | 配置错误 |

定位步骤：

1. 看 run 的 trace（见第 8 节）：`failed:provider:<category>` 直接给出类别。
2. 对照上表判断是 provider 问题还是配置问题。
3. provider outage / rate limit 属于外部状态：**不自动重试、不 fallback、不 repair**
   （Phase 5.4 明确不实现）；记录类别与频率，等待恢复后重跑 live smoke。

## 8. 阅读 AgentRun trace

`agent_runs.trace` 是换行分隔的步骤标记，例如一次成功 answer 循环：

```text
created
context_built
model_called:INTERPRET_ANSWER
model_called:DRAFT_ANSWER_PATCH
model_called:DRAFT_NODE
reflected:PATCH
reflected:NODE
persisted_answer
persisted_patch
persisted_node
completed
```

失败时以 `failed:...` 结尾：

```text
created
context_built
model_called:DRAFT_NODE
failed:provider:RATE_LIMITED
```

定位要点：

- `model_called:<TASK>`：哪个 task 调用了模型（`DRAFT_NODE` / `INTERPRET_ANSWER` /
  `DRAFT_ANSWER_PATCH` / `DRAFT_SPEC`）。
- `reflected:<GATE>`：哪个 reflection gate 跑过（`NODE` / `PATCH` / `SPEC_GROUNDING` /
  `SOURCE_REFERENCES`）。
- `persisted_<artifact>`：哪些产物已落库（`node` / `answer` / `patch` / `spec_snapshot`）。
- `failed:provider:<CATEGORY>`：provider 故障类别（不带 message，不带 key）。
- `failed:<ClassName>`：其他运行时异常（如 `ModelContractException`）。
- 关联 id 在同一行记录里：`project_id`、`route_id`、`context_snapshot_id`、
  `produced_*_id` 都是 uuid，可跨表定位。

## 9. 常见失败及处理

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 回归中 live smoke 显示 skipped | 没设 `SPEC_AGENT_OPENCODE_KEY` | 预期行为；显式跑 live smoke 才需要 |
| live smoke BLOCKED: gateway must be opencode | 没 source secrets 或 gateway=fake | source `.local-secrets.env` 后重跑 |
| live smoke BLOCKED: model must end with -free | `SPEC_AGENT_OPENCODE_MODEL` 不是 free 模型 | 用 `GET /models` 的当前 free 列表，选 `-free` id |
| `failed:provider:AUTHENTICATION` | key 失效 | 换 key |
| `failed:provider:RATE_LIMITED` | 限流 | 等待后重跑；观察频率 |
| `failed:provider:SERVER_ERROR` | provider outage | 等待恢复；不要在生产代码加 retry |
| `failed:provider:INVALID_RESPONSE` | 模型输出不合法 | 检查 prompt 版本与模型行为；失败是 fail-closed 设计 |
| `failed:provider:NOT_CONFIGURED` | 没 credential / 没 model | 检查 seed 与 env |
| `OpenCodeCredentialIntegrationTest` 等"无凭据"测试失败 | dev DB 有旧残留（旧 master key 加密） | 第 6 节清理后重跑（新版本测试自带隔离，通常不需要） |

## 10. 不要做的事

- 不要提交 `.local-secrets.env` 或任何含真实 key 的文件。
- 不要在生产环境执行第 6 节的 SQL。
- 不要因为 provider 不稳定就实现 retry / fallback / repair（Phase 5.4 范围外）。
- 不要把完整 prompt / model output / 用户回答写进 trace 或日志。
