# 06 · 时间、事件与工资

## 时间不是"按回合走"，是按"下一件事"走

很多回合制游戏的时间是"每回合 = 1 天"。j-yc-bench 不是。这里时间是**离散事件驱动**的：

> 调用 `sim resume` → 引擎找出"下一件该发生的事"是什么 → 把仿真时间快进到那一刻 → 把事件处理掉 → 把控制权还给 Agent。

"下一件事"可能是：

- 一个任务到达 50% 进度（`TASK_HALF_PROGRESS`）
- 一个任务完成（`TASK_COMPLETED`，无论按时还是延期）
- 月初到了，要发工资（`monthly_payroll`）
- 一年到期（`HORIZON_END`）
- 账上钱变负（`BANKRUPTCY`）

`sim resume` 会**一直推进**，直到撞到一个 Agent 必须看到的事件为止。

## 工作只在工作时间发生

`default` 预设里：

```
workday_start_hour = 9     # 早 9 点
workday_end_hour = 18      # 晚 6 点
work_hours_per_day = 9     # 每个工作日 9 小时
```

也就是：

- **周一到周五 9:00–18:00** 算工作时间，员工在推进任务。
- 周末、夜晚都**完全不推进**——时间过了，但工作量没动。
- 工资发放、被动衰减这些"挂钟时间"的事件不受工作时间限制（按真实日期走）。

直接影响：

- 周五下午接的任务，截止日期会**自动跳过周末**。
- 在周末调用 `sim resume`，会一路快进到下周一上午 9 点。
- 任务完成时间永远落在工作时间内。

## 工资什么时候扣

工资是**每月第一个工作日的 09:00** 一次性扣完。

> 不是 1 号——如果 1 号是周末，会顺延到第一个工作日。

具体逻辑：

1. 拉所有员工的当前 `salary_cents` 求和。
2. 公司账上 `funds_cents -= 总和`。
3. 每个员工写一条 `MONTHLY_PAYROLL` 类别的账本条目（`finance ledger` 能查）。
4. 扣完后如果 `funds_cents < 0` → **直接破产**，本局结束。

并且——

- **工资和事件冲突时，工资优先**。在同一时刻 `payroll < event < target`。这模拟的是"月初工资是雷打不动的义务"。
- **每次工资发完，引擎会主动停下来**让 Agent 看一眼——让 Agent 有机会在工资发完后立刻调整策略。

> **时序关键**：如果工资日和某个任务完成事件落在同一时间点，会**先扣工资 → 立即做破产检查**；如果已经为负就破产。之后的任务报酬要等下次推进才能入账。**所以工资日扣完已经破产，靠"同一时刻完成的任务"是救不回来的**。

## 任务里程碑事件

引擎支持"任务到达指定进度百分比时触发事件"的能力，由 `task_progress_milestones` 控制。

**但 `default` 预设里这个数组是空的**（`task_progress_milestones = []`）——默认不会有里程碑事件，Agent 只会在任务完成那一刻收到通知。

自定义预设可设为 `[0.25, 0.5, 0.75]` 给 Agent 中途检查点。里程碑事件本身**不改变任何状态**，只是给 Agent "中途叫醒"的机会。

## 任务完成事件：`TASK_COMPLETED`

这是最重要的一类事件。完成时处理逻辑：

1. 判断 `success = (completed_at <= deadline)`：
   - **成功**：加报酬、加声望（每个领域 +`reward_prestige_delta`）、给前 4 名员工加技能、给所有被指派员工加工资。
   - **延期**：扣声望（`penalty_fail_multiplier × reward_prestige_delta`）、扣 35% 报酬现金、**不加工资**、**不加技能**。
2. 更新该客户的信任（成功 → 递减收益加成；延期 → 扣 0.18）。
3. 给其它所有客户扣 `trust_cross_client_decay = 0.018`。
4. **重算所有 active 任务的 ETA**（释放出来的员工让其它任务 ETA 变快）。
5. 检查破产。

完成事件返回的 JSON 类似：

```json
{
  "type": "task_completed",
  "task_title": "Task-17",
  "client_name": "Vertex Labs",
  "success": true,
  "funds_delta": 1850000,
  "trust_delta": 0.40,
  "deadline_margin": "ahead by 14h",
  "employees_assigned": 3,
  "salary_bump_total_cents": 210,
  "bankrupt": false
}
```

## `sim resume` 到底干了啥

实际行为：

1. **必须先有 active 任务**。如果没有，会报错——避免"光发工资不干活"。
2. 反复推进时间直到撞到下一个 actionable event。
3. 如果是 `task_completed` 之类的事件，**返回**。
4. 如果只是 `monthly_payroll` 且还有 active 任务，会**自动 fast-forward 跳过**（除非破产）。
5. 如果当前没有 active 任务了（最后一个刚完成），也会**停下来**让 Agent 接新单。
6. 到 `HORIZON_END` 或 `BANKRUPTCY` 立刻终止。

返回里会带一个 `wake_events` 列表，包含从上次到现在所有发生过的事件。

> **关键**：`sim resume` 不是"前进 1 天"——它是"前进到下一个该让你看的事情发生为止"。可能 0.1 天，也可能 30 天。

## 一年里时间是怎么分布的

`default` 预设：开局 `2025-01-01 09:00`，跑满 1 年就是 `2026-01-01 09:00` 触发 `HORIZON_END`。

时间事件密度大概是：

- **12 次月度工资**（每月第一个工作日）。
- **任务完成事件**：几十到一百多个。
- **任务半程事件**：`default` 预设下为 0 个。

中位数 Agent 一年大概触发**几十到一百多个事件**。

## 结局：三种终止方式

```
1. HORIZON_END    → 时间到，正常退休 → 看最终成绩
2. BANKRUPTCY     → 资金 < 0，破产 → 立刻终止
3. max_episodes>1 → 破产后自动重启一局（scratchpad 保留），用完所有 episode 才结束
```

`auto_advance_after_turns = 5`——Agent 如果**连续 5 轮没调用 `sim resume`**，runner 会强制替它调用一次，避免它卡死。

## 给 Agent 的几条提示

1. **`sim resume` 不会前进固定时长**——永远以 `company status` 的 `sim_time` 字段为准。
2. **工资是雷打不动的**——月末前两周如果跑道告急，应该提前加速完成在做的任务。
3. **`wake_events` 里可能有多个事件**——一次 resume 里可能"工资发了 + 一个任务完成"全在一起。
4. **没有 active 任务时 `sim resume` 会报错**——先 accept → assign → dispatch → 再 resume。
5. **`deadline_margin` 是诊断 RAT 的重要信号**——如果一个客户的任务总是 late，大概率是 scope creep。

下一篇具体讲 Agent 怎么和这套 CLI 打交道 → [07 - Agent 怎么和系统对话](07-agent-interface.md)。
