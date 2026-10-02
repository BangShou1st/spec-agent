# 本地搜索与知识检索配置

## 启动基础

安装 Java 21、Node/npm、Docker、Python 环境（GA 使用锁定的 CPython 3.11.14，可由 uv 创建）。从自己的仓库目录运行，不依赖开发者个人目录、账号或模型。先 `docker compose up -d postgres`，在 `frontend` 运行 `npm ci`，按根 README 准备项目 Brain 环境，然后运行根目录 `start-dev.bat`。启动器会启动前端、Java、项目 Brain、独立 GA Brain，并共享安装实例的内部认证密钥。

可复制 `deployment/application-local.example.yml` 到同目录 `application-local.yml`，调整本机数据库设置，在启动器终端设置：

```powershell
$env:SPRING_CONFIG_ADDITIONAL_LOCATION='optional:file:../deployment/application-local.yml'
.\start-dev.bat
```

该相对路径按 Java 的 `backend` 工作目录解析。示例里的数据库密码仅是仓库 Docker Compose 的公开开发默认值；自建数据库请使用自己的配置。也可直接设置 `SPEC_AGENT_DB_HOST/PORT/NAME/USER/PASSWORD`。示例没有真实服务凭据，应用不会自动加载 `.env`、教程目录或个人 Windows 环境。Linux/macOS 按根 README 的分组件命令启动，共享同一 `SPEC_AGENT_BRAIN_INTERNAL_SECRET`。

数据库连接、端口、Java/Python 地址、主密钥和内部认证仍是启动配置。主密钥默认首次生成到 `backend/data/secret-master.key`，也支持 `SPEC_AGENT_SECRET_MASTER_KEY`；不要在已有数据库上丢弃或替换它。内部认证由启动器复用根 `data/internal-secret.txt`。备份数据库时同时保管主密钥；它与内部认证 token 是不同用途的秘密。密钥不放 `VITE_*` 或提交到 Git。

## 联网搜索 `/settings/search`

填写 Tavily API key，选“启用联网搜索”，点击“保存”。“测试连接”只发起小范围真实搜索，不隐式保存，也可测试停用状态下的已存密钥。“更换密钥”输入新值后保存；留空保存保留现有值。“清除配置”经明确确认后清除凭据并停用。

首次没有数据库设置时兼容 Java 的 `SPEC_AGENT_TAVILY_API_KEY`。页面会显示来源；“导入并保存”显式把当前启动环境密钥加密复制到数据库，启用状态以表单为准。一旦保存或清除，数据库就是权威，残留环境变量不会复活配置；恢复必须明确导入或输入新密钥。保存后新请求使用新配置，已准备的 Tavily 请求保留其捕获的配置。GET 只返回有无凭据及末四位掩码。

## 知识检索 `/settings/retrieval`

1. 选择 Ollama 或 OpenAI-compatible Embeddings API，填写地址及**向量模型**。Ollama 地址是服务根地址，API 地址通常以 `/v1` 结尾，Java 将调用 `/embeddings`。选择适用于模型的查询编码方式；通用模型使用“原始文本”，Qwen 检索模型可使用“Qwen 检索指令”。高级设置中的超时和批量可按需调整。
2. “测试连接”使用隔离的文档和查询文本调用真正的 Embeddings 接口，检测原生维度（1–4096），不请求截断维度。聊天接口成功不能作为依据。API 测试可能产生费用；远程服务会收到所选语料，使用自己信任的供应商。先测试再保存可使用短期测试凭证直接批准候选；先保存也可以，但需测试已保存配置再重建。
3. 保存仅形成候选，不切换当前索引。选择“全局助手产品帮助”，点击“重建所选索引”。完成后点击“启用新索引”，才切换 HELP 的生效模型及代际。失败时仍使用原索引，可修复服务后“重试失败任务”。页面可关闭，Java 后台继续执行；运行中进程重启会诚实标为中断失败，可重试。

Ollama 需自行运行并安装自己选择的 embedding 模型，不自动下载，也不要求使用测试机器的模型。默认只信任本机/容器宿主地址；部署到其他内部地址时，在 Java 启动环境明确设置 `SPEC_AGENT_EMBEDDING_TRUSTED_ORIGINS`（逗号分隔 origin）。容器内 `localhost` 指向容器自身。普通远程 API 要求 HTTPS，本机兼容 API 可 HTTP。Ollama 模式不支持网关密钥；带鉴权的兼容服务使用 API 模式。

远程 key 只在 Java 复用现有加密机制保存及调用，Python 通过绑定宿主工作负载的 broker 使用向量，不收到供应商 key。省略 key 仅对同一服务类型和地址保留；更换地址不会携带旧凭据。“清除凭据”同时撤销相关历史连接凭据，停止其远程调用，保留索引数据。新的连接地址不会擅自替换仍生效的旧模型。

无凭据、服务不可达、模型不存在、没有索引都会显示明确状态；可以先使用不依赖这些服务的功能。当前设置仅允许显式重建 HELP，项目 Agent 的默认 Ollama profile、项目索引和冻结输入不迁移。聊天模型、Zen broker 仍按原模型配置页运行。

## 可用性边界

不同供应商、地址、模型/本地模型 digest、维度或查询编码方式产生不同语义 profile，同维度模型也不能复用旧向量。复用原 `retrieval_entries` 与 pending 列；准备成功、来源和头版本一致才事务激活，不增加向量库。超时、批量和密钥属于连接修订，新批准请求读取匹配模型的最新连接限制，已经批准的 Python 请求保持捕获配置。

远程服务若在同一个模型名字下悄悄变更权重且不暴露版本，无法从 API 元数据证明权重身份：应使用版本化模型 ID，并测试、重建。测试成功只代表该次请求可用，不是持续健康或检索质量保证。
