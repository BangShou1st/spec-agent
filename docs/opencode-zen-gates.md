# OpenCode Zen 接入 — 原理、闸门与维护手册

> 最后更新：2026-10-16。本文档沉淀本项目 `HttpOpenCodeZenTransport` 直连 opencode zen 免费模型的全部逆向/实测结论，与 `D:/OpenCode-UA-Start/README.md`（本地代理版 V2）同源互证。

共享实现由独立仓库 E:/project/shared-libs/opencode-zen 维护（两个模块：
opencode-zen-spring-boot-autoconfigure 与 opencode-zen-spring-boot-starter；协议与模型规则
也包含在自动配置模块中，版本 1.2.0）。本项目**不再保存源码快照**：后端以普通依赖
`com.sharedlibs:opencode-zen-spring-boot-starter` 解析制品（本机 mavenLocal，CI 用私有
GitHub Packages）；联调时设 `OPENCODE_ZEN_LOCAL` 指向库检出目录，Gradle 用本地源码替换同坐标
依赖。请求身份、标识符、保留工具、SSE 帧、模型元数据解析、免费资格规则及免费/付费凭证选择
以共享库为代码源；本文保留验证结论与排障说明。升级与发布流程见
docs/OPENCODE_SHARED_COMPONENT.md。

## 一、本项目怎么用 zen

后端 **直连** `https://opencode.ai/inference/v1`（默认 `DIRECT`，不走 18991 本地代理，不走 7897 Clash；`spec.agent.model.opencode.proxy` 未显式设置时永远是 `HttpClient.Builder.NO_PROXY`，见 `HttpOpenCodeZenTransport` 构造器与 `proxySelectorFor`）。

伪装由 transport 内部完成，调用方无感：

| 文件 | 职责 |
|---|---|
| `OpenCodeZenTransport.java` | 闸门常量唯一来源（UA、头名、client、project） |
| `HttpOpenCodeZenTransport.java` | 请求构造（头 + body 注入）、SSE 解析、错误分类、直连客户端 |
| `OpenCodeZenIds.java` | `ses_`/`msg_` id 生成算法（时间戳×0x1000+12bit计数，可位取反） |
| `OpenCodeZenSessionIds.java` | 会话→session id 的稳定映射缓存 |

## 二、5 道闸门（实测二分验证，缺一即 403 FreeTierError）

| # | 闸门 | 本项目实现 | 验证结论 |
|---|---|---|---|
| 1 | UA 含 `opencode/<ver>` 且版本 ≥1.18（1.17→426） | `opencode/1.19.0 ai-sdk/provider-utils/4.0.23 runtime/bun/1.3.14`（与已验证的本地 V2 客户端同版本） | Mozilla/5.0 前缀可有可无；1.18.31 与 1.19.0 均实测 200 |
| 2 | `x-opencode-session` = `ses_`+12hex+14base62（总长30），且**中段 hex ∈ 0xff0..0xfff**（真实客户端"同毫秒内从 0 单调递增"的计数器语义） | `OpenCodeZenIds.sessionId()`，位取反降序 + **同毫秒单调计数器（≤0xF，耗尽则推进毫秒）** | 只验格式不足以通过：**2026-10-16 字节级 A/B 证实随机计数器稳定 403**（唯一根因），真实小值计数器稳定 200；UUID 形状 403；顺序字母型 id 被上游低熵黑名单拒 |
| 3 | body `stream:true` | `completionPayload` 固定 true | false 一定 403 |
| 4 | body `tools` 含名为 `bash` 和 `read` 的 function | `RESERVED_TOOLS`（名字精确大小写敏感，描述/参数可伪造） | 旧占位名 `reserved_noop` 不满足名称闸门，2026-09-18 已改；2 工具与 5 工具形态均实测 200 |
| 5 | 按模型选择凭据：免费档使用公开凭据 `Bearer public`，付费档使用用户 Key | 免费模型始终走 `public`；付费模型没有 Key 时本地拒绝 | 2026-10-16 实测：免费模型带存储密钥 → 401，`public` → 200 |

模型列表同时包含免费和付费模型；设置页的“仅免费”只过滤展示，不会把产品限制为免费档。免费模型无需 Key，且无论是否保存了付费 Key，都固定发送 `Bearer public`；付费模型必须有 Key。免费资格取官方价格元数据，未知模型按付费处理。

身份头五件套：`x-opencode-client: cli`、`x-opencode-project: global`（CLI 全局配置时 project.id 就是字面量 `global`，替代旧的 `prj_` 随机 id）、`x-opencode-request: msg_+26`（每请求新值）、`x-opencode-session`（每会话稳定复用）、`x-opencode-session-id`（与 session 同值，已验证客户端双头齐发）。生产补全 body 另带 `stream_options.include_usage:true`（与已验证客户端一致，可选项）。

