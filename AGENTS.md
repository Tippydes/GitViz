# GitViz

帮助 Git 初学者看懂本地仓库提交历史的桌面应用：提交列表 / 时间线、贡献统计图表、提交差异。Java + JavaFX + Maven 的课程项目（第 9 组 PBL）。程序只读取仓库。

## 规格

`docs/design/` 是唯一的规格来源。issue、提交信息和代码注释里的 `§x.y` 都指 `docs/design/system-design.md` 的章节：

- 做界面：先读 §2.2 里对应的页面，再读 §2.3.1 表 4 的跳转。**P1–P10 是页面编号，T01–T25 是跳转编号**，都不是优先级。
- 改"打开仓库"流程或分析缓存：§2.3.2（图 10）。
- 读写 `~/.gitviz` 下的文件：§3.4 字段表、§3.6 读写时机、§3.7 异常处理。
- 错误提示：§2.2.6 表 3。测试要点：§3.8。
- 需求范围与验收标准：`docs/design/proposal.md`（开题文档）。

实现必须偏离设计时，在同一个 PR 里修改设计文档，让文档始终描述真实的程序。

## 硬约束

来自课程要求和设计原则（§1.2）：

- **只读 Git**：只调用白名单子命令 `--version`、`rev-parse`、`log`、`show`、`merge-base --is-ancestor`、`rev-list --count`；用 ProcessBuilder 的参数列表调用，仓库路径作为独立参数；每条命令带 `-c i18n.logOutputEncoding=UTF-8`。需要改用户 Git 配置的场景（如 `safe.directory`）只展示命令，由用户自己执行。所有 git 调用都经过 `GitService` → `GitRunner`，白名单由 `GitCommand` 在代码层面保证。
- **只用本地文件持久化**：用户数据用 JSON（Gson），分析缓存用 CSV（Apache Commons CSV）。课程禁止任何数据库，包括 SQLite、H2 等嵌入式数据库。
- **数据只写 `~/.gitviz`**：被分析的仓库目录和程序安装目录保持原样。
- **原子写入**：每次写盘都经单线程 PersistenceExecutor 排队，按"同目录 .tmp → force → 复制 .bak → ATOMIC_MOVE"写入（§3.6）。缓存的 meta.json 最后写，作为缓存完整的标记。
- **线程**：Git 调用和文件读写在后台线程；界面对象和可观察属性只在 JavaFX 应用线程读写；线程之间只传不可变对象。
- **显式 UTF-8**：读 git 输出和文件时显式指定 UTF-8（中文 Windows 的默认编码是 GBK）。JSON 无 BOM；CSV 带 BOM、CRLF。
- **分层**：界面层 → 服务层 → 持久化层，单向依赖。持久化层是纯 Java；服务层最多用到 JavaFX 的可观察属性和 `Task`；两者都能脱离界面单测。
- **面向初学者的文案**：提示一律用中文，说明原因并给出下一步操作；git 原始输出放进默认折叠的"技术详情"。

## 构建与测试

- 用 Maven Wrapper，本机不需要装 Maven：`./mvnw test` 运行全部测试，`./mvnw javafx:run` 启动程序；Windows 的 PowerShell / cmd 里用 `.\mvnw.cmd`。命令变化时同步修改这里和 README。
- 运行需要 JDK 17+、Git ≥ 2.31（缓存构建用到 `--diff-merges`）。编译目标与 JavaFX 版本的决策见 `docs/adr/0001-javafx-22-on-jdk-17.md`，改版本前先读它。
- 测试仓库用 `TestRepo`（`testutil` 包）在 `@TempDir` 里现场创建：它隔离开发者本机的 Git 配置，作者和时间通过环境变量固定。数据目录指向临时目录；"今天"由可注入的时钟决定。测试结果与开发者本机的仓库、Git 配置和日期无关。
- 测试 JVM 的默认字符集被故意设成 GBK（见 `pom.xml`），漏写 UTF-8 的读写会在中文测试数据上失败。

## 工作方式

- 任务是 GitHub issue，每个都是一条纵向切片（tracer bullet），验收标准写在 issue 里。开工前读完 issue 及其"设计依据"列出的章节。
- 只领 frontier 上的 issue：它的 blocked-by 全部已关闭。
- 每条验收标准对应一个自动化测试，或在测试说明里写明手工检查步骤。
- 组件名沿用设计文档（RepositorySession、FilterState、AnalysisCache、PersistenceExecutor 等，见 §1.3 表 1），issue、提交信息和代码保持同一套叫法。

## Agent skills

### Issue tracker

Issues 在 GitHub `Tippydes/GitViz`，用 `gh` 操作，依赖关系用 GitHub 原生 issue dependencies。见 `docs/agents/issue-tracker.md`。

### Triage labels

五个默认 triage 标签，另有三个"重要程度"标签。见 `docs/agents/triage-labels.md`。

### Domain docs

single-context；在 `CONTEXT.md` 出现之前，术语以设计文档为准。见 `docs/agents/domain.md`。
