# j-yc-bench

[YC-Bench](https://github.com/collinear-ai/yc-bench) 的 Java 移植版：一个面向 LLM Agent 的**长时程确定性基准测试**——让 Agent 扮演 AI 创业公司 CEO，在仿真世界里经营一整年。

> **核心问题**：给你一家公司、$200,000、8 个员工和一年时间，你能不能不破产还赚到钱？

## 它在测什么

与传统"一问一答"的 LLM 评测不同，j-yc-bench 测试的是**长期执行一致性**：

- **长期规划** — 今天的决策影响几个月后的结果
- **资源分配** — 8 个员工、4 个领域、Brooks's Law 限制
- **对抗性识别** — 35% 客户是隐藏的"RAT"陷阱客户
- **外部记忆维护** — 对话历史仅保留 20 轮，必须用 scratchpad
- **现金流管理** — 每月扣工资，资金为负立刻破产

## 技术栈

| 组件 | 技术选型 | 版本 |
|------|---------|------|
| 语言 | Java | 11 |
| 构建 | Maven + Shade Plugin | — |
| 数据库 | SQLite via `sqlite-jdbc` | 3.46.1.0 |
| CLI | Picocli | 4.7.6 |
| 序列化 | Jackson (JSON + YAML + JSR310) | 2.17.2 |
| LLM 调用 | `java.net.http.HttpClient` | JDK 内置 |
| 日志 | SLF4J + Logback | 2.0.13 / 1.4.14 |
| 图表 | JFreeChart | 1.5.5 |
| 测试 | JUnit 5 | 5.10.2 |

## 快速开始

### 环境要求

- JDK 11+
- Maven 3.6+
- [Ollama](https://ollama.com)（默认本地 LLM 运行时，开箱即用）

### 构建

```bash
mvn -DskipTests package
```

产出 `target/j-yc-bench.jar`（self-contained fat JAR，含所有依赖）。

### 30 秒体验（无需 LLM）

```bash
# 构建后直接运行演示 — 4 种 Bot 策略对比
./scripts/demo.sh
```

输出示例：
```
Bot              Config       Seed   Final Balance   OK  Fail  Prestige
----------------------------------------------------------------------
greedy_bot       default      1           $621,531   63    13      5.0
random_bot       default      1           $387,144   75    11      3.0
throughput_bot   default      1           $376,380   58    10      2.7
prestige_bot     default      1           $216,453   55    16      3.7

Bankruptcies: 0/4
```

### 🧠 LLM Agent 可视化演示

直观看到 LLM 如何做出经营决策（需 Ollama）：

```bash
# 单回合决策演示 — 看 AI 如何分析市场并选择任务
bash scripts/llm_single_turn.sh

# 多回合可视化 — 带彩色终端的完整经营演示（Python 3.7+）
python3 scripts/llm_demo.py --turns 3

# 使用其他模型
python3 scripts/llm_demo.py --model qwen3.5:32b --turns 5
```

### 开箱即用（Ollama 本地模型）

项目默认使用 Ollama 本地模型 `qwen3.5:4b`，**无需 API Key**，只需安装并启动 Ollama：

```bash
# 1. 安装 Ollama（如已安装则跳过）
# macOS: brew install ollama
# Linux: curl -fsSL https://ollama.com/install.sh | sh

# 2. 拉取默认模型
ollama pull qwen3.5:4b

# 3. 启动 Ollama 服务（如未运行）
ollama serve

# 4. 直接运行基准测试 — 无需任何额外配置！
java -jar target/j-yc-bench.jar run --seed 1
```

### 使用云端 API（可选）

如需使用 OpenAI、Anthropic 等云端模型，在项目根目录创建 `.env` 文件：

```bash
# .env
OPENAI_API_KEY=sk-...
ANTHROPIC_API_KEY=sk-ant-...
GEMINI_API_KEY=AIza...
OPENROUTER_API_KEY=sk-or-v1-...
```

然后通过 `--model` 指定模型：

```bash
java -jar target/j-yc-bench.jar run \
  --model openai/gpt-4o-mini \
  --seed 1 \
  --config default
```

### 运行参数

| 参数 | 必需 | 默认值 | 说明 |
|------|------|--------|------|
| `--model` | | `ollama/qwen3.5:4b` | LLM 模型标识符（如 `ollama/qwen3.5:4b`、`openai/gpt-4o`、`anthropic/claude-3-5-sonnet`） |
| `--seed` | | `1` | 任务市场随机种子（员工和客户始终用固定 seed=1） |
| `--config` | | `default` | 预设名（内置 `default`）或 `.yaml` 文件路径 |
| `--horizon-years` | | 1 | 仿真时长（年） |
| `--company-name` | | `BenchCo` | 公司名（仅显示用） |
| `--start-date` | | `2025-01-01` | 仿真起始日期 |
| `--max-episodes` | | 1 | 破产后重启次数（scratchpad 跨 episode 保留） |
| `--no-live` | | false | 禁用实时输出 |

### 支持的模型 Provider

| Provider | 模型前缀 | 环境变量 | 说明 |
|----------|---------|---------|------|
| **Ollama（默认）** | `ollama/*` | 无需 | 本地运行，开箱即用 |
| OpenAI | `openai/*` | `OPENAI_API_KEY` | |
| Anthropic | `anthropic/*` | `ANTHROPIC_API_KEY` | |
| Google Gemini | `gemini/*` | `GEMINI_API_KEY` | |
| OpenRouter | `openrouter/*` | `OPENROUTER_API_KEY` | |

## 输出文件

每次 run 产出：

```
db/{config}_{seed}_{model}.db                  — SQLite 完整仿真状态（可审计）
db/{config}_{seed}_{model}.transcript.jsonl     — 每回合对话和命令记录
db/{config}_{seed}_{model}.session.json         — LLM 会话快照（断点恢复用）
results/yc_bench_result_{config}_{seed}_{model}.json — 完整 rollout + 时间序列
```

### 断点恢复

如果 run 被中断（Ctrl+C），再次执行相同命令会**自动从断点继续**。想重新跑需先删除对应 db 文件。

### 并行跑多个 seed

```bash
for s in 1 2 3 4 5; do
  java -jar target/j-yc-bench.jar run --model "$MODEL" --seed $s --config default &
done
wait
```

## CLI 命令一览

除了 `run` 命令，j-yc-bench 还提供 9 组仿真命令供 Agent 使用（也可手动调试）：

| 命令组 | 主要子命令 | 用途 |
|--------|-----------|------|
| `sim` | `init`, `resume` | 初始化世界、推进时间 |
| `company` | `status` | 查看公司状态（资金、声望、跑道） |
| `employee` | `list` | 查看员工列表及技能速率 |
| `market` | `browse` | 浏览可接任务 |
| `task` | `accept`, `assign`, `dispatch`, `list`, `inspect`, `cancel` | 任务全生命周期管理 |
| `client` | `list`, `history` | 查看客户信息及历史 |
| `finance` | `ledger` | 查看财务流水 |
| `report` | — | 汇总报告 |
| `scratchpad` | `read`, `write`, `append`, `clear` | Agent 长期记忆 |

查看全部命令：`java -jar target/j-yc-bench.jar --help`

## 项目结构

```
j-yc-bench/
├── pom.xml                          — Maven 构建配置
├── src/main/java/com/collinear/ycbench/
│   ├── cli/                         — Picocli 命令树
│   │   ├── Main.java               — 入口点 + 子命令注册
│   │   ├── CliContext.java          — 命令执行上下文
│   │   ├── JsonOutput.java          — JSON 输出工具
│   │   ├── DotEnv.java             — .env 文件加载
│   │   └── commands/               — 9 个命令组 + run 命令
│   │       ├── SimCmd.java
│   │       ├── CompanyCmd.java
│   │       ├── EmployeeCmd.java
│   │       ├── MarketCmd.java
│   │       ├── TaskCmd.java
│   │       ├── ClientCmd.java
│   │       ├── FinanceCmd.java
│   │       ├── ReportCmd.java
│   │       ├── ScratchpadCmd.java
│   │       └── RunCmd.java
│   ├── agent/                       — Agent 循环层
│   │   ├── AgentLoop.java           — 主循环驱动
│   │   ├── Prompt.java              — 系统 prompt + 回合上下文构建
│   │   ├── RunState.java            — 运行状态管理
│   │   ├── CommandExecutor.java     — 进程内命令执行（无需启动新 JVM）
│   │   ├── StateSnapshot.java       — 每回合状态快照
│   │   └── runtime/                 — LLM 调用运行时
│   │       ├── AgentRuntime.java    — 接口定义
│   │       ├── HttpLlmRuntime.java  — HTTP 客户端实现
│   │       ├── RuntimeSettings.java — 运行时参数
│   │       ├── TurnRequest.java     — 请求模型
│   │       └── TurnResult.java      — 响应模型
│   ├── core/                        — 离散事件仿真引擎
│   │   ├── Engine.java              — 时间推进 + 工资扣除 + 破产检查
│   │   ├── Progress.java            — 任务进度刷新
│   │   ├── Eta.java                 — ETA 重算（含 Brooks's Law）
│   │   ├── BusinessTime.java        — 工作日/工作时间计算
│   │   ├── EventOps.java            — 事件队列操作
│   │   ├── AdvanceResult.java       — 推进结果
│   │   └── handlers/                — 事件处理器
│   │       ├── TaskCompleteHandler.java  — 任务完成（声望/信任/工资联动）
│   │       ├── TaskHalfHandler.java      — 进度里程碑
│   │       ├── BankruptcyHandler.java    — 破产处理
│   │       └── HorizonEndHandler.java    — 时间到期
│   ├── services/                    — 世界生成
│   │   ├── SeedWorld.java           — 种子化入口
│   │   ├── GenerateEmployees.java   — 员工生成
│   │   ├── GenerateClients.java     — 客户生成（含 RAT 标记）
│   │   ├── GenerateTasks.java       — 任务市场生成
│   │   ├── RngStreams.java          — 随机流管理
│   │   └── RngHelpers.java          — 采样工具
│   ├── config/                      — 配置系统
│   │   ├── ConfigLoader.java        — YAML 加载 + 参数派生
│   │   ├── ExperimentConfig.java    — 顶层配置
│   │   ├── WorldConfig.java         — 世界参数
│   │   ├── SimConfig.java           — 仿真参数
│   │   ├── LoopConfig.java          — 循环参数
│   │   ├── AgentConfig.java         — Agent 参数
│   │   ├── SalaryTierConfig.java    — 薪资等级配置
│   │   ├── WorldDists.java          — 分布参数
│   │   ├── DistSpec.java            — 分布规格定义
│   │   ├── Sampling.java            — 分布采样
│   │   └── PyRandom.java            — Python MT19937 精确复刻
│   ├── runner/                      — 基准编排
│   │   ├── RunCmdMain.java          — 运行入口（episode 循环 + 结果写入）
│   │   ├── RunArgs.java             — 运行参数
│   │   └── Extract.java             — 结果提取 + 时间序列
│   ├── db/                          — 数据持久化
│   │   ├── Database.java            — SQLite 连接工厂
│   │   ├── JdbcUtils.java           — JDBC 工具类
│   │   ├── dao/                     — 10 个 DAO
│   │   │   ├── CompanyDao.java
│   │   │   ├── EmployeeDao.java
│   │   │   ├── TaskDao.java
│   │   │   ├── ClientDao.java
│   │   │   ├── EventDao.java
│   │   │   ├── LedgerDao.java
│   │   │   ├── SimStateDao.java
│   │   │   ├── ScratchpadDao.java
│   │   │   ├── SessionDao.java
│   │   │   └── MonthlyMetricDao.java
│   │   └── model/                   — 19 个数据模型
│   │       ├── Company.java, CompanyPrestige.java
│   │       ├── Employee.java, EmployeeSkillRate.java
│   │       ├── Task.java, TaskStatus.java, TaskRequirement.java, TaskAssignment.java
│   │       ├── Client.java, ClientTrust.java
│   │       ├── SimEvent.java, EventType.java, SimState.java
│   │       ├── LedgerEntry.java, LedgerCategory.java
│   │       ├── Scratchpad.java, SessionRow.java, MonthlyMetric.java
│   │       └── Domain.java
│   ├── plots/                       — JFreeChart 可视化
│   └── scripts/                     — 辅助脚本
├── src/main/resources/
│   ├── presets/default.yaml         — 默认预设配置
│   ├── schema.sql                   — SQLite DDL（幂等）
│   └── logback.xml                  — 日志配置
├── scripts/
│   ├── diff_python_java.py          — Python/Java 对比验证
│   └── diff_python_java.sh
└── wiki/                            — 项目 Wiki 文档
```

## 确定性保证

给定相同的 `--seed`：

- **员工和客户**始终用固定 `world_seed = 1` 生成 → 跨所有 run seed 一致
- **任务市场**用 `--seed` 指定的值生成 → 同 seed 完全相同
- **`PyRandom`** 类精确复刻 Python 的 Mersenne Twister（MT19937）→ Java 版和 Python 版对同一 seed 生成一致的世界

这意味着不同模型在**完全相同的环境**下接受评估，分数差异来自能力差异而非随机性。

## 评估指标

### 生死线

| 结局 | `terminal_reason` | 含义 |
|------|-------------------|------|
| 活到年底 | `horizon_end` | ✅ 合格 |
| 中途破产 | `bankruptcy` | ❌ 不合格 |
| 程序异常 | `error` | ⚠️ 不计分 |

### 核心指标

1. **是否存活** — 先按"活到 horizon"分桶
2. **终态资金** — 在活下来的前提下，账上还剩多少
3. **声望分布** — 四个领域最终声望
4. **RAT 识别率** — 是否成功避开了 RAT 客户

### Bot 基线

| Bot | 策略 | 用途 |
|-----|------|------|
| `greedy` | 总是接最贵的 | 下限（跑不赢它 = 失败） |
| `random` | 随机接 | 噪声基线 |
| `throughput` | 挑性价比最高的 | 中等基线 |
| `prestige` | 前期刷声望后期切收益 | 策略基线 |

## 配置预设

默认预设位于 `src/main/resources/presets/default.yaml`。默认使用 Ollama 本地模型 `qwen3.5:4b`（`http://localhost:11434/v1`），无需配置任何 API Key 即可运行。

关键参数：

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `agent.model` | `ollama/qwen3.5:4b` | 默认 LLM 模型 |
| `agent.base_url` | `http://localhost:11434/v1` | Ollama 服务地址 |
| `initial_funds_cents` | 20,000,000 | 启动资金（$200K） |
| `num_employees` | 8 | 员工数量 |
| `num_clients` | 6 | 客户数量 |
| `num_market_tasks` | 200 | 市场任务总数 |
| `loyalty_rat_fraction` | 0.35 | RAT 客户比例 |
| `salary_bump_pct` | 0.01 | 每次成功完成的加薪比例 |
| `trust_work_reduction_max` | 0.50 | 最大信任工作量削减 |
| `prestige_decay_per_day` | 0.0 | 声望被动衰减（默认关闭） |
| `history_keep_rounds` | 20 | Agent 对话历史保留轮数 |
| `auto_advance_after_turns` | 5 | 强制 resume 阈值 |

自定义预设：复制 `default.yaml` 修改后用 `--config /path/to/custom.yaml` 指定。

## 文档

- **Wiki**（面向新人，通俗讲解）：[`wiki/`](wiki/)
- **系统设计**（面向开发者，架构细节）：[`../system_design/`](../system_design/)

## License

同 [YC-Bench](https://github.com/collinear-ai/yc-bench) 上游项目。
