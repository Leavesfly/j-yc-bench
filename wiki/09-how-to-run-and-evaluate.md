# 09 · 怎么跑、怎么评估

## 环境要求

- Java 11+
- Maven

## 构建

```bash
mvn -DskipTests package
```

产出 `target/j-yc-bench.jar`（self-contained JAR）。

## 跑一次实验

```bash
java -jar target/j-yc-bench.jar run \
  --model openai/gpt-4o-mini \
  --seed 1 \
  --config default
```

### 参数说明

| 参数 | 含义 |
|------|------|
| `--model` | 模型字符串（如 `openai/gpt-4o`、`anthropic/claude-3.5-sonnet`、`openrouter/...`） |
| `--seed` | 任务市场随机种子。**员工和客户用固定 seed=1**，换 seed 只换任务 |
| `--config` | 预设名（仅 `default` 内置）或 YAML 文件路径 |
| `--horizon-years` | 仿真年数（不指定则用预设值，default 是 1） |
| `--company-name` | 公司名，仅用于显示，默认 `BenchCo` |
| `--start-date` | 仿真起始日期（ISO 格式），默认 `2025-01-01` |
| `--no-live` | 关掉终端动态仪表板 |
| `--max-episodes` | 破产后允许重启次数。默认 1（不重启） |

API key 通过环境变量读取：

```bash
export OPENAI_API_KEY="sk-..."
export ANTHROPIC_API_KEY="sk-ant-..."
export GEMINI_API_KEY="AIza..."
export OPENROUTER_API_KEY="sk-or-v1-..."
```

## 产出文件

一次 run 会产出：

```
db/{config}_{seed}_{model}.db                  ← SQLite 数据库，完整仿真状态
db/{config}_{seed}_{model}.transcript.jsonl     ← 每回合的对话和命令记录
db/{config}_{seed}_{model}.session.json         ← LLM session 消息快照（断点恢复用）
results/yc_bench_result_{config}_{seed}_{model}.json  ← 完整 rollout + 时间序列
```

`results/yc_bench_result_*.json` 是最重要的产物，顶层字段：

- `session_id`、`model`、`seed`、`horizon_years`、`total_episodes`
- `terminal`：bool，是否进入终态
- `terminal_reason`：`"bankruptcy"` / `"horizon_end"` / `"error"`
- `transcript`：每回合的 Agent 输出、命令、结果
- `time_series`：funds、prestige、trust 等随时间变化

## 断点恢复

如果 run 被中断（Ctrl+C），再次跑同样命令会**自动从断点继续**：

- 检测到 db 文件已存在且仿真非终态 → 从 session.json 恢复对话历史
- 想重新跑，**先删掉 db 文件**再起新 run

## 并行跑多个 seed

```bash
for s in 1 2 3 4 5; do
  java -jar target/j-yc-bench.jar run --model "$MODEL" --seed $s --config default &
done
wait
```

每个 run 写自己的 db 文件，不互相覆盖。

## 评估：怎么看跑得好不好

### 生死指标

先看 `terminal_reason`：

- `"horizon_end"` → 活到年底，**合格**
- `"bankruptcy"` → 中途破产，**不合格**
- `"error"` → 程序异常，**不算数**

然后看终态 `funds_cents`：在活到年底的前提下账上还剩多少。

```bash
sqlite3 db/default_1_openai_gpt-4o.db \
  "SELECT funds_cents FROM company; SELECT domain, prestige_level FROM company_prestige;"
```

### Bot 基线

仓库自带 4 个确定性 bot 基线（`scripts/bot_runner.py`）：

| Bot | 策略 |
|-----|------|
| `greedy` | 永远挑挂牌报酬最高的 |
| `random` | 在可接任务里随机挑（seeded RNG） |
| `throughput` | 挑"报酬/估算完成小时数"最高的 |
| `prestige` | 前期刷声望，后期切 throughput |

Bot 设计有意"弱"于 LLM：盲于实际技能、最多 1 个并行任务、不识别 RAT、不写 scratchpad。

**如果你的 LLM 跑不赢 greedy bot，基本是失败的**。

### 多 seed 平均

单 seed 受任务市场抽样影响大。**评估一个模型至少跑 3~5 个 seed 平均**。

### 可视化脚本

`scripts/` 目录提供画图工具：

| 脚本 | 用途 |
|------|------|
| `plot_results.py` | 单次 run 的资金/声望曲线 |
| `plot_multi_model.py` | 同 seed 下多模型对比 |
| `plot_comparison.py` | 模型 vs bot 基线对比 |
| `plot_prestige_radar.py` | 四领域声望雷达图 |

## 评估陷阱

### 1. 只看终态资金不看是否破产

先按"是否活到 horizon"分桶，再按"账上钱"排序。

### 2. 单 seed 评估

不同 seed 的任务市场差别很大——单 seed 没有统计意义。

### 3. 跨 config 直接对比

不同 config 初始资金、报酬分布不同。比较应在**同 config + 同 seed 组**下进行。

### 4. 忽略 transcript

两个模型终态资金接近，但 A 靠运气，B 靠实力识别 RAT——后者更有泛化价值。**翻一翻 transcript** 看 scratchpad 和策略调整。

## 反直觉结论

> **跑 1 个 seed 的成绩没用，但跑 1 个 seed 的过程很有用**。数值评估用多 seed 聚合，行为分析用单 seed 深读 transcript。

下一篇 → [10 - 如何扩展与调参](10-extending-and-tuning.md)。
