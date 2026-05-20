#!/usr/bin/env bash
# ============================================================================
# j-yc-bench 快速演示脚本
# 无需 LLM，直接用内置 Bot 策略运行仿真，展示项目核心能力
# ============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
JAR="$PROJECT_DIR/target/j-yc-bench.jar"

# 颜色
GREEN='\033[0;32m'
CYAN='\033[0;36m'
YELLOW='\033[1;33m'
BOLD='\033[1m'
NC='\033[0m'

echo -e "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${CYAN}  🚀 j-yc-bench 演示${NC}"
echo -e "${CYAN}  Long-horizon deterministic benchmark for LLM agents${NC}"
echo -e "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo ""

# 检查 JAR 是否存在
if [ ! -f "$JAR" ]; then
    echo -e "${YELLOW}⚙️  JAR 未找到，正在构建...${NC}"
    cd "$PROJECT_DIR"
    mvn -DskipTests package -q 2>/dev/null || {
        echo "构建失败，请先手动运行: mvn -DskipTests package"
        exit 1
    }
    echo -e "${GREEN}✅ 构建完成${NC}"
    echo ""
fi

echo -e "${BOLD}📋 演示内容：${NC}"
echo "   使用 4 种内置 Bot 策略模拟 AI 创业公司经营一整年"
echo "   • greedy    — 总是接最贵的任务（贪心）"
echo "   • random    — 随机选择任务"
echo "   • throughput— 挑性价比最高的任务"
echo "   • prestige  — 前期刷声望，后期切收益"
echo ""
echo -e "${BOLD}🎯 目标：${NC} 用 \$200,000 启动资金 + 8 名员工存活一整年不破产"
echo ""
echo -e "${BOLD}⚠️  注意：${NC} 35% 的客户是隐藏的 RAT 陷阱客户！"
echo ""
echo -e "${YELLOW}▶  开始运行...${NC}"
echo ""

cd "$PROJECT_DIR"
java -cp "$JAR" com.collinear.ycbench.scripts.BotRunner --seed 1

echo ""
echo -e "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${GREEN}✅ 演示完成！${NC}"
echo ""
echo -e "${BOLD}💡 下一步：${NC}"
echo "   • 使用 LLM Agent 运行:  java -jar target/j-yc-bench.jar run --seed 1"
echo "   • 查看更多命令:         java -jar target/j-yc-bench.jar --help"
echo "   • 自定义配置:           vim src/main/resources/presets/default.yaml"
echo ""
