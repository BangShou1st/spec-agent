# 本地部署服务配置实施与验收记录

日期：2026-10-02。对应 [设计](LOCAL_DEPLOYMENT_SERVICE_CONFIGURATION_DESIGN.md)，包括第 10 节页面及第 11 节浏览器截图验收。功能、确定性回归、真实 Tavily/Ollama 和本地兼容 API 全链路已完成；**真实云端 embedding 联调未完成**，没有使用或寻找远程账号凭据，不将测试夹具当作云端服务验收。

## 完整改动

| 范围 | 实现 |
|---|---|
| Tavily | `/settings/search`；Java GET/PUT/DELETE/test；加密数据库权威、兼容首次环境配置、显式导入、独立测试、保留/替换/清除凭据及禁用；已准备请求捕获配置，新请求读取更新 |
| Embedding | `/settings/retrieval`；两种提供商、地址/模型/密钥、高级参数、真实文档/查询接口测试、实际原生维度、短期绑定配置/凭据的测试凭证、候选与当前生效状态 |
| Java | 复用 `ModelCredentialCrypto`，增加 V53 设置/修订/profile/测试凭证/重建表及密钥轮换覆盖；受内部认证和工作负载授权约束的 embedding broker，严格排序/数量/维度/有限值/非零验证及结果写入围栏 |
| Python | LangChain 标准 `Embeddings`；原生 Ollama 本地计算和 workload-bound Java broker 适配；无供应商凭据，批准 profile 验证、动态维度与现有 float32/L2/checksum 流程 |
| 索引 | 原 `retrieval_entries`/pending 列；HELP 显式选择、QUEUED→RUNNING→READY→显式 ACTIVE；失败保留原头/向量、同任务重试、重启中断恢复；激活事务检查完整向量、来源及头版本 |
| 前端 | 扩展原 SettingsLayout；ProviderCard 泛化到 SettingsCard；原 providerSettings.css、token、ApiErrorBanner、摘要和控件；中文语义、窄屏单列、加载/错误/确认、后台进度轮询 |
| 本地部署 | [配置说明](LOCAL_DEPLOYMENT_SERVICE_CONFIGURATION_GUIDE.md) 与 `deployment/application-local.example.yml`；明确数据库、主密钥、内部认证和连接地址仍需启动配置 |

未引入第二套向量存储，未自动重建项目索引。项目默认 `retrieval.v1` 与固定 Ollama profile 保留；新增 `retrieval.v2` 只接收宿主批准的语义 profile。同维度不同模型也有不同 identity。聊天提供商、Zen broker、项目 Runtime/Brain 冻结历史未改变。

## 验证结果

| 检查 | 最终结果 | 本地证据 |
|---|---|---|
| Java `gradlew.bat testNonLive bootJar` | 1598 总项：1565 通过、33 跳过，0 失败/错误；含架构、原模型/Tavily、项目检索及新集成边界 | `scratch/service-settings/java-final.log`、`backend/build/test-results/testNonLive` |
| Python `.venv/Scripts/python -m pytest -q` | 229 通过；一个既有 Starlette/AnyIO 弃用警告 | `python-final.log` |
| 前端 `npm test` | 867 通过、1 跳过；新页凭据省略/明确清除/测试凭证失效覆盖 | `frontend-final.log` |
| 前端 `npm run build` | vue-tsc 与 Vite 构建通过；既有大 chunk 提示 | `build-final.log` |
| Tavily 真实服务 | 环境显式导入、真实成功/无效草稿密钥失败、刷新、禁用、清除、恢复；没有保存错误草稿 | `search-browser.json` 与对应截图 |
| Java 两次重启 | 禁用仍保留遮罩；清除后 DB tombstone 不因存在环境 key 复活；Embedding 配置、HELP 生效代际与 3 个可用片段不变 | `persistence-browser.json`、`*after-restart.png` |
| Ollama 真实服务 | 现有 Qwen 1024 维及 Nomic 768 维，真实文档/查询向量；候选、重建、READY、显式激活；不存在模型真实报错 | `retrieval-browser.json` 与对应截图 |
| 兼容 API 本地夹具 | 实际 Java/Python/Vue 联调，模拟 `/embeddings` 返回反序 5 维数组，排序正常；故障保留旧 3 片段索引、重试/RUNNING/READY/激活、掩码、省略保留、明确清除 | `api-browser.json`；所有图内明确标注夹具 |
| 空白安装 | 独立 Java 18080 + 新数据库、V53 全部迁移，无 Tavily/embedding 配置、无 HELP 索引；真实请求不存在的本机 Ollama 端口返回连接失败 | `fresh-browser.json`、`fresh-*.png`；不是模拟设置响应 |
| 生效检索 | 正常全局助手执行完成，一次 `help.search` 成功；返回 profile/代际与 HELP 当前头完全一致 | `live-help-run.json`；运行 `32df1570-3192-4d84-aab6-64d664ef568d`，工具收据 SUCCEEDED |
| 项目兼容 | 191 份冻结输入，实施前后有序 payload_hash 校验均为 `65c08059e9a7b442a37ce207eea7fd14`；项目默认 profile 未迁移 | 实际开发数据库只读检查；完整原项目回归 |
| 凭据 | API 不含明文；DB 非空密钥 `enc:v1:`；新改动/证据文本/日志对实际 Tavily key 扫描零匹配；截图仅掩码；前端不持久化 key | 集成测试、SQL 布尔检查及本地安全扫描 |

