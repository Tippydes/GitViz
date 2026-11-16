# Triage Labels

Skills 使用五个 canonical triage roles。这个文件把这些 roles 映射到此 repo issue tracker 中实际使用的 label 字符串。

| Label in mattpocock/skills | Label in our tracker | Meaning                                  |
| -------------------------- | -------------------- | ---------------------------------------- |
| `needs-triage`             | `needs-triage`       | Maintainer needs to evaluate this issue  |
| `needs-info`               | `needs-info`         | Waiting on reporter for more information |
| `ready-for-agent`          | `ready-for-agent`    | Fully specified, ready for an AFK agent  |
| `ready-for-human`          | `ready-for-human`    | Requires human implementation            |
| `wontfix`                  | `wontfix`            | Will not be actioned                     |

当某个 skill 提到 role（例如 “apply the AFK-ready triage label”）时，使用此表中对应的 label 字符串。

编辑右侧列，使其匹配你实际使用的 vocabulary。

## 重要程度

每个 ticket 另带一个重要程度标签：

| Label | 含义 |
| --- | --- |
| `重要程度: 必须` | 开题验收标准或课程约束直接要求，或是大量后续 ticket 的地基；缺了不能验收 |
| `重要程度: 应该` | 设计文档要求的完整体验；缺了能验收，但与设计不一致 |
| `重要程度: 可削减` | 设计文档标明的辅助功能（书签、筛选方案、设置页）及只依赖它们的 ticket；进度紧张时可整体推迟 |

同一 frontier 上有多个可领的 ticket 时，先做重要程度高的；重要程度相同时，先做解锁后续 ticket 多的（见 ticket 正文的 Blocks 一节）。
