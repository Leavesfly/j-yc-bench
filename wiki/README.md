# j-yc-bench Wiki

欢迎来到 j-yc-bench 的 Wiki。这里用尽量通俗的语言，把"让 LLM Agent 扮演一名 AI 创业公司 CEO 经营一年"的这个基准测试（Java 移植版），从头到尾讲清楚。

j-yc-bench 是 [YC-Bench](https://github.com/collinear-ai/yc-bench) 的 Java 移植版本，技术栈为 **Java 11 + Maven + SQLite JDBC + Picocli + Jackson**。

如果你只想快速了解项目本身的代码与跑法，请看根目录的 [`README.md`](../README.md)。
如果你想看更偏架构、偏代码细节的设计文档，请看 [`system_design/`](../../system_design/)。
本 Wiki 面向**第一次接触这个项目的人**，目标是"读完就懂为什么这么设计、怎么玩、能学到什么"。

## 阅读顺序建议

下面这条路径从"是什么 / 为什么"开始，再到"怎么玩 / 怎么用"，最后到"怎么扩展"。建议按顺序读。

| 章节 | 一句话内容 |
|------|-----------|
| [00 - 项目介绍](00-introduction.md) | j-yc-bench 到底是什么，跟普通 LLM 评测有什么不一样 |
| [01 - 为什么要做长时程基准](01-why-long-horizon.md) | 单轮问答测不出来的能力，要靠"经营一年"才能暴露 |
| [02 - 游戏规则总览](02-game-rules.md) | CEO 这一年到底要干嘛，赢/输的判定是什么 |
| [03 - 四个领域与声望](03-four-domains-and-prestige.md) | 公司有 4 条业务线，声望决定你能接什么活 |
| [04 - 员工与隐藏技能](04-employees-hidden-skills.md) | 8 个员工怎么分配，Brooks's Law 为什么逼你不能"全员上" |
| [05 - 客户、信任与 RAT 客户](05-clients-and-trust.md) | 有的客户是金主，有的客户是"陷阱"，得自己分辨 |
| [06 - 时间、事件与工资](06-time-and-events.md) | 时间是怎么走的，工资什么时候扣，破产怎么算 |
| [07 - Agent 怎么和系统对话](07-agent-interface.md) | CLI、JSON、工具调用、scratchpad 长期记忆 |
| [08 - 核心策略张力与常见坑](08-strategy-tensions.md) | 这游戏里那些"看似聪明实则送命"的决策 |
| [09 - 怎么跑、怎么评估](09-how-to-run-and-evaluate.md) | 跑一次实验、看结果、对比 Bot 基线 |
| [10 - 如何扩展与调参](10-extending-and-tuning.md) | 改难度、写新预设、加新机制 |

### 技术方案与架构

| 章节 | 一句话内容 |
|------|-----------|
| [11 - 系统架构](11-architecture.md) | 分层设计、模块职责、核心数据流、关键类详解 |
| [12 - 数据模型与存储设计](12-data-models.md) | SQLite 表结构、DAO 层设计、ER 关系、查询模式 |

## 一句话总结

> **YC-Bench 不是问 AI "你会不会写代码"，而是问 AI "给你一家公司、$200,000、8 个员工和一年时间，你能不能不破产还赚到钱"。**

它把"长期规划、资源分配、对抗性客户识别、外部记忆维护"这些能力，揉进一个看得见摸得着的经营模拟里，让不同模型在同一套规则下分胜负。
