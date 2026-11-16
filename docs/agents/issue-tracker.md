# Issue tracker: GitHub

这个 repo 的 issues 和 specs 存放在 GitHub issues 中（`Tippydes/GitViz`）。所有操作都使用 `gh` CLI。

## Conventions

- **Create an issue**: `gh issue create --title "..." --body "..."`。多行 body 使用 heredoc。
- **Read an issue**: `gh issue view <number> --comments`，用 `jq` 过滤 comments，并同时获取 labels。
- **List issues**: `gh issue list --state open --json number,title,body,labels,comments --jq '[.[] | {number, title, body, labels: [.labels[].name], comments: [.comments[].body]}]'`，按需加上 `--label` 和 `--state` filters。
- **Comment on an issue**: `gh issue comment <number> --body "..."`
- **Apply / remove labels**: `gh issue edit <number> --add-label "..."` / `--remove-label "..."`
- **Close**: `gh issue close <number> --comment "..."`

从 `git remote -v` 推断 repo；在 clone 内运行时，`gh` 会自动处理。

## Ticket 结构

`/to-tickets` 生成的 ticket 正文依次是：顶部依赖摘要、重要程度、What to build、设计依据（指向 `docs/design/` 的章节链接）、Acceptance criteria、不在本 ticket 范围、Blocked by、Blocks。

## Pull requests as a triage surface

**PRs as a request surface: no.** _（如果这个 repo 把 external PRs 当作 feature requests，则设为 `yes`；`/triage` 会读取这个 flag。）_

设为 `yes` 时，PRs 走与 issues 相同的 labels 和 states，使用 `gh pr` 对应命令：

- **Read a PR**: `gh pr view <number> --comments`，以及 `gh pr diff <number>` 获取 diff。
- **List external PRs for triage**: `gh pr list --state open --json number,title,body,labels,author,authorAssociation,comments`，然后只保留 `authorAssociation` 为 `CONTRIBUTOR`、`FIRST_TIME_CONTRIBUTOR` 或 `NONE` 的（丢弃 `OWNER`/`MEMBER`/`COLLABORATOR`）。
- **Comment / label / close**: `gh pr comment`、`gh pr edit --add-label`/`--remove-label`、`gh pr close`。

GitHub 在 issues 和 PRs 之间共享一个 number space，因此裸 `#42` 可能是两者之一——用 `gh pr view 42` 解析，失败则回退到 `gh issue view 42`。

## When a skill says "publish to the issue tracker"

创建一个 GitHub issue。

## When a skill says "fetch the relevant ticket"

运行 `gh issue view <number> --comments`。

## Blocking

用 GitHub 的 **native issue dependencies**——canonical、UI 可见的表达。

- **Add an edge**: `gh api --method POST repos/Tippydes/GitViz/issues/<child>/dependencies/blocked_by -F issue_id=<blocker-db-id>`，其中 `<blocker-db-id>` 是 blocker 的数字 **database id**（`gh api repos/Tippydes/GitViz/issues/<n> --jq .id`，_不是_ `#number` 或 `node_id`）。
- **Read**: `gh api repos/Tippydes/GitViz/issues/<n> --jq .issue_dependencies_summary`；`blocked_by` 只统计 open blockers，即 live gate。
- **Frontier**: open、没有 assignee、`issue_dependencies_summary.blocked_by == 0` 的 issues。
- 每个 ticket 正文里同时写有 "Blocked by" 一节，依赖不可用时以它为准。所有 blockers 都关闭后，ticket 即为 unblocked。

## Wayfinding operations

供 `/wayfinder` 使用。**map** 是单个 issue，以 **child** issues 作为 tickets。

- **Map**: 单个带 `wayfinder:map` label 的 issue，保存 Notes / Decisions-so-far / Fog body。`gh issue create --label wayfinder:map`。
- **Child ticket**: 作为 GitHub sub-issue 链接到 map 的 issue（通过 sub-issues endpoint 使用 `gh api`）。未启用 sub-issues 时，把 child 加入 map body 中的 task list，并在 child body 顶部写 `Part of #<map>`。Labels：`wayfinder:<type>`（`research`/`prototype`/`grilling`/`task`）。一旦被 claim，ticket 被 assign 给 driving dev。
- **Blocking**: 同上一节的 native issue dependencies；依赖不可用时，回退到 child body 顶部的 `Blocked by: #<n>, #<n>` 行。
- **Frontier query**: 列出 map 的 open children（`gh issue list --state open`，限定在 map 的 sub-issues / task list），丢弃任何带有 open blocker 的（`issue_dependencies_summary.blocked_by > 0`，或 `Blocked by` 行中的 open issue）或带有 assignee 的；按 map 顺序第一个胜出。
- **Claim**: `gh issue edit <n> --add-assignee @me`——session 的第一次写入。
- **Resolve**: `gh issue comment <n> --body "<answer>"`，然后 `gh issue close <n>`，再向 map 的 Decisions-so-far 追加 context pointer（gist + link）。
