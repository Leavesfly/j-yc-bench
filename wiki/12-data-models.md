# 12 · 数据模型与存储设计

## 存储方案

j-yc-bench 使用单一 **SQLite 文件**存储所有仿真状态。选择 SQLite 的原因：

- **零部署依赖**：无需安装数据库服务器，单 JAR 即可运行
- **可审计**：任何人都能用 `sqlite3` 命令行直接查询验证仿真过程
- **确定性**：同一 seed 产出完全相同的 db 文件
- **断点恢复**：中断后再次运行，检测到已有 db 直接续跑

通过 `org.xerial:sqlite-jdbc` 驱动访问，启用 `PRAGMA foreign_keys = ON` 和 `PRAGMA journal_mode = WAL`。

## 核心数据模型

所有模型位于 `com.collinear.ycbench.db.model` 包，均为简单 POJO（public 字段，无 getter/setter）。

### 公司相关

**`Company`**
```java
public final class Company {
    public UUID id;
    public String name;
    public long fundsCents;    // 当前资金（分）
}
```

**`CompanyPrestige`**
```java
public final class CompanyPrestige {
    public UUID companyId;
    public String domain;          // research | inference | training | data_environment
    public double prestigeLevel;   // 1.0 ~ 10.0
}
```

### 员工相关

**`Employee`**
```java
public final class Employee {
    public UUID id;
    public UUID companyId;
    public String name;            // "Emp_1" ~ "Emp_8"
    public String tier;            // "junior" | "mid" | "senior"
    public double workHoursPerDay; // 默认 9.0
    public long salaryCents;       // 当前月薪（分）
}
```

**`EmployeeSkillRate`**
```java
public final class EmployeeSkillRate {
    public UUID employeeId;
    public String domain;          // 四个领域之一
    public double rate;            // 1.0 ~ 10.0，单位/小时
}
```

### 任务相关

**`Task`**
```java
public final class Task {
    public UUID id;
    public UUID companyId;         // 接受前为 null
    public UUID clientId;
    public TaskStatus status;      // MARKET → PLANNED → ACTIVE → COMPLETED_* / CANCELLED
    public String title;
    public int requiredPrestige;
    public long rewardFundsCents;
    public double rewardPrestigeDelta;
    public double skillBoostPct;
    public OffsetDateTime acceptedAt;
    public OffsetDateTime deadline;
    public OffsetDateTime completedAt;
    public Boolean success;
    public int progressMilestonePct;
    public int requiredTrust;
    public Long advertisedRewardCents;  // 挂牌价（接受前可见）
    public Integer marketSlot;
}
```

**`TaskStatus`** 枚举：
| 值 | 含义 |
|----|------|
| `MARKET` | 在市场上，可被接受 |
| `PLANNED` | 已接受，等待指派/开工 |
| `ACTIVE` | 正在执行 |
| `COMPLETED_SUCCESS` | 按时完成 |
| `COMPLETED_FAIL` | 延期完成 |
| `CANCELLED` | 被取消 |

**`TaskRequirement`**
```java
public final class TaskRequirement {
    public UUID taskId;
    public String domain;
    public double requiredQty;     // 该领域所需工作量
    public double completedQty;    // 已完成工作量
}
```

**`TaskAssignment`**
```java
public final class TaskAssignment {
    public UUID taskId;
    public UUID employeeId;
}
```

### 客户相关

**`Client`**
```java
public final class Client {
    public UUID id;
    public String name;
    public double rewardMultiplier;    // 0.7 ~ 2.5
    public String tier;                // "Standard" | "Premium" | "Enterprise"
    public List<String> specialtyDomains;  // JSON 数组存储
    public double loyalty;             // -1.0 ~ 1.0（对 Agent 隐藏）
}
```

**`ClientTrust`**
```java
public final class ClientTrust {
    public UUID clientId;
    public UUID companyId;
    public double trustLevel;          // 0.0 ~ 5.0
}
```

### 事件系统

**`SimEvent`**
```java
public final class SimEvent {
    public UUID id;
    public UUID companyId;
    public EventType eventType;
    public OffsetDateTime scheduledAt;
    public String payload;             // JSON 字符串
    public boolean processed;
}
```