验收结束时 Tavily 已显式恢复为启用、DB 权威；HELP 已恢复 Qwen 1024 维，经当前候选重建并显式激活。验收阶段没有下载模型、修改原项目 Brain 环境、修改其他服务配置或提交/推送。空白验收 Java 实例已经停止。后续收尾经用户授权检查最终 diff、文档及提交范围，再提交并推送 `origin/main`；本地截图证据保留在忽略目录。

## 第 10–11 节视觉验收

实际使用本机 Edge/Playwright 打开真实 Vue 页面与 Java/Python API。视口为桌面 1440×900、窄屏 390×844；长页面保存 fullPage，图片高度因此大于视口。**全部 44 张 PNG 已通过图像查看工具逐张打开检查**。改动后最终两页桌面/窄屏、空白安装、全部 API 夹具重拍并再次查看；独立 finish reviewer 最终结论 `ship`，无剩余实质视觉问题。

证据目录：`E:\project\spec-agent\scratch\service-settings`。该目录被 `.gitignore` 忽略，不提交截图/浏览器脚本/日志/密钥。下表文件名均相对于此目录；桌面/窄屏说明的是捕获视口，不是 fullPage 图片最终高度。

| 截图文件（均 `.png`） | 页面 / 视口 / 状态 | 来源与检查结果 |
|---|---|---|
| `models-baseline` | 原模型页 / 桌面 / 样式基准 | 真实页面；内容宽度、卡片/按钮/字体基准已查看 |
| `search-environment-desktop` | 搜索 / 桌面 / 环境来源 | 真实 Tavily 启动配置，凭据遮罩 |
| `search-masked-desktop`, `search-masked-mobile` | 搜索 / 双视口 / 已保存遮罩 | 真实 DB；字段与按钮完整 |
| `search-loading-desktop`, `search-success-desktop` | 搜索 / 桌面 / 测试中与成功 | 真实 Tavily；loading 禁用、成功反馈正常 |
| `search-disabled-desktop` | 搜索 / 桌面 / 停用 | 真实 DB；停用语义明确 |
| `search-error-desktop` | 搜索 / 桌面 / 认证失败 | 真实 Tavily 对合成错误草稿 key 的失败，未覆盖存储 |
| `search-empty-desktop`, `search-empty-mobile-focus` | 搜索 / 双视口 / 清除、键盘焦点 | 真实 DB tombstone；焦点可见，窄屏按钮单列 |
| `retrieval-empty-desktop`, `retrieval-empty-mobile` | 检索 / 双视口 / 无候选 | 当时旧 HELP 可用；不能冒充完全空白安装 |
| `retrieval-loading-desktop`, `retrieval-success-desktop` | 检索 / 桌面 / Ollama 测试 | 真实文档/查询接口，1024 维 |
| `retrieval-pending-desktop` | 检索 / 桌面 / 候选待重建 | 真实保存，当前索引保持原代际 |
| `retrieval-building-desktop` | 检索 / 桌面 / **QUEUED 等待开始** | 真实任务；该图不冒充 RUNNING，见下方运行图 |
| `retrieval-ready-desktop` | 检索 / 桌面 / 等待启用 | 真实重建，READY 与 ACTIVE 区分 |
| `retrieval-active-desktop`, `retrieval-active-mobile` | 检索 / 双视口 / 激活 | 真实 Qwen 索引；字段和说明无溢出 |
| `retrieval-error-desktop` | 检索 / 桌面 / 模型不存在 | 真实 Ollama 对合成模型名的错误 |
| `retrieval-nomic-ready-desktop`, `retrieval-nomic-active-desktop` | 检索 / 桌面 / 768 维切换 | 真实 Nomic；旧 1024 维到新 768 维事务激活 |
| `api-form-desktop-fixture`, `api-form-mobile-fixture` | API 表单 / 双视口 / 填写与焦点 | 明确标注本地夹具；无真实云端 key |
| `api-loading-desktop-fixture`, `api-success-desktop-fixture` | API / 桌面 / 测试中、5 维成功 | 真 broker 调用本地夹具；不是聊天测试 |
| `api-masked-desktop-fixture` | API / 桌面 / 候选遮罩 | 修复后明确“候选尚未启用”，保留当前索引 |
| `index-failed-desktop-fixture`, `index-failed-mobile-fixture` | 索引 / 双视口 / FAILED | 夹具真实 HTTP 500；错误、重试和旧索引说明可读 |
| `index-running-desktop-fixture` | 索引 / 桌面 / **RUNNING** | Java 后台实际任务，夹具延迟使运行态可捕获 |
| `index-ready-desktop-fixture`, `api-active-desktop-fixture` | 索引 / 桌面 / READY 与 ACTIVE | 夹具新索引准备、显式启用 |
| `api-cleared-desktop-fixture` | API / 桌面 / 凭据已清除 | 修复后明确当前索引保留、候选需验证 |
| `search-disabled-after-restart`, `search-cleared-after-restart` | 搜索 / 桌面 / 重启后 | 真实 DB，环境 key 存在也不自动启用或恢复 |
| `search-final-desktop`, `search-final-mobile` | 搜索 / 双视口 / 最终恢复 | 修复后较深文字 token、原风格、无溢出 |
| `retrieval-final-desktop`, `retrieval-final-mobile` | 检索 / 双视口 / 最终恢复 | 修复后较深文字 token、Qwen 1024 维生效 |
| `fresh-search-desktop`, `fresh-search-mobile` | 搜索 / 双视口 / 完全空白安装 | 独立真实 Java/DB；无任何 key |
| `fresh-retrieval-desktop`, `fresh-retrieval-mobile` | 检索 / 双视口 / 无配置且无索引 | 独立真实 Java/DB，提示基础功能可用 |
| `fresh-retrieval-unreachable` | 检索 / 桌面 / 服务不可达 | 真实关闭端口，明确连接失败 |

