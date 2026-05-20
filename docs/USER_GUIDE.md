# j-yc-bench 用户使用指南

本指南面向希望使用 j-yc-bench 评估 LLM Agent 长期决策能力的研究人员和工程师。

---

## 目录

1. [环境准备](#环境准备)
2. [构建项目](#构建项目)
3. [配置 API Key](#配置-api-key)
4. [运行第一次基准测试](#运行第一次基准测试)
5. [理解输出结果](#理解输出结果)
6. [多 Seed 批量评估](#多-seed-批量评估)
7. [查看和分析结果](#查看和分析结果)
8. [自定义配置预设](#自定义配置预设)
9. [使用 CLI 手动调试](#使用-cli-手动调试)
10. [常见问题排查](#常见问题排查)

---

## 环境准备

### 系统要求

| 组件 | 最低版本 | 说明 |
|------|---------|------|
| JDK | 11+ | 推荐 OpenJDK 11 或 17 |
| Maven | 3.6+ | 用于构建项目 |
| 磁盘空间 | ~500MB | 含依赖下载 + SQLite 数据库 |
| 网络 | 可访问 LLM API | OpenAI / Anthropic / Gemini / OpenRouter |

### 验证环境

```bash
java -version    # 确认 >= 11
mvn -version     # 确认 >= 3.6
```

---

## 构建项目

```bash
cd j-yc-bench
mvn -DskipTests package
```

构建成功后产出 `target/j-yc-bench.jar`（fat JAR，含所有依赖，约 15MB）。

验证构建：

```bash
java -jar target/j-yc-bench.jar --version
# 输出: j-yc-bench 0.1.0
```

---

## 配置 API Key

j-yc-bench 支持 4 个 LLM 提供商，通过环境变量配置 API Key。

### 方式一：`.env` 文件（推荐）

在项目根目录创建 `.env` 文件：

```bash
# 按需配置，只需要你实际使用的 provider
OPENAI_API_KEY=sk-proj-...
ANTHROPIC_API_KEY=sk-ant-api03-...
GEMINI_API_KEY=AIzaSy...
OPENROUTER_API_KEY=sk-or-v1-...
```

程序启动时会自动从当前目录或父目录查找并加载 `.env`。

### 方式二：环境变量

```bash
export OPENAI_API_KEY="sk-proj-..."
```

### Provider 与模型名对应关系

| 你想用的模型 | `--model` 参数写法 | 需要的环境变量 |
|------------|-------------------|--------------|
| GPT-4o | `openai/gpt-4o` | `OPENAI_API_KEY` |
| GPT-4o-mini | `openai/gpt-4o-mini` | `OPENAI_API_KEY` |
| Claude 3.5 Sonnet | `anthropic/claude-3-5-sonnet-20241022` | `ANTHROPIC_API_KEY` |
| Claude 3 Haiku | `anthropic/claude-3-haiku-20240307` | `ANTHROPIC_API_KEY` |
| Gemini 2.0 Flash | `gemini/gemini-2.0-flash` | `GEMINI_API_KEY` |
| 通过 OpenRouter 路由 | `openrouter/anthropic/claude-3.5-sonnet` | `OPENROUTER_API_KEY` |

---

## 运行第一次基准测试

### 最简命令

```bash
java -jar target/j-yc-bench.jar run \
  --model openai/gpt-4o-mini \
  --seed 1 \
  --config default
```

这会启动一次完整的基准测试：
- Agent（由 `gpt-4o-mini` 驱动）将接管一家 AI 公司
- 仿真从 2025-01-01 开始，持续 1 年
- 公司有 $200,000 启动资金、8 名员工、6 个客户

### 运行时间预估

| 模型 | 平均回合数 | 大致耗时 |
|------|-----------|---------|
| gpt-4o-mini | 50~80 | 10~20 分钟 |
| gpt-4o | 50~80 | 15~30 分钟 |
| claude-3.5-sonnet | 50~80 | 15~30 分钟 |

实际耗时取决于 API 响应速度和 Agent 的决策复杂度。

### 全部参数说明

```bash
java -jar target/j-yc-bench.jar run --help
```

| 参数 | 必需 | 默认值 | 说明 |
|------|------|--------|------|
| `--model` | ✓ | — | LLM 模型标识符 |
| `--seed` | ✓ | — | 任务市场随机种子 |
| `--config` | | `default` | 预设名或 YAML 文件路径 |
| `--horizon-years` | | 1 | 仿真年数 |
| `--company-name` | | `BenchCo` | 公司名（仅显示） |
| `--start-date` | | `2025-01-01` | 起始日期 |
| `--max-episodes` | | 1 | 允许的最大 episode 数 |
| `--no-live` | | false | 禁用实时输出 |

### 中断与恢复

运行过程中可以随时 `Ctrl+C` 中断。再次执行**相同命令**会自动从断点恢复。

如果想重新开始，需要先删除对应的数据库文件：

```bash
rm db/default_1_openai_gpt-4o-mini.db*
```

---

## 理解输出结果

### 产出文件

```
db/default_1_openai_gpt-4o-mini.db              ← SQLite 仿真数据库
db/default_1_openai_gpt-4o-mini.transcript.jsonl ← 逐回合对话记录
db/default_1_openai_gpt-4o-mini.session.json     ← LLM 会话快照
results/yc_bench_result_default_1_openai_gpt-4o-mini.json ← 最终结果
```

### 结果 JSON 核心字段

```json
{
  "model": "openai/gpt-4o-mini",
  "seed": 1,
  "terminal": true,
  "terminal_reason": "horizon_end",    // 或 "bankruptcy"
  "terminal_detail": "Simulation ended at horizon",
  "time_series": { ... }               // 资金、声望随时间变化
}
```

### 如何判断成绩好坏

1. **存活？** — `terminal_reason == "horizon_end"` 表示活到了年底（合格线）
2. **终态资金** — 用 sqlite3 查看：

```bash
sqlite3 db/default_1_openai_gpt-4o-mini.db "SELECT funds_cents FROM company;"
```

3. **声望** — 查看四个领域的最终声望：

```bash
sqlite3 db/default_1_openai_gpt-4o-mini.db "SELECT domain, prestige_level FROM company_prestige;"
```

---

## 多 Seed 批量评估

单个 seed 的结果有随机性，建议至少跑 **3~5 个 seed** 取平均。

### 串行执行

```bash
for seed in 1 2 3 4 5; do
  java -jar target/j-yc-bench.jar run \
    --model openai/gpt-4o-mini \
    --seed $seed \
    --config default
done
```

### 并行执行（推荐）

```bash
for seed in 1 2 3 4 5; do
  java -jar target/j-yc-bench.jar run \
    --model openai/gpt-4o-mini \
    --seed $seed \
    --config default &
done
wait
echo "All seeds completed."
```

每个 seed 会产出独立的 db 和 result 文件，互不冲突。

### 多模型对比

```bash
MODELS=("openai/gpt-4o-mini" "openai/gpt-4o" "anthropic/claude-3-5-sonnet-20241022")
for model in "${MODELS[@]}"; do
  for seed in 1 2 3; do
    java -jar target/j-yc-bench.jar run --model "$model" --seed $seed --config default &
  done
done
wait
```

---

## 查看和分析结果

### 用 sqlite3 快速查看

```bash
# 最终资金
sqlite3 db/default_1_*.db "SELECT funds_cents/100.0 AS dollars FROM company;"

# 完成了多少任务
sqlite3 db/default_1_*.db "SELECT status, COUNT(*) FROM task GROUP BY status;"

# 每个客户的成功/失败
sqlite3 db/default_1_*.db "
  SELECT c.name, 
         SUM(CASE WHEN t.success=1 THEN 1 ELSE 0 END) AS success,
         SUM(CASE WHEN t.success=0 THEN 1 ELSE 0 END) AS fail
  FROM task t JOIN client c ON t.client_id = c.id
  WHERE t.status IN ('completed_success','completed_fail')
  GROUP BY c.name;"

# 查看 Agent 的 scratchpad 内容
sqlite3 db/default_1_*.db "SELECT content FROM scratchpad;"
```

### 查看 transcript

```bash
# 看最后 5 个回合的 Agent 命令
tail -5 db/default_1_openai_gpt-4o-mini.transcript.jsonl | python3 -m json.tool
```

### 可视化脚本

项目根目录的 `scripts/` 提供了 Python 画图工具（需要 matplotlib）：

```bash
# 单次 run 的资金曲线
python3 ../scripts/plot_results.py results/yc_bench_result_default_1_openai_gpt-4o-mini.json

# 多模型对比
python3 ../scripts/plot_multi_model.py results/
```

---

## 自定义配置预设

### 创建自定义预设

复制默认预设并修改：

```bash
cp src/main/resources/presets/default.yaml my_hard_preset.yaml
```

编辑 `my_hard_preset.yaml`：

```yaml
world:
  initial_funds_cents: 10000000    # $100K（更紧的启动资金）
  salary_bump_pct: 0.02            # 2%（工资涨得更快）
  loyalty_rat_fraction: 0.50       # 50%（更多 RAT 客户）
  prestige_decay_per_day: 0.01     # 启用声望衰减
```

使用自定义预设运行：

```bash
java -jar target/j-yc-bench.jar run \
  --model openai/gpt-4o \
  --seed 1 \
  --config ./my_hard_preset.yaml
```

### 常用调参场景

| 场景 | 调整方式 |
|------|---------|
| 快速验证（缩短运行时间） | 减小 `required_qty` 分布的 high 值 |
| 加大难度 | 减少 `initial_funds_cents`，增大 `salary_bump_pct` |
| 更多陷阱 | 增大 `loyalty_rat_fraction`（如 0.5） |
| 启用声望衰减 | 设置 `prestige_decay_per_day: 0.01` |
| 更短对话记忆 | 减小 `history_keep_rounds`（如 10） |

---

## 使用 CLI 手动调试

你可以手动执行 CLI 命令来理解仿真机制（先初始化一个世界）：

```bash
# 初始化仿真世界
java -jar target/j-yc-bench.jar sim init --seed 1 --config default

# 查看公司状态
java -jar target/j-yc-bench.jar company status

# 浏览市场
java -jar target/j-yc-bench.jar market browse

# 接受一个任务
java -jar target/j-yc-bench.jar task accept --task-id Task-1

# 查看员工
java -jar target/j-yc-bench.jar employee list

# 指派员工
java -jar target/j-yc-bench.jar task assign --task-id Task-1 --employees Emp_1,Emp_2

# 开工
java -jar target/j-yc-bench.jar task dispatch --task-id Task-1

# 推进时间
java -jar target/j-yc-bench.jar sim resume

# 查看客户历史
java -jar target/j-yc-bench.jar client history

# 查看财务流水
java -jar target/j-yc-bench.jar finance ledger

# 读写 scratchpad
java -jar target/j-yc-bench.jar scratchpad read
java -jar target/j-yc-bench.jar scratchpad write --content "Cipher Corp is RAT"
```

所有命令返回 JSON，可配合 `| python3 -m json.tool` 格式化阅读。

---

## 常见问题排查

### Q: 运行报错 "No API key found for provider openai"

**原因**：未配置对应的环境变量。

**解决**：确认 `.env` 文件在项目根目录，且格式正确（无引号包裹值，或使用双引号）。

### Q: 运行中断后再次执行报错

**原因**：数据库文件残留且状态不一致。

**解决**：
```bash
rm db/default_1_*.db db/default_1_*.session.json db/default_1_*.transcript.jsonl
```

### Q: Agent 总是破产

**可能原因**：
- 模型能力不足（未能识别 RAT、不写 scratchpad、不控制团队规模）
- 预设难度过高

**建议**：
- 先用 `gpt-4o` 等强模型跑通 default 预设
- 查看 transcript 分析 Agent 的决策模式
- 检查 scratchpad 是否为空（Agent 是否在用长期记忆）

### Q: 运行非常慢

**可能原因**：
- LLM API 响应慢
- Agent 每回合调用了太多命令

**建议**：
- 使用 `gpt-4o-mini` 等快速模型进行调试
- 降低 `required_qty` 分布让任务更快完成（减少总回合数）
- 如果是网络问题，考虑使用 OpenRouter 路由

### Q: 想对比 Java 版和 Python 版结果

项目附带对比脚本：

```bash
bash scripts/diff_python_java.sh --seed 1 --config default
```

由于 `PyRandom` 精确复刻了 Python MT19937，相同 seed 的世界生成应完全一致。

---

## 进一步阅读

- **Wiki（通俗讲解）**：[`wiki/`](wiki/) — 从"是什么"到"怎么扩展"
- **系统架构**：[`wiki/11-architecture.md`](wiki/11-architecture.md)
- **数据模型**：[`wiki/12-data-models.md`](wiki/12-data-models.md)
- **游戏机制详解**：[`wiki/02-game-rules.md`](wiki/02-game-rules.md)
