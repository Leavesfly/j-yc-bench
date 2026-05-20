# 00 · 项目介绍：j-yc-bench 是什么

## 一句话

**j-yc-bench 是 YC-Bench 的 Java 移植版——一个"让 LLM Agent 当一年 AI 创业公司 CEO"的基准测试。**

它不是问答题，也不是"写一个函数"那种单步任务，而是一个**会持续推进的世界**：你（Agent）一上来拿到 $200,000、8 个员工、一张 200 条左右的任务市场和 6 个客户，然后要在仿真里活满一年（仿真从 2025-01-01 开始，时长由配置控制，默认 1 年；对应几十到上百个决策回合），不能破产，最好还能赚钱、攒声望。

## 技术栈

- **Java 11** — 核心语言
- **Maven** — 构建与依赖管理
- **SQLite via sqlite-jdbc** — 数据持久化（JDBC，无 ORM）
- **Picocli** — CLI 命令行框架
- **Jackson** — JSON + YAML 序列化
- **java.net.http.HttpClient** — LLM API 调用（OpenAI / Anthropic / Gemini / OpenRouter）

## 它长什么样

整个交互过程对 Agent 来说就是一个命令行（下面是**简化示意**，字段名以实际 CLI 返回为准）：

```bash
$ java -jar j-yc-bench.jar company status
# 返回类似：
# {
#   "company_name": "BenchCo",
#   "funds_cents": 15000000,
#   "monthly_payroll_cents": 3000000,
#   "runway_months": 5.0,
#   "prestige": {"research": 3.5, "inference": 2.1, "data_environment": 1.0, "training": 4.2}
# }

$ java -jar j-yc-bench.jar market browse --domain training
# 返回若干个任务，每个有 id (形如 "Task-17")、title、required_prestige、reward_funds_cents 等

$ java -jar j-yc-bench.jar task accept --task-id Task-17
# 返回 accept 结果与计算出的 deadline
```

Agent 通过工具调用执行这些命令、读 JSON 结果、做决策、再决定何时调用 `sim resume` 让时间往前走。**所有交互都是 JSON 进、JSON 出**，没有自由文本解析的歧义。

## 它在测什么

跟"让 GPT 写一段代码"或者"让 Claude 解一道数学题"不一样，j-yc-bench 测的是这些**只有长时间运行才会暴露的能力**：

- **长期规划**：今天接的任务，可能几十天后才到截止日期；你今天选错员工，下个月才看到工资爆表。
- **资源分配**：员工同时做 3 个任务，每个就只剩 1/3 速度；同一任务超过 4 人，第 5 人开始零贡献（Brooks's Law）。聚焦还是并行？
- **对抗性识别**：约 35% 的客户是"RAT 客户"，接他们的活会被偷偷膨胀工作量，导致必定延期。客户的忠诚度对 Agent 是**隐藏**的，Agent 要能从失败模式里识别出"这家伙不能再合作了"。
- **外部记忆**：对话历史只保留最近 20 轮，超出的会被截断。Agent 必须主动往 `scratchpad`（持久备忘录）里写笔记，否则就会忘掉早期发现。
- **稳健现金流管理**：每月初要扣工资；如果工资发完资金变负就破产，游戏结束。

## 它和"普通 Agent 评测"有什么不同

| 普通 LLM 评测 | j-yc-bench |
|---------------|------------|
| 一道题做完就结束 | 几十到上百个回合连续滚动 |
| 每题独立、互不影响 | 早期决策深刻影响后期局势 |
| 信息基本都给你 | 部分关键信息（客户忠诚度、scope creep）是隐藏的 |
| 没有时间压力 | 截止日期、月度工资、跑道都在走表 |
| 错了重来 | 错了就是错了，破产直接结束 |
| 主要测"知识 / 推理" | 主要测"持续决策与执行一致性" |

## 它是确定性的

只要给定相同的 `--seed`，世界生成出来的员工、客户、任务、事件顺序就是完全一样的。这让"同一道题给不同模型做"成为可能——模型 A 和模型 B 在完全相同的世界里各自经营一年，最后比比谁活得更好。

> 注：员工和客户用一个**固定的 world seed = 1** 生成（代码里写死的 `FIXED_WORLD_SEED`），所以同一批人/同一批客户会出现在所有 run seed 上；变化的只是任务生成（任务用 `--seed` 指定的 run seed）。这是为了让跨种子的对比更公平——你不会因为"这次随机到的员工特别强"而占便宜。

## 代码结构

```
src/main/java/com/collinear/ycbench/
├── cli/        — Picocli 命令树
├── agent/      — LLM 运行时 + Agent 循环
├── core/       — 仿真引擎 + 事件处理器
├── db/         — JDBC 连接 + schema + DAO
├── services/   — RNG + 世界生成
├── config/     — YAML schema + 加载器
└── runner/     — 基准编排
src/main/resources/
├── presets/default.yaml
└── logback.xml
```

## 接下来读哪一篇

- 想先知道"为什么非得是一年这么长" → [01 - 为什么要做长时程基准](01-why-long-horizon.md)
- 想直接了解游戏规则 → [02 - 游戏规则总览](02-game-rules.md)
- 想直接上手跑 → [09 - 怎么跑、怎么评估](09-how-to-run-and-evaluate.md)