逐张检查：标题、880px 内容宽、卡片/字段对齐、中文换行、长模型/错误码、按钮和导航窄屏换行、焦点、loading/disabled、错误恢复和凭据遮罩。未见异常水平溢出、遮挡或丢失操作。自动 overflow 断言只是辅证，截图查看才是视觉验收。

首轮发现并修复：① 旧 ACTIVE 任务在保存新候选后误称“新索引已启用”，改为明确旧索引继续生效；② 新页大量小字沿用 muted token 对比度不足，限定新页使用既有 secondary token，白底约 6.33:1，保持 incumbent 风格。原模型页作为历史基准保留，不宣称已修复其他设置页的预存对比度债务。

## 使用与确实未完成事项

搜索：填 key → 保存 → 按需测试；旧环境配置用“导入并保存”。检索：填写实际向量服务 → 测试 → 保存候选 → 选择帮助语料并重建 → 完成后“启用新索引”。详见 [使用说明](LOCAL_DEPLOYMENT_SERVICE_CONFIGURATION_GUIDE.md)。

**尚未完成的真实联调：远程云端 Embeddings API。** API 模式的实现、确定性测试、浏览器两种表单和本地兼容接口故障/重试/激活链路已经完成；没有远程真实凭据，因此没有验证某一云供应商的真实权限、网络、费用或模型版本行为。未将本地夹具成功等同于这项验收。真实服务可能在相同模型名下改变权重，应使用版本化 ID；本次也不宣称检索质量评测通过。
