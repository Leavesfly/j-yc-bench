#!/usr/bin/env bash
# ============================================================================
# j-yc-bench 单回合 LLM 演示
# 直接调用 Ollama API，展示 LLM Agent 面对任务市场时的决策过程
# ============================================================================

set -e

MODEL="${1:-qwen3.5:4b}"
BASE_URL="http://localhost:11434"

# 颜色
BOLD='\033[1m'
GREEN='\033[0;32m'
CYAN='\033[0;36m'
YELLOW='\033[1;33m'
MAGENTA='\033[0;35m'
DIM='\033[2m'
NC='\033[0m'

echo -e "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${CYAN}  🧠 j-yc-bench — LLM Agent 单回合决策演示${NC}"
echo -e "${DIM}  模型: ${MODEL} | API: ${BASE_URL}${NC}"
echo -e "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo ""

# 检查 Ollama
if ! curl -s "${BASE_URL}/api/tags" > /dev/null 2>&1; then
    echo -e "${YELLOW}❌ 无法连接 Ollama，请先启动: ollama serve${NC}"
    exit 1
fi
echo -e "${GREEN}✅ Ollama 连接正常${NC}"
echo ""

# 展示公司状态
echo -e "${BOLD}┌─────────────── 📊 公司状态 ───────────────┐${NC}"
echo -e "│  💰 资金: \$200,000.00                     │"
echo -e "│  👥 员工: 8 人 | 月薪: \$38,005            │"
echo -e "│  ⏱️  跑道: ~5.3 个月                       │"
echo -e "│  📊 声望: 1.0                             │"
echo -e "│  🔄 活跃任务: 0                           │"
echo -e "${BOLD}└───────────────────────────────────────────┘${NC}"
echo ""

echo -e "${BOLD}📋 市场上的可选任务:${NC}"
echo -e "${DIM}────────────────────────────────────────────────────────${NC}"
echo "  Task-67  | Helix Systems       | research | \$20,495 | +0.018"
echo "  Task-140 | Cortex Intelligence | inference| \$18,280 | +0.210"
echo "  Task-92  | Cortex Intelligence | inference| \$16,811 | +0.093"
echo "  Task-3   | Prism Analytics     | data     | \$16,353 | +0.160"
echo "  Task-31  | Helix Systems       | inference| \$16,939 | +0.141"
echo -e "${DIM}────────────────────────────────────────────────────────${NC}"
echo ""

echo -e "${BOLD}👥 团队:${NC}"
echo "  Emp_1 Alice (senior/inference/5.2)  Emp_5 Eva (junior/inference/2.1)"
echo "  Emp_2 Bob   (mid/data/3.8)          Emp_6 Frank (senior/data/4.9)"
echo "  Emp_3 Carol (senior/research/5.0)   Emp_7 Grace (mid/research/3.6)"
echo "  Emp_4 David (mid/training/3.5)      Emp_8 Henry (junior/training/2.0)"
echo ""

echo -e "${MAGENTA}${BOLD}🧠 正在请求 LLM 决策...${NC}"
echo -e "${DIM}────────────────────────────────────────────────────────${NC}"

# 流式调用 Ollama 原生 API — 关闭 thinking 模式，逐字输出
echo ""
echo -e "${GREEN}${BOLD}💬 Agent 决策（流式输出）:${NC}"
echo -e "${DIM}────────────────────────────────────────────────────────${NC}"

curl -sN --max-time 300 "${BASE_URL}/api/chat" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "'"${MODEL}"'",
    "messages": [
      {
        "role": "system",
        "content": "你是一个 AI 创业公司的 CEO Agent。公司有 $200,000 资金，8名员工，月薪 $38,005，跑道约5个月。你需要从市场上选择任务来赚取收入并提升声望，合理分配员工，确保公司存活一年。请用中文简洁分析并给出决策。"
      },
      {
        "role": "user",
        "content": "当前是第1回合。公司状态：资金$200,000, 跑道5.3个月, 声望1.0, 无活跃任务。\n\n可选任务:\n1. Task-67: Helix Systems, research领域, 报酬$20,495, 声望+0.018, 工作量878\n2. Task-140: Cortex Intelligence, inference领域, 报酬$18,280, 声望+0.210, 工作量547\n3. Task-92: Cortex Intelligence, inference领域, 报酬$16,811, 声望+0.093, 工作量562\n4. Task-3: Prism Analytics, data领域, 报酬$16,353, 声望+0.160, 工作量868\n5. Task-31: Helix Systems, inference领域, 报酬$16,939, 声望+0.141, 工作量876\n\n团队:\n- Emp_1 Alice: senior/inference, 效率5.2/h\n- Emp_2 Bob: mid/data, 效率3.8/h\n- Emp_3 Carol: senior/research, 效率5.0/h\n- Emp_4 David: mid/training, 效率3.5/h\n- Emp_5 Eva: junior/inference, 效率2.1/h\n- Emp_6 Frank: senior/data, 效率4.9/h\n- Emp_7 Grace: mid/research, 效率3.6/h\n- Emp_8 Henry: junior/training, 效率2.0/h\n\n请分析并决策：接哪个任务？分配哪些员工？为什么？"
      }
    ],
    "think": false,
    "stream": true,
    "options": {"temperature": 0.7}
  }' 2>/dev/null | python3 -c "
import sys, json

for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    try:
        chunk = json.loads(line)
        content = chunk.get('message', {}).get('content', '')
        if content:
            print(content, end='', flush=True)
        if chunk.get('done', False):
            break
    except (json.JSONDecodeError, KeyError):
        continue
print()
"

echo -e "${DIM}────────────────────────────────────────────────────────${NC}"

echo ""
echo -e "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${GREEN}✅ 单回合演示完成！${NC}"
echo ""
echo -e "${BOLD}💡 更多演示:${NC}"
echo "  • 多回合可视化: python3 scripts/llm_demo.py --turns 5"
echo "  • Bot 策略对比: ./scripts/demo.sh"
echo "  • 完整基准测试: java -jar target/j-yc-bench.jar run --seed 1"
echo ""