## 三、2026-10-16 改动记录（对齐已验证可用的本地 V2 客户端）

产品验收发现 Java 直连持续 403 FreeTierError,而同机的本地 V2 代理(`D:/OpenCode-UA-Start/opencode-zen-proxy.py`)成功。通过对上游做单变量 A/B + 回声服务器字节级抓包,把差异隔离到唯一根因并修复:

1. **`OpenCodeZenIds` 计数器语义(403 根因)**:旧实现用 `ThreadLocalRandom` 随机填 12bit 计数器,而真实客户端是"同一毫秒内从 0 单调递增"——上游据此校验降序会话 id 的中段 hex(取反后必须落在 `0xff0..0xfff`,即计数器 ≤0xF)。随机计数器只有 16/4096 概率合规,叠加会话 id 缓存复用,几乎 100% 被拒。修复:共享单调计数器,槽位耗尽推进毫秒。
2. **凭据策略**:免费模型实测对存储密钥返回 401(该免费档不认用户 key),对 `Bearer public` 返回 200。凭模型价格元数据选择凭据：免费模型始终走 `public`；付费模型必须配置真实 Key。
3. **`x-opencode-session-id` 双头**:补齐与 `x-opencode-session` 同值的第二个会话头(已验证客户端双头齐发)。
4. **UA 对齐**:`1.18.31` → `1.19.0`(与 V2 代理同版;两者实测都被接受,属防御性对齐)。
5. **`stream_options.include_usage`**:生产补全补上(与 V2 客户端一致;实测可选项,usage 便于计量)。

实验同时证明**无需改动**:基址(`/zen/v1` 与 `/inference/v1` 等价)、2 工具占位形态、探测请求(max_tokens+response_format)、GA Chat 形态(tool 消息+tool_choice)、SSE 解析与协议门禁。修复后 Java 传输直连上游获得真实模型正文,GA 端到端(COMPLETED)与网页对话全部验证通过。

## 四、2026-09-18 改动记录

依据 `D:/OpenCode-UA-Start` 代理版同日实测（mimo/nemotron 200 通过），修正四处偏差：

1. `USER_AGENT`：`opencode/1.18.31` → 全串 `opencode/1.18.31 ai-sdk/provider-utils/4.0.23 runtime/bun/1.3.14`
2. `DESKTOP_CLIENT("desktop")` → `CLIENT_ID("cli")`（`OPENCODE_CLIENT` 默认值，desktop 是桌面应用专传值）
3. project 头：`prj_` 随机 id → 字面量 `global`；删除已无人引用的 `OpenCodeZenIds.projectId()`
4. `RESERVED_TOOLS`：`reserved_noop` 单工具 → `bash` + `read` 双工具（闸门4按名匹配）

同步更新 `HttpOpenCodeZenTransportTest` / `HttpOpenCodeZenTransportUserAgentTest` 断言。编译 + 三个 transport 测试类全绿。构建需 JDK 17+：`JAVA_HOME=E:/Java/jdk-21.0.10 ./gradlew test`。

## 五、故障排查

| 症状 | 原因 | 处理 |
|---|---|---|
| 403 FreeTierError | 5 道闸门缺一,最常见是 id 计数器形态不合规 | 逐项核对上表,重点看会话 id 中段 hex 是否落在 0xff0..0xfff |
| 426 UpgradeRequired | UA 版本 <1.18 | 用全串 UA |
| 403 error code: 1010 | Cloudflare 盾拦（请求不像 CLI） | 确认 transport 常量未被改动 |
| 401 ModelError / 401 server_error | 免费模型误带付费 key,或付费模型的 Key 已失效 | 免费模型固定走 `Bearer public`；付费模型走已保存 Key |
| 连接超时 | 本地网络到 opencode.ai 不通 | 本项目强制直连不兜底；可临时设 `spec.agent.model.opencode.proxy=http://127.0.0.1:7897`（仅排障用） |
| 空内容/EMPTY_CONTENT | 上游免费档高峰期偶发 | 重试；首字延迟可达 10s+ 属正常 |

## 六、失效条件与对策

- 服务端目前只有"像不像 CLI"的格式校验。若升级为 **TLS 指纹**校验：JDK HttpClient 指纹与 Bun 不同，需换 curl_cffi 类方案
- 若升级为**请求签名**：需重新逆向新 CLI 二进制（`D:/npm-global/node_modules/opencode-ai`，Bun 打包 JS 可直接提取字符串）
- 免费模型名单会变：以 `GET /models` 实时返回为准,并以官方目录 `models.opencode.ai/api.json` 的价格/协议元数据双重判定

## 七、相关文档

- `D:/OpenCode-UA-Start/README.md` — 本地代理版（18991），原理同源，含代理专属的路径映射与流式透传说明
- 逆向来源：opencode.exe v1.18.31 二进制 + `github.com/anomalyco/opencode` 官方源码交叉验证
