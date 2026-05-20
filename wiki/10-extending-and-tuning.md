# 10 · 如何扩展与调参

j-yc-bench 把所有"可调参数"都收在 YAML 配置文件里（`src/main/resources/presets/default.yaml`），用 Jackson 加载和校验。

> **改难度、试新机制不用动 Java 代码**，写一份新 YAML 文件就够了。

## 第 1 层：调几个数字

复制 `src/main/resources/presets/default.yaml` 改几个数字，然后用 `--config /path/to/your.yaml` 跑。

**调节难度最敏感的旋钮**：

| 想做什么 | 调哪个 |
|---------|--------|
| 让钱更紧 | `initial_funds_cents`（default 20000000 = $200K） |
| 让工资压力更大 | `salary_bump_pct`（default 0.01 = 1%） |
| 让任务工作量更大 | 调大 `required_qty` 的 high/mode |
| 让任务报酬更小 | 调小 `reward_funds_cents` 分布 |
| 让 RAT 更多/更狠 | `loyalty_rat_fraction`（default 0.35） |
| 让信任更难涨 | `trust_build_rate`（default 5，调大更难） |
| 让信任更脆 | `trust_fragility`（default 0.3，调大更脆） |
| 让声望天花板更低 | `prestige_max`（default 10.0） |
| 让员工更弱 | 调小 tier 的 `rate_max` |
| 给任务加里程碑 | `task_progress_milestones: [0.25, 0.5, 0.75]` |
| 启用被动声望衰减 | `prestige_decay_per_day: 0.01` |
| 启用多领域任务 | `domain_count: {type: triangular, low: 1, high: 3, mode: 1}` |

## 第 2 层：写难度梯度

```yaml
# easy.yaml: 钱多 + 工资涨得慢 + RAT 少
world:
  initial_funds_cents: 50000000  # $500K
  salary_bump_pct: 0.005         # 0.5%
  loyalty_rat_fraction: 0.15
```

```yaml
# nightmare.yaml: 钱少 + 工资涨快 + RAT 多 + 被动衰减
world:
  initial_funds_cents: 10000000  # $100K
  salary_bump_pct: 0.02          # 2%
  loyalty_rat_fraction: 0.50
  prestige_decay_per_day: 0.02
  trust_fragility: 0.6
```

## 第 3 层：换分布族

配置里的分布参数支持不同的分布族：

| 族 | 字段 | 用途 |
|---|------|------|
| `constant` | `value` | 固定值，消融实验用 |
| `uniform` | `low`, `high` | 均匀分布 |
| `triangular` | `low`, `high`, `mode` | 三角分布，最常用 |
| `normal` | `mean`, `stdev`, `low`, `high` | 截断正态 |
| `beta` | `alpha`, `beta`, `scale`, `low`, `high` | Beta 分布 |

示例——把 `required_prestige` 改成固定值：

```yaml
world:
  dist:
    required_prestige:
      type: constant
      value: 3
```

## 第 4 层：改 system prompt

在自定义预设里覆盖 `agent.system_prompt`：

```yaml
agent:
  system_prompt: |
    You are a cautious CEO. Prefer small teams (≤2 employees per task).
    Never accept tasks with reward > $8,000 in the first 30 days.
```

留空则用内置 prompt。改 prompt 主要用于**对照实验**。

## 第 5 层：加新命令/机制（需要写 Java 代码）

### 加一个新 CLI 命令

1. 在 `com.collinear.ycbench.cli` 包里合适的命令类上加一个 `@Command` 方法（Picocli）。
2. 处理逻辑，用 Jackson 序列化返回 JSON。
3. 如果是全新命令组，新建类并在主命令里注册。
4. **记得在 system prompt 里告诉 Agent 有这个命令**。

### 加一类新事件

1. 在 `EventType` 枚举里加值（如 `MARKET_REPLENISH`）。
2. 在 `core/handlers/` 下写 handler。
3. 在 `Engine` 的事件分发里加分支调用 handler。
4. 在合适的地方排入事件队列。

### 加员工/客户新属性

1. 在对应的 DAO/Model 里加字段和 SQL 列。
2. 在世界生成 Service 里初始化。
3. 在 CLI 命令返回里加进 JSON。
4. **改 schema 后需要清空旧 db**（`rm db/*.db`）。

## 调参注意事项

### 1. 改完清旧 db

runner 检测到已有 db 文件会接着跑（断点恢复），可能用的还是旧配置的世界。要么删 db，要么换 seed。

### 2. 派生参数别直接设

部分参数（如 `trust_fail_penalty`、`trust_decay_per_day`、`scope_creep_max`）是从"直觉旋钮"自动派生的，直接设会被覆盖。

### 3. salary tier shares 必须和为 1.0

```yaml
salary_junior:
  share: 0.50
salary_mid:
  share: 0.35
salary_senior:
  share: 0.15
# 加起来必须是 1.0
```

### 4. 改 prompt 时小心删核心提示

"always have active tasks running"、"use scratchpad" 这两条是保命提示——删了之后模型会被反复绊倒。

### 5. 想跑得快

把 `required_qty` 分布调小 → 总事件数变少 → 一次 run 时长显著缩短。适合快速迭代。

## 反直觉结论

> 调参时最常见的错误是**改了一个旋钮就指望难度线性变化**。但所有机制互相耦合：调大 `salary_bump_pct` 不只让工资涨快，还会逼出更好的策略让最终成绩更高。
>
> 调完一个 preset 后，**先跑 bot 基线看看**——如果连 greedy bot 都活不到年底，说明太硬了；如果 random bot 都能赚钱，说明太软了。Bot 是预设的"压力测试器"。

## 下一步可以做什么

- 把一年延长到 3 年（`horizon_years: 3`），观察复利效应。
- 加"竞争对手"事件：每月有概率某个高 prestige 任务被"别人"抢走。
- 实现 `loyalty_reveal_trust`：trust 达到阈值后在 CLI 里揭示客户真实 loyalty。
- 加"实习生招聘"：每季度可从候选池招 1 人，但只能看到 tier 看不到 skill_rates。

---

到这里 Wiki 就结束了。希望这一系列文档帮你理解了 j-yc-bench 为什么是这个样子、它在测什么、以及怎么用它。

> 想回头复习哪个部分，从 [README](README.md) 看目录跳转。
