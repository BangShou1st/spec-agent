# Acceptance Evidence 归档准则

> **Status**: `Canonical Guide` (证据提报规范)\
> **Authority**: 本文档为任何阶段性交付、回归测试或发布门禁提报验收证据的强制标准。

---

## 1. 核心原则：结论入库，过程本地化

本目录（`docs/acceptance-evidence/`）用于记录历次任务的**核心验证结论与机器可读的判定摘要**，不是构建产物的存储桶。

### 允许提交的内容 (Allowed Artifacts)
- **`delivery.md` / `summary.md`**：记录测试命令、通过/失败/跳过计数、关键耗时、环境隔离声明（不超过 200 行）。
- **`verification.json`**：测试套件结构化结果、场景通过状态枚举。
- **`git-state.json`**：提交哈希、分支名、工作区校验和。
- **微型配置/快照清单**：极小体量的结构化 metadata。

### 严禁提交的内容 (Prohibited Heavy Artifacts)
- **严禁提交构建报告全集**：严禁复制 Gradle `build/reports/`、Playwright HTML 报告等。
- **严禁提交 XML 测试明细**：严禁提交包含数百个文件的 `*-xml/` 测试明细目录。
- **严禁提交二进制与归档包**：严禁提交 `.bin`、`.idx`、`.class`、`.jar`、`.zip`、`.tar.gz` 等二进制文件。
- **严禁提交超长控制台输出**：数十兆的 raw console dump 或 full debug log 应保留在本地 `scratch/traces/` 或 CI Artifacts 中。

---

## 2. 目录命名规范

每次门禁或发布创建子目录：
`docs/acceptance-evidence/<round-or-feature-id>-<YYYYMMDD>/`

示例：
- `c16-real-integration-20261021/`
- `p3bae4-database-isolation-20261022/`

每个子目录下推荐仅包含：
```text
├── summary.md          # 人工可读的验收总结报告
├── verification.json   # 机器可读的断言结果
└── git-state.json      # Git 状态与哈希
```
