# Spec Agent 文档全景导航与治理规约

> **Document Status**: `Canonical` (权威规约)\
> **Authority**: 本文档为 `docs/` 目录的总索引与文档落子/归档/证据治理的唯一标准入口。所有贡献者与 AI 必须严格遵守本文档定义的目录结构与落子规则。

---

## 1. 文档体系总览 (Documentation Taxonomy)

项目文档按职责与生命周期严格分为以下层次：

```text
docs/
├── README.md                      # [当前文档] 全局文档总导航、架构图景与落子规范
│
├── specs/                         # [L1 核心基线规范 - 活文档] 系统的唯一权威规约（永续维护）
│   ├── PRODUCT_SPEC.md            # 产品定位与业务边界
│   ├── ARCHITECTURE.md            # 系统总体分层与架构设计
│   ├── AGENT_RUNTIME.md           # Agent 运行时生命周期与边界
│   ├── CONTEXT_RULES.md           # 上下文构建与历史防污染规则
│   ├── MODEL_GATEWAY.md           # 模型网关、Provider 适配与防直连
│   ├── DEVELOPMENT_ENVIRONMENT.md # 开发环境与隔离测试数据库准则
│   ├── BACKEND_STRUCTURE.md       # 后端工程分层架构
│   ├── FRONTEND_STRUCTURE.md      # 前端工程分层架构
│   ├── SKILL_PACKAGE_SPEC.md      # 技能包协议与格式
│   ├── MODEL_AGNOSTIC_EVALUATION.md # 模型评测体系
│   └── OPENCODE_SHARED_COMPONENT.md # OpenCode Zen 共享组件规范
│   (兼容说明：根目录 docs/*.md 保留对核心规范的原生引用，新归纳视图统归于此)
│
├── v2/                            # [L2 V2 演进专项规约] V2 Graph & Agent 系统专属
│   ├── README.md                  # V2 文档索引与权威契约速览
│   ├── GRAPH_MODEL_V2.md          # 冻结的核心 Graph 契约与连接规则
│   ├── NODE_MODEL_V2.md           # Node 模型与 Workspace Unit
│   ├── AGENT_RUNTIME_ARCHITECTURE.md # V2 Agent Runtime 与 Decision Cycle
│   ├── CAPABILITY_RUNTIME.md      # Capability / Skill / MCP 统一调用层
│   └── UNIFIED_PYTHON_RAG_DESIGN.md # 统一 Python RAG 与 Ollama 架构设计
│
├── rfcs/                          # [L3 设计提案与架构决策] 阶段性特性方案与 ADR
│   └── YYYYMMDD-<feature>-design.md
│
├── runbooks/                      # [L4 运维与本地开发操作指南]
│   └── local-dev-troubleshooting.md
│
├── archive/                       # [L5 历史归档区] 冻结的历史记录，不代表当前代码行为
│   ├── delivery-reviews/          # 历史各轮交付评审 (DELIVERY-ROUND1~5 等)
│   ├── bugfixes-and-incidents/    # 历史事故复盘与排错总结 (P3B incident 等)
│   ├── experiments/               # 历史 A/B 测试报告与模型对比
│   └── legacy/                    # 早期旧模块归档 (如 superpowers/ 等)
│
└── acceptance-evidence/           # [L6 验收证据] 结构化轻量证据区（严格瘦身）
    ├── README.md                  # 证据记录准则与禁止事项
    └── <round-id>/                # 每次验收门禁的轻量结构化归档 (summary.md + json)
```

---

## 2. 核心规约索引 (Canonical Specifications)

在修改系统代码前，必须先阅读以下对应的权威规范：

| 规范文件 | 适用范围 / 关注重点 |
| :--- | :--- |
| [PRODUCT_SPEC.md](PRODUCT_SPEC.md) | 产品定位、问题定义、业务边界（非代码生成、非项目管理） |
| [ARCHITECTURE.md](ARCHITECTURE.md) | 领域模型、分层架构、数据所有权与 Runtime 核心不变式 |
| [AGENT_RUNTIME.md](AGENT_RUNTIME.md) | Agent 执行流程、单步预算、状态持久化与失败边界 |
| [CONTEXT_RULES.md](CONTEXT_RULES.md) | 上下文投影原则：从 Tip 沿 Lineage 回放，严禁全局聊天历史 |
| [MODEL_GATEWAY.md](MODEL_GATEWAY.md) | 模型抽象、防 Runtime 直连 HTTP、Provider 隔离与凭据保护 |
| [DEVELOPMENT_ENVIRONMENT.md](DEVELOPMENT_ENVIRONMENT.md) | 本地环境搭建、数据库隔离安全门禁 (`spec_agent_p3b_live_*`) |
| [BACKEND_STRUCTURE.md](BACKEND_STRUCTURE.md) | 后端 Spring Boot 模块划分与包依赖单向流动 |
| [FRONTEND_STRUCTURE.md](FRONTEND_STRUCTURE.md) | 前端 React + Canvas 架构、状态流与组件边界 |
| [docs/v2/README.md](v2/README.md) | V2 Graph Workspace、统一 Action Protocol 与 Python Brain 边界 |

