# 07 · Agent 怎么和系统对话

## 接口只有一个：`java -jar j-yc-bench.jar` CLI

j-yc-bench 的设计哲学是"**所有交互都是命令行 + JSON**"。Agent 唯一的工具就是一个叫 `run_command` 的函数调用，参数就是一条 shell 命令字符串。它会执行这条命令，把 stdout 原样返回。

- **不需要为不同模型写不同的 prompt 模板**：所有模型走完全一样的命令接口。
- **不需要为不同模型解析不同的输出**：所有命令都返回 JSON。
- **可审计**：Agent 调了什么命令、拿到了什么 JSON，全在 transcript 里能查。

## CLI 命令分组

| 命令组 | 主要命令 | 用途 |
|--------|---------|------|
| `sim` | `init`、`resume` | 初始化世界、推进时间 |
| `company` | `status` | 看公司当前状态（钱、声望、跑道） |
| `employee` | `list` | 看员工和他们的领域速率 |
| `market` | `browse` | 看市场上的可接任务 |
| `task` | `accept`、`assign`、`assign-all`、`dispatch`、`list`、`inspect`、`cancel` | 任务全生命周期 |
| `client` | `list`、`history` | 看客户和他们的成功/失败历史 |
| `finance` | `ledger` | 看账本 |
| `report` | （汇总报告） | 看汇总数据 |
| `scratchpad` | `read`、`write`、`append`、`clear` | 长期备忘录 |

## 标准回合流程

```bash
java -jar j-yc-bench.jar market browse
java -jar j-yc-bench.jar task accept --task-id Task-42
java -jar j-yc-bench.jar employee list
java -jar j-yc-bench.jar task assign --task-id Task-42 --employees Emp_1,Emp_4,Emp_7
java -jar j-yc-bench.jar task dispatch --task-id Task-42
java -jar j-yc-bench.jar sim resume
```

`sim resume` 返回的 JSON 里有 `wake_events` 数组，告诉 Agent 推进期间发生了什么。

## 任务状态机

```
MARKET --accept--> PLANNED --assign--> PLANNED --dispatch--> ACTIVE
ACTIVE --(完成)--> COMPLETED_SUCCESS / COMPLETED_FAIL
ACTIVE --(取消)--> CANCELLED
```

漏了任何一步下一步都会拒绝。没 active 任务时 `sim resume` 也会报错。

## Scratchpad：对抗遗忘

对话历史**只保留最近 20 轮**（`history_keep_rounds = 20`）。scratchpad 存在数据库里，**每回合注入系统 prompt 顶部**，不受截断影响。

```bash
java -jar j-yc-bench.jar scratchpad read
java -jar j-yc-bench.jar scratchpad write --content "..."
java -jar j-yc-bench.jar scratchpad append --content "..."
java -jar j-yc-bench.jar scratchpad clear
```

跨 episode 时 scratchpad 会被保留，让 Agent 在多次失败里积累经验。

## 每回合 Agent 看到的 prompt 结构

```text
## Turn 12 — Simulation State
- Current time: 2025-03-15T14:30:00
- Funds: $87,540.00 | Monthly payroll: $24,300.00 | Runway: ~3.6 months
- Active tasks: 2 | Planned tasks: 0
- Memory: 8 turns before oldest messages start dropping...

### Events since last turn:
- Task-23 (Vertex Labs): SUCCESS +$15,000 [ahead by 12h]
- Task-31: 50% progress reached
```

## 模型接入

```bash
java -jar j-yc-bench.jar run --model openai/gpt-4o-mini --seed 1 --config default
```

API key 通过环境变量：`OPENAI_API_KEY`、`ANTHROPIC_API_KEY`、`GEMINI_API_KEY`、`OPENROUTER_API_KEY`。

`temperature` 默认 0.0，尽可能让结果确定。

## 反直觉结论

> scratchpad 内容**每回合都被重新喂进 LLM**，吃 token。把它当**精炼决策手册**用（"`Cipher Corp` = RAT，永不接"），不要当流水账日志。

下一篇 → [08 - 核心策略张力与常见坑](08-strategy-tensions.md)。
