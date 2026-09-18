# OpenCode Zen 免费档接入 — 原理、闸门与维护手册

> 最后更新：2026-09-18。本文档沉淀本项目 `HttpOpenCodeZenTransport` 直连 opencode zen 免费模型的全部逆向/实测结论，与 `D:/OpenCode-UA-Start/README.md`（本地代理版）同源互证。

## 一、本项目怎么用 zen

后端 **直连** `https://opencode.ai/zen/v1`（默认 `DIRECT`，不走 18991 本地代理，不走 7897 Clash；`spec.agent.model.opencode.proxy` 未显式设置时永远是 `HttpClient.Builder.NO_PROXY`，见 `HttpOpenCodeZenTransport` 构造器与 `proxySelectorFor`）。

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
| 1 | UA 含 `opencode/<ver>` 且版本 ≥1.18（1.17→426） | `opencode/1.18.31 ai-sdk/provider-utils/4.0.23 runtime/bun/1.3.14`（CLI 真实全串，Bun fetch + ai-sdk 追加） | Mozilla/5.0 前缀可有可无 |
| 2 | `x-opencode-session` = `ses_`+12hex+14base62（总长30） | `OpenCodeZenIds.sessionId()`，位取反降序 | 只验格式；UUID 形状 403；顺序字母型 id 被上游低熵黑名单拒（时间戳+随机算法天然规避） |
| 3 | body `stream:true` | `completionPayload` 固定 true | false 一定 403 |
| 4 | body `tools` 含名为 `bash` 和 `read` 的 function | `RESERVED_TOOLS`（名字精确大小写敏感，描述/参数可伪造） | 旧占位名 `reserved_noop` 不满足名称闸门，2026-09-18 已改 |
| 5 | 免费模型免 Authorization | 透传用户 key（有就带） | `hy3-free`/`kimi-k2.5-free`/`glm-5-free`/`ling-3.0-flash-free` 上游要付费 key（401），换 `mimo-v2.5-free` 等 |

身份头四件套：`x-opencode-client: cli`、`x-opencode-project: global`（CLI 全局配置时 project.id 就是字面量 `global`，替代旧的 `prj_` 随机 id）、`x-opencode-request: msg_+26`（每请求新值）、`x-opencode-session`（每会话稳定复用）。

## 三、2026-09-18 改动记录

依据 `D:/OpenCode-UA-Start` 代理版同日实测（mimo/nemotron 200 通过），修正四处偏差：

1. `USER_AGENT`：`opencode/1.18.31` → 全串 `opencode/1.18.31 ai-sdk/provider-utils/4.0.23 runtime/bun/1.3.14`
2. `DESKTOP_CLIENT("desktop")` → `CLIENT_ID("cli")`（`OPENCODE_CLIENT` 默认值，desktop 是桌面应用专传值）
3. project 头：`prj_` 随机 id → 字面量 `global`；删除已无人引用的 `OpenCodeZenIds.projectId()`
4. `RESERVED_TOOLS`：`reserved_noop` 单工具 → `bash` + `read` 双工具（闸门4按名匹配）

同步更新 `HttpOpenCodeZenTransportTest` / `HttpOpenCodeZenTransportUserAgentTest` 断言。编译 + 三个 transport 测试类全绿。构建需 JDK 17+：`JAVA_HOME=E:/Java/jdk-21.0.10 ./gradlew test`。

## 四、故障排查

| 症状 | 原因 | 处理 |
|---|---|---|
| 403 FreeTierError | 5 道闸门缺一 | 逐项核对上表，重点看 tools 名字与 UA 版本 |
| 426 UpgradeRequired | UA 版本 <1.18 | 用全串 UA |
| 403 error code: 1010 | Cloudflare 盾拦（请求不像 CLI） | 确认 transport 常量未被改动 |
| 401 ModelError | 该免费模型上游要付费 key | 换 mimo-v2.5-free / nemotron-3.5-lightning-free |
| 连接超时 | 本地网络到 opencode.ai 不通 | 本项目强制直连不兜底；可临时设 `spec.agent.model.opencode.proxy=http://127.0.0.1:7897`（仅排障用） |
| 空内容/EMPTY_CONTENT | 上游免费档高峰期偶发 | 重试；首字延迟可达 10s+ 属正常 |

## 五、失效条件与对策

- 服务端目前只有"像不像 CLI"的格式校验。若升级为 **TLS 指纹**校验：JDK HttpClient 指纹与 Bun 不同，需换 curl_cffi 类方案
- 若升级为**请求签名**：需重新逆向新 CLI 二进制（`D:/npm-global/node_modules/opencode-ai`，Bun 打包 JS 可直接提取字符串）
- 免费模型名单会变：以 `GET /models` 实时返回为准（约 71 个，带 `-free` 后缀为候选，401 的除外）

## 六、相关文档

- `D:/OpenCode-UA-Start/README.md` — 本地代理版（18991），原理同源，含代理专属的路径映射与流式透传说明
- 逆向来源：opencode.exe v1.18.31 二进制 + `github.com/anomalyco/opencode` 官方源码交叉验证