---

## 3. 文档生命周期与落子准则 (Placement & Lifecycle)

后续所有新文档编写必须严格对照下表归位，**严禁在 `docs/` 根目录或项目根目录随意平铺新文档**：

| 文档类型 | 存放目录 | 命名格式 | 生命周期 / 状态标记 |
| :--- | :--- | :--- | :--- |
| **系统核心规约 (Spec)** | `docs/` 或 `docs/v2/` 权威文件 | `UPPER_SNAKE_CASE.md` | `status: Canonical`，长期维护，随代码同步更新 |
| **设计提案 (RFC / Proposal)** | `docs/rfcs/` | `YYYYMMDD-<feature>-design.md` | `status: Proposal -> Frozen -> Historical` |
| **交付复盘 / 评审战报** | `docs/archive/delivery-reviews/` | `YYYYMMDD-<feature>-delivery.md` | `status: Historical`，仅作历史证据，不作当前规约 |
| **事故分析 / Bugfix 复盘** | `docs/archive/bugfixes-and-incidents/` | `YYYYMMDD-<incident>-review.md` | `status: Historical`，记录根本原因与防复发措施 |
| **评测实验 / A/B 对比** | `docs/archive/experiments/` | `YYYYMMDD-<topic>-report.md` | `status: Historical`，记录历史模型对比与量化指标 |
| **本地操作 / 故障排除指南** | `docs/runbooks/` | `<topic>-runbook.md` | `status: Runbook`，日常操作手册 |

### 文档元数据 Header 标准
任何新创建或重大修改的文档，头部必须包含以下元数据块：

```markdown
---
title: "文档标题"
status: Canonical | Proposal | Frozen | Historical | Runbook
category: Architecture | Specification | Decision | Report | Guide
date: YYYY-MM-DD
last_updated: YYYY-MM-DD
authority: "Current Authority (权威基线)" | "Historical Reference Only (历史参考)"
related_files:
  - "backend/src/main/..."
---
```

---

## 4. 运行日志与测试产物治理 (Logs & Test Artifacts)

### 4.1 根目录零日志原则 (Zero-Log Root)
* **禁止**在项目根目录（`e:\project\spec-agent\`）生成任何 `*.log`、`*.txt` 或临时探针脚本。
* 任何启动命令（如 `start-dev.bat`）、重测脚本（如 `test-nonlive`）的重定向输出必须统一写入 `scratch/logs/` 或操作系统临时目录。

### 4.2 验收证据沉淀黄金法则 (Acceptance Evidence Policy)
在 `docs/acceptance-evidence/<round-id>/` 下沉淀门禁证据时：
1. **精简结论入库**：只允许保存说明通过率与门禁耗时的 `summary.md`、关键哈希与测试断言的 `verification.json`、以及记录 git 状态的 `git-state.json`。
2. **严禁构建产物 Dump**：**严禁将 Gradle 的 `build/reports/`、Playwright HTML 报告、XML 测试结果集、二进制 `.bin`/`.idx`、编译后的 `.class`/`.jar` 或全量 `.zip` 提交进代码库**。
3. **厚重过程本地化**：几万行的执行控制台输出与测试录屏一律放在 `scratch/traces/` 或 CI Artifacts，不可污染 Git 追踪树。

### 4.3 本地工作台 `scratch/` 规范
`scratch/` 为开发本地私有工作台（已被 `.gitignore` 忽略），推荐组织结构如下：
* `scratch/logs/`：本地后台进程与服务日志
* `scratch/traces/`：Playwright/E2E 录屏与轨迹文件
* `scratch/dumps/`：本地数据库快照
* `scratch/tmp/`：临时探针与一次性分析脚本
