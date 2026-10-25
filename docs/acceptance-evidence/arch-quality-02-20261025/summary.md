# ARCH-QUALITY-02 · 交付总结

status: Historical(本轮快照,2026-10-25)
authority: Historical Reference Only

## 结论

**ARCH_QUALITY_02_PASS**(本地全量验证通过;远端 CI 10/10 SUCCESS)

三个优化簇全部按计划实施:Python 三引擎结构化错误诊断统一、WAIT/dependencyRef 判定收敛为单一原语、协议版本与 /health 能力公告单一事实来源化。Java 生产代码零改动,异常类名、HTTP 状态码、机器码词汇表与 wire 格式全部不变。

## 各簇实际修改与行为变化

### 簇 A · 三引擎结构化错误诊断

- 新增 `contracts/diagnostics.py`:`ContractDiagnostic` 契约(从 decision 引擎原样搬移,Java `ModelContractDiagnostic` 镜像)+ `contract_diagnostic` 构造器 + 共享安全提取原语(`safe_field_path` 白名单、`actual_type`/`expected_type`)。
- `decision/engine.py` 改为引用共享契约,删除 5 个本地重复定义,行为零变化。
- `state_update/engine.py`、`artifact/engine.py`:全部可确定成因的失败路径(JSON 解析、schema 校验、越界 ref、空 sourceRefs)补齐结构化诊断;**异常消息不再嵌入 pydantic 原文、section 标题与 ref 值**(改前为潜在泄漏点),`from None` 切断异常链。
- `app.py` 零修改:既有 `_fail` 透传天然支持。
- 传输失败(ModelClientError/BrokerTimeoutError)保留 `diagnosticStatus=UNKNOWN`——契约诊断形状对传输失败是伪结构,不为消灭 UNKNOWN 伪造诊断。
- 复用前后:诊断契约 1 份(原 decision 独有,三引擎割裂);失败路径诊断覆盖 decision 5/5、state_update 0/2→2/2、artifact 0/4→4/4。

### 簇 B · WAIT / dependencyRef 判定收敛

- 新增纯谓词 `contracts/decisions.py::wait_payload_matches_contract`(无副作用、不依赖请求类型)。
- 生产前置拒绝(`decision/engine.py::_check_eligibility_action`)与信封契约自检(`validate_v3_response_for_request`)共享同一原语,各自保留异常类型与错误语义。
- `validate_v3_response_for_request` 保留:它校验响应信封层(与生产路径的模型输出层不同对象),并承担版本/eligibility 摘要/evidence refs 自检,非纯冗余。
- Java `ActionEligibilityValidator` 权威复核零改动;Java 无需 null 分支的差异(Python V4 信封 `pending_dependencies` 必填)已在 audit.md 记录依据。
- 新增双路径一致性矩阵测试:7 种非法形态两路一致拒绝、合法形态两路一致放行,防漂移由测试锁定而非代码注释。

### 簇 C · 协议版本单一事实来源

- `global_assistant/contracts.py` 新增 6 个 `Final` 版本常量,全部 DTO `Literal` 引用常量(PEP 586 合法)。
- `/health` 公告(`gaExecutionProtocolVersions`/`workspaceRpcGrantVersions`)、GA 事件发送(`execution.py`)、ModelRequest 构造(`model_adapter.py`)、broker 请求(`broker_client.py` 的 `model-inference.v1`,激活原定义未用的 `INFERENCE_PROTOCOL_VERSION`)全部改引常量;`WORKSPACE_RPC_GRANT_VERSION` 从 model_client 层迁入 `contracts/protocol.py`。
- `ga-model-inference.v1`(Java 内部代际)与 `.v2`(Python wire)的有意区分保持不变并写入常量注释。
- 新增行为级公告一致性测试:公告的每个版本被对应 DTO 实际接受、未公告版本被拒、broker 实发值等于常量——公告与接受能力由测试而非约定绑定。

## 关键数字

- 生产代码 +268/−134(11 个 src 文件,其中 1 新增);新增测试 23 例(3 个文件);Python 套件 344→367;Java testOffline 487/0 与基线一致;CI 10/10 SUCCESS。
- src 内每个协议版本字面量从最多 3 处收敛为 1 处唯一定义点(grep 复核)。

## 安全与兼容验证

- 异常类名全部不变 ⇒ `brain_failure:<类名>` 与 Java `classifyBrainFailureDetail` 映射不变;新增机器码 `MODEL_OUTPUT_SECTION_WITHOUT_SOURCE_REFS` 不入 Java 白名单(纯增量);artifact 越界引用诊断恰好落入既有白名单对,run 失败 payload 增量携带 contractDiagnostic(设计内增强,内容全脱敏)。
- 响应体/异常消息/诊断字段无模型输出值(新增泄漏回归测试锁定);HTTP 502/409/422 与 /health 输出值不变。
- 全程 Fake/Scripted/MockTransport,无真实供应商、无生产 DB。

## 未实施事项与理由(见 audit.md §5)

- ModelClientError 传输失败不挂契约诊断(伪结构,UNKNOWN 如实保留)。
- 预算检查(max_model_calls<1)不加诊断(请求侧错误,Java wire 校验先拦)。
- prompt 侧 `waitPayloadSchema` 不与校验谓词做表示形式统一(声明 vs 执行,表示形式与生命周期不同)。
- ARCH-QUALITY-01 遗留 P2 项(SHA-256 五种写法、ref 前缀词表、conftest 夹具路径等)未动。

## 交付状态

- 代码提交:`8f6520c90b4caf05f3c411b13786e9febb98e4b2`(dev,已推送)
- CI:Run `40151616342`,10/10 SUCCESS(contracts-static / backend-independent / brain / validation-mode / frontend / backend / e2e / eval-baseline / cross-language / validation-summary)
- https://github.com/BangShou1st/spec-agent/actions/runs/40151616342
- dev 与 origin/dev 0 ahead / 0 behind;未触碰 `main`,未强推
- 用户 WIP(`ModelSettingsIsolationIntegrationTest.java`、`SHARED_RAG_ACCEPTANCE_PRECHECK.json`、全部 untracked 证据与脚本)完整保留
