# 11 · 系统架构

## 整体分层

j-yc-bench 采用经典的**分层架构**，从上到下依次是：

```
┌─────────────────────────────────────────────────┐
│  runner/        基准编排层（入口、生命周期管理）       │
├─────────────────────────────────────────────────┤
│  agent/         Agent 循环层（LLM 交互、工具执行）    │
├─────────────────────────────────────────────────┤
│  cli/           CLI 命令层（Picocli、JSON 输出）     │
├─────────────────────────────────────────────────┤
│  core/          仿真引擎层（离散事件、时间推进）       │
├─────────────────────────────────────────────────┤
│  services/      世界生成层（员工、客户、任务）         │
├─────────────────────────────────────────────────┤
│  config/        配置层（YAML 预设、分布采样）          │
├─────────────────────────────────────────────────┤
│  db/            数据持久化层（SQLite JDBC、DAO）      │
└─────────────────────────────────────────────────┘
```

## 模块职责

| 模块 | 包路径 | 核心职责 |
|------|--------|---------|
| **runner** | `com.collinear.ycbench.runner` | 基准测试入口，管理 episode 循环、世界初始化、结果提取 |
| **agent** | `com.collinear.ycbench.agent` | Agent 循环驱动、Prompt 构建、命令执行、状态快照 |
| **agent/runtime** | `com.collinear.ycbench.agent.runtime` | LLM HTTP 调用（OpenAI/Anthropic/Gemini/OpenRouter） |
| **cli** | `com.collinear.ycbench.cli` | Picocli 命令注册、9 个命令组、JSON 序列化输出 |
| **core** | `com.collinear.ycbench.core` | 离散事件引擎、时间推进、ETA 计算、进度刷新 |
| **core/handlers** | `com.collinear.ycbench.core.handlers` | 事件处理器：任务完成、任务半程、破产、时间到期 |
| **services** | `com.collinear.ycbench.services` | 世界生成：员工、客户、任务市场；RNG 流 |
| **config** | `com.collinear.ycbench.config` | YAML 配置加载、分布采样、参数派生 |
| **db** | `com.collinear.ycbench.db` | SQLite 连接管理、Schema 初始化 |
| **db/dao** | `com.collinear.ycbench.db.dao` | 10 个 DAO：Company/Employee/Task/Client/Event/Ledger/... |
| **db/model** | `com.collinear.ycbench.db.model` | 19 个数据模型（POJO），映射 SQLite 表 |

## 核心数据流

```
用户执行: java -jar j-yc-bench.jar run --model openai/gpt-4o --seed 1

  RunCmdMain.run()
    │
    ├─ 1. ConfigLoader 加载 YAML 预设 → WorldConfig
    ├─ 2. Database.open() 创建/打开 SQLite
    ├─ 3. SeedWorld.seed() 生成员工+客户+任务+初始事件
    ├─ 4. HttpLlmRuntime 初始化（model、API key、temperature）
    ├─ 5. AgentLoop.run() 启动 Agent 循环
    │     │
    │     ├─ Prompt.buildTurnContext() 构建当前回合上下文
    │     ├─ runtime.executeTurn() 调用 LLM → 获取工具调用
    │     ├─ CommandExecutor.execute() 执行 CLI 命令
    │     │     └─ cli/commands/* 处理命令 → 读写 db → 返回 JSON
    │     ├─ 如果命令是 "sim resume":
    │     │     └─ Engine.advanceTime() 推进仿真时间
    │     │           ├─ applyPayroll() 扣工资
    │     │           ├─ Progress.flush() 更新任务进度
    │     │           ├─ Eta.recalculate() 重算完成时间
    │     │           └─ handlers/* 处理触发的事件
    │     └─ 循环直到 terminal=true
    │
    └─ 6. Extract.fullRollout() 提取结果 JSON
```

## 关键类详解

### `Engine`（核心引擎）

位于 `com.collinear.ycbench.core.Engine`，是离散事件仿真的核心。

**主要方法：**
- `applyPayroll(db, companyId, time)` — 扣全员工资，检查破产
- `advanceTime(db, companyId, config, targetTime)` — 推进时间到下一个事件

**推进逻辑：**
1. 计算 `nextPayroll`（下月第一个工作日 09:00）
2. 计算 `nextEvent`（事件队列里最早的）
3. 取 `min(nextPayroll, nextEvent, targetTime)`
4. 平局优先级：`payroll < event < target`
5. 执行对应动作（扣工资 / 处理事件 / 到达目标时间）

### `AgentLoop`（Agent 循环）