**`EventType`** 枚举：
| 值 | 含义 |
|----|------|
| `TASK_HALF_PROGRESS` | 任务到达进度里程碑 |
| `TASK_COMPLETED` | 任务完成（成功或延期） |
| `PAYMENT_DISPUTE` | 支付争议（保留机制） |
| `BANKRUPTCY` | 破产 |
| `HORIZON_END` | 仿真时间到期 |

### 财务系统

**`LedgerEntry`**
```java
public final class LedgerEntry {
    public UUID id;
    public UUID companyId;
    public OffsetDateTime occurredAt;
    public LedgerCategory category;
    public long amountCents;           // 正=入账，负=出账
    public String refType;             // "employee" | "task"
    public String refId;
}
```

**`LedgerCategory`** 枚举：
| 值 | 含义 |
|----|------|
| `MONTHLY_PAYROLL` | 月度工资 |
| `TASK_REWARD` | 任务报酬 |
| `TASK_FAIL_PENALTY` | 延期罚款 |
| `TASK_CANCEL_PENALTY` | 取消罚款 |
| `PAYMENT_DISPUTE` | 支付争议 |

### 全局状态

**`SimState`**
```java
public final class SimState {
    public UUID companyId;
    public OffsetDateTime currentTime;
    public OffsetDateTime horizonEnd;
    public boolean terminal;
    public String terminalReason;      // "bankruptcy" | "horizon_end" | null
}
```

**`Scratchpad`**
```java
public final class Scratchpad {
    public UUID companyId;
    public String content;             // Agent 的长期备忘录，纯文本
}
```

## DAO 层设计

10 个 DAO 类对应核心数据操作：

| DAO | 主要方法 |
|-----|---------|
| `CompanyDao` | `findById`、`updateFunds`、`getPrestige`、`updatePrestige` |
| `EmployeeDao` | `listByCompany`、`getSkillRates`、`updateSalary`、`updateSkillRate` |
| `TaskDao` | `findById`、`listByStatus`、`accept`、`assign`、`dispatch`、`complete`、`cancel` |
| `ClientDao` | `listAll`、`getTrust`、`updateTrust` |
| `EventDao` | `insert`、`nextPending`、`markProcessed` |
| `LedgerDao` | `insert`、`listByCompany` |
| `SimStateDao` | `get`、`updateTime`、`markTerminal` |
| `ScratchpadDao` | `get`、`write`、`append`、`clear` |
| `SessionDao` | `save`、`load`（LLM 对话快照） |
| `MonthlyMetricDao` | `insert`、`listAll`（月度统计快照） |

设计原则：
- **无 ORM**：全部原生 JDBC，通过 `JdbcUtils` 简化操作
- **Connection 传入**：DAO 不管理连接生命周期，由调用方控制事务
- **幂等 Schema**：`CREATE TABLE IF NOT EXISTS`，支持重复执行

## 表关系 ER 图

```
company ──1:N── employee
company ──1:N── company_prestige (4条，每个领域一条)
company ──1:1── sim_state
company ──1:1── scratchpad
company ──1:N── ledger_entry
company ──1:N── sim_event

client  ──1:N── task
client  ──1:1── client_trust (per company)

task    ──1:N── task_requirement (1~4条，每个领域一条)
task    ──N:M── employee (通过 task_assignment)
```

## 查询模式

核心查询路径：

1. **每回合状态快照**：`SimState` + `Company` + `CompanyPrestige` + active `Task` count
2. **市场浏览**：`Task WHERE status='market' AND requiredPrestige <= 当前声望`
3. **ETA 计算**：`Task(active)` + `TaskRequirement` + `TaskAssignment` + `EmployeeSkillRate`
4. **工资扣除**：`Employee.salaryCents SUM` → `Company.fundsCents -= total`
5. **任务完成**：`TaskRequirement.completedQty` 更新 + 声望/信任/工资/技能联动更新

---

> 想回到架构总览 → [11 - 系统架构](11-architecture.md)
> 想回到 Wiki 首页 → [README](README.md)