位于 `com.collinear.ycbench.agent.AgentLoop`。

**循环流程：**
1. 构建 `StateSnapshot`（从 db 读取公司状态、活跃任务、scratchpad）
2. 调用 `Prompt.buildTurnContext()` 生成用户消息
3. 委托 `AgentRuntime.executeTurn()` 获取 LLM 回复
4. 解析工具调用，通过 `CommandExecutor` 执行
5. 如果连续 `autoAdvanceAfterTurns`（默认 5）轮没有 `sim resume`，强制执行一次
6. 检查终态条件（`BANKRUPTCY`、`HORIZON_END`、`maxTurns`）

### `HttpLlmRuntime`（LLM 运行时）

位于 `com.collinear.ycbench.agent.runtime.HttpLlmRuntime`，使用 `java.net.http.HttpClient`。

**支持的 provider：**
- OpenAI（`openai/*`）— Chat Completions API
- Anthropic（`anthropic/*`）— Messages API
- Gemini（`gemini/*`）— GenerateContent API
- OpenRouter（`openrouter/*`）— OpenAI 兼容格式

**核心行为：**
- 维护消息历史（system + user/assistant 交替）
- 截断历史到 `history_keep_rounds`（默认 20）
- 解析工具调用（function calling / tool_use）
- 支持流式和非流式响应

### `CommandExecutor`（命令执行器）

位于 `com.collinear.ycbench.agent.CommandExecutor`。

Agent 的工具调用会被转换为 CLI 命令字符串，`CommandExecutor` 解析并路由到对应的 Picocli 命令。执行过程不启动新进程，而是**进程内直接调用**命令类方法，避免 JVM 启动开销。

## 数据持久化

### SQLite Schema

所有仿真状态存储在单一 SQLite 文件中。核心表：

| 表 | 对应 Model | 用途 |
|---|---|---|
| `company` | `Company` | 公司基本信息（funds_cents） |
| `company_prestige` | `CompanyPrestige` | 4 个领域的声望值 |
| `employee` | `Employee` | 员工基础属性 |
| `employee_skill_rate` | `EmployeeSkillRate` | 员工在每个领域的速率 |
| `task` | `Task` | 任务状态、截止日期、报酬 |
| `task_requirement` | `TaskRequirement` | 任务在每个领域的工作量要求 |
| `task_assignment` | `TaskAssignment` | 员工-任务指派关系 |
| `client` | `Client` | 客户属性（含隐藏 loyalty） |
| `client_trust` | `ClientTrust` | 信任值 |
| `sim_event` | `SimEvent` | 事件队列 |
| `sim_state` | `SimState` | 仿真全局状态（当前时间等） |
| `ledger_entry` | `LedgerEntry` | 财务流水 |
| `scratchpad` | `Scratchpad` | Agent 长期备忘录 |
| `session_row` | `SessionRow` | LLM 对话快照（断点恢复） |
| `monthly_metric` | `MonthlyMetric` | 月度汇总指标 |

### DAO 层

10 个 DAO 类全部使用**原生 JDBC**（无 ORM），通过 `JdbcUtils` 工具类简化 PreparedStatement 操作。所有 DAO 方法接收 `Connection` 参数，不自行管理事务——事务由调用方（Engine/Handler）统一管理。

## 配置系统

### 配置层次

```
ExperimentConfig（顶层）
├── WorldConfig         — 世界参数（资金、员工、客户、声望、信任...）
│   ├── SalaryTierConfig × 3  — junior/mid/senior 薪资和速率区间
│   └── WorldDists      — 分布参数（required_qty、reward、prestige...）
├── SimConfig           — 仿真参数（horizon_years、start_date）
├── LoopConfig          — 循环参数（auto_advance_after_turns、max_turns）
└── AgentConfig         — Agent 参数（temperature、history_keep_rounds）
```

### 加载流程

`ConfigLoader` 负责：
1. 先尝试从 `classpath:presets/{name}.yaml` 加载
2. 找不到则把 `name` 当文件路径加载
3. 用 Jackson YAML 反序列化为 `ExperimentConfig`
4. 执行参数派生（trust 相关参数从直觉旋钮自动计算）

### 随机数系统

`PyRandom` 类精确复制了 Python 的 Mersenne Twister 实现（包括相同的种子初始化和 twist 算法），确保给定相同 seed 时 Java 版和 Python 版生成完全一致的世界。

`RngStreams` 管理多个独立的随机流（world stream、task stream），避免不同组件的随机抽样互相干扰。

## 下一篇

→ [12 - 数据模型与存储设计](12-data-models.md)
