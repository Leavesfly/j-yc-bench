#!/usr/bin/env python3
"""
j-yc-bench LLM Agent 可视化演示

实时展示 LLM Agent 如何经营一家 AI 创业公司：
- 直接调用 Ollama API 展示推理过程
- 彩色终端输出，直观展示决策链
- 无需额外依赖，仅需 Python 3.7+ 和 Ollama

用法:
    python3 scripts/llm_demo.py [--model MODEL] [--turns N]
"""

import json
import sys
import time
import urllib.request
import urllib.error
import argparse
import textwrap
from datetime import datetime

# ─── 终端颜色 ───────────────────────────────────────────────────────────────

BOLD = "\033[1m"
DIM = "\033[2m"
RED = "\033[31m"
GREEN = "\033[32m"
YELLOW = "\033[33m"
BLUE = "\033[34m"
MAGENTA = "\033[35m"
CYAN = "\033[36m"
WHITE = "\033[37m"
RESET = "\033[0m"
BG_BLUE = "\033[44m"
BG_GREEN = "\033[42m"
BG_RED = "\033[41m"


def color(text, *styles):
    return "".join(styles) + str(text) + RESET


# ─── 模拟公司状态 ─────────────────────────────────────────────────────────────

INITIAL_STATE = {
    "funds_cents": 20_000_000,
    "monthly_payroll": 3_800_503,
    "employees": 8,
    "active_tasks": 0,
    "completed_tasks": 0,
    "failed_tasks": 0,
    "prestige": 1.0,
    "sim_time": "2025-01-01T09:00Z",
    "horizon_end": "2026-01-01T09:00Z",
}

MARKET_TASKS = [
    {"id": "Task-67", "client": "Helix Systems", "domain": "research",
     "reward": 20495.85, "prestige_delta": 0.018, "qty": 878},
    {"id": "Task-31", "client": "Helix Systems", "domain": "inference",
     "reward": 16939.05, "prestige_delta": 0.141, "qty": 876},
    {"id": "Task-3", "client": "Prism Analytics", "domain": "data",
     "reward": 16353.15, "prestige_delta": 0.160, "qty": 868},
    {"id": "Task-92", "client": "Cortex Intelligence", "domain": "inference",
     "reward": 16811.53, "prestige_delta": 0.093, "qty": 562},
    {"id": "Task-140", "client": "Cortex Intelligence", "domain": "inference",
     "reward": 18280.32, "prestige_delta": 0.210, "qty": 547},
]

EMPLOYEES = [
    {"id": "Emp_1", "name": "Alice Chen", "tier": "senior", "domain": "inference", "rate": 5.2},
    {"id": "Emp_2", "name": "Bob Park", "tier": "mid", "domain": "data", "rate": 3.8},
    {"id": "Emp_3", "name": "Carol Wu", "tier": "senior", "domain": "research", "rate": 5.0},
    {"id": "Emp_4", "name": "David Kim", "tier": "mid", "domain": "training", "rate": 3.5},
    {"id": "Emp_5", "name": "Eva Singh", "tier": "junior", "domain": "inference", "rate": 2.1},
    {"id": "Emp_6", "name": "Frank Li", "tier": "senior", "domain": "data", "rate": 4.9},
    {"id": "Emp_7", "name": "Grace Zhao", "tier": "mid", "domain": "research", "rate": 3.6},
    {"id": "Emp_8", "name": "Henry Wang", "tier": "junior", "domain": "training", "rate": 2.0},
]


# ─── Ollama API 调用 ─────────────────────────────────────────────────────────

def call_ollama(messages, model, base_url, stream=True):
    """调用 Ollama 原生 API，关闭 thinking 模式，支持流式输出"""
    # 使用 Ollama 原生 /api/chat 接口，可传 think: false 关闭推理模式
    ollama_base = base_url.rsplit("/v1", 1)[0]
    url = f"{ollama_base}/api/chat"
    payload = {
        "model": model.replace("ollama/", ""),
        "messages": messages,
        "stream": stream,
        "think": False,
        "options": {"temperature": 0.7},
    }
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url, data=data,
        headers={"Content-Type": "application/json"}
    )

    try:
        resp = urllib.request.urlopen(req, timeout=300)
    except urllib.error.URLError as e:
        print(color(f"\n❌ 无法连接 Ollama: {e}", RED, BOLD))
        print(color("   请确保 Ollama 服务正在运行: ollama serve", YELLOW))
        sys.exit(1)

    if not stream:
        body = json.loads(resp.read().decode("utf-8"))
        return body.get("message", {}).get("content", "")

    # 流式输出 — Ollama 原生格式：每行一个 JSON 对象
    full_content = ""
    for line in resp:
        line = line.decode("utf-8").strip()
        if not line:
            continue
        try:
            chunk = json.loads(line)
            content = chunk.get("message", {}).get("content", "")
            if content:
                full_content += content
                sys.stdout.write(content)
                sys.stdout.flush()
            if chunk.get("done", False):
                break
        except json.JSONDecodeError:
            continue
    return full_content


# ─── 可视化输出 ────────────────────────────────────────────────────────────────

def print_banner():
    banner = f"""
{color("━" * 70, CYAN)}
{color("  🤖 j-yc-bench LLM Agent 可视化演示", BOLD, CYAN)}
{color("  Long-horizon deterministic benchmark for LLM agents", DIM)}
{color("━" * 70, CYAN)}
"""
    print(banner)


def print_company_status(state):
    funds = state["funds_cents"] / 100
    runway_months = funds / (state["monthly_payroll"] / 100)
    print(f"""
{color("┌─────────────────── 公司状态 ───────────────────┐", BLUE, BOLD)}
│  💰 {color(f"资金: ${funds:,.2f}", GREEN, BOLD):<48s}│
│  📅 当前时间: {color(state['sim_time'], WHITE):<38s}│
│  👥 员工: {state['employees']} 人 | 月薪总额: ${state['monthly_payroll']/100:,.2f}      │
│  ⏱️  跑道: {color(f"~{runway_months:.1f} 个月", YELLOW if runway_months < 6 else GREEN):<38s}│
│  📊 声望: {color(f"{state['prestige']:.2f}", MAGENTA):<39s}│
│  ✅ 完成: {state['completed_tasks']}  ❌ 失败: {state['failed_tasks']}  🔄 进行中: {state['active_tasks']:<11s}│
{color("└───────────────────────────────────────────────┘", BLUE, BOLD)}
""")


def print_market(tasks):
    print(color("\n📋 可用任务市场:", BOLD, YELLOW))
    print(color("─" * 70, DIM))
    print(f"  {'ID':<10} {'客户':<22} {'领域':<10} {'报酬':<12} {'声望'}")
    print(color("─" * 70, DIM))
    for t in tasks:
        print(f"  {t['id']:<10} {t['client']:<22} {t['domain']:<10} "
              f"${t['reward']:>9,.2f}  +{t['prestige_delta']:.3f}")
    print(color("─" * 70, DIM))


def print_employees(employees):
    print(color("\n👥 团队成员:", BOLD, YELLOW))
    print(color("─" * 60, DIM))
    print(f"  {'ID':<7} {'姓名':<14} {'级别':<8} {'领域':<10} {'效率'}")
    print(color("─" * 60, DIM))
    for e in employees:
        tier_color = GREEN if e["tier"] == "senior" else (YELLOW if e["tier"] == "mid" else DIM)
        print(f"  {e['id']:<7} {e['name']:<14} {color(e['tier']:<8, tier_color)} "
              f"{e['domain']:<10} {e['rate']:.1f}/h")
    print(color("─" * 60, DIM))


def print_section(title, icon="🔸"):
    print(f"\n{color(f'{icon} {title}', BOLD, MAGENTA)}")
    print(color("─" * 50, DIM))


def print_thinking():
    sys.stdout.write(color("\n💭 Agent 正在思考", CYAN, BOLD))
    for _ in range(3):
        time.sleep(0.3)
        sys.stdout.write(color(".", CYAN))
        sys.stdout.flush()
    print()


# ─── 演示流程 ──────────────────────────────────────────────────────────────────

def build_system_prompt():
    return textwrap.dedent("""\
        你是一个 AI 创业公司的 CEO Agent。你的目标是在 1 年内经营公司不破产。
        
        你需要：
        1. 从市场上选择合适的任务
        2. 合理分配员工
        3. 管理现金流，确保不会因为工资支出而破产
        
        请用中文简洁地回答，说明你的决策逻辑。
        格式：先列出你的分析要点，然后给出具体行动命令。
    """)


def build_turn_prompt(state, tasks, employees, turn_num):
    task_list = "\n".join(
        f"  - {t['id']}: {t['client']} | {t['domain']}领域 | 报酬${t['reward']:,.0f} | 声望+{t['prestige_delta']:.3f} | 工作量{t['qty']}"
        for t in tasks
    )
    emp_list = "\n".join(
        f"  - {e['id']} ({e['name']}): {e['tier']}级 | {e['domain']}领域 | 效率{e['rate']}/h"
        for e in employees
    )
    funds = state["funds_cents"] / 100
    runway = funds / (state["monthly_payroll"] / 100)

    return textwrap.dedent(f"""\
        ## 第 {turn_num} 回合 — 公司状态
        - 资金: ${funds:,.2f}
        - 跑道: ~{runway:.1f} 个月
        - 员工: {state['employees']} 人
        - 活跃任务: {state['active_tasks']}
        - 已完成: {state['completed_tasks']} | 失败: {state['failed_tasks']}
        
        ## 可用任务
        {task_list}
        
        ## 团队
        {emp_list}
        
        请分析当前局势并做出决策：选哪个任务？分配哪些员工？为什么？
    """)


def run_demo(model, base_url, max_turns):
    print_banner()
    print(color(f"  模型: {model}", DIM))
    print(color(f"  API:  {base_url}", DIM))
    print(color(f"  回合: {max_turns}", DIM))

    # 验证连接
    print(color("\n⏳ 检查 Ollama 连接...", YELLOW))
    try:
        urllib.request.urlopen(f"{base_url.rsplit('/v1', 1)[0]}/api/tags", timeout=5)
        print(color("✅ Ollama 服务正常\n", GREEN))
    except Exception:
        print(color("❌ 无法连接 Ollama，请确保服务正在运行", RED, BOLD))
        print(color("   启动命令: ollama serve", YELLOW))
        sys.exit(1)

    state = dict(INITIAL_STATE)
    messages = [{"role": "system", "content": build_system_prompt()}]

    for turn in range(1, max_turns + 1):
        print(color(f"\n{'═' * 70}", CYAN))
        print(color(f"  📍 第 {turn}/{max_turns} 回合", BOLD, CYAN))
        print(color(f"{'═' * 70}", CYAN))

        print_company_status(state)
        print_market(MARKET_TASKS[:3 + turn])  # 逐渐展示更多任务
        print_employees(EMPLOYEES)

        # 构建 prompt
        user_msg = build_turn_prompt(state, MARKET_TASKS[:3 + turn], EMPLOYEES, turn)
        messages.append({"role": "user", "content": user_msg})

        # 调用 LLM
        print_section("Agent 决策", "🧠")
        print_thinking()
        print(color("", GREEN))

        response = call_ollama(messages, model, base_url, stream=True)
        print(RESET)

        messages.append({"role": "assistant", "content": response})

        # 模拟状态变化
        state["completed_tasks"] += 1
        state["active_tasks"] = 1 if turn < max_turns else 0
        state["funds_cents"] -= state["monthly_payroll"] // 2  # 半月工资
        state["funds_cents"] += int(MARKET_TASKS[min(turn - 1, len(MARKET_TASKS) - 1)]["reward"] * 100)
        state["prestige"] += MARKET_TASKS[min(turn - 1, len(MARKET_TASKS) - 1)]["prestige_delta"]

        # 分隔
        print(color(f"\n  ✅ 回合 {turn} 完成 — Agent 已做出决策", GREEN, BOLD))
        if turn < max_turns:
            print(color("  ⏳ 推进仿真时间...", DIM))
            time.sleep(1)

    # 总结
    print(f"""
{color("━" * 70, GREEN)}
{color("  🎉 演示完成！", BOLD, GREEN)}
{color("━" * 70, GREEN)}

{color("📊 最终状态:", BOLD)}
  • 资金: ${state['funds_cents']/100:,.2f}
  • 声望: {state['prestige']:.2f}
  • 完成任务: {state['completed_tasks']}
  • 存活: {'✅ 是' if state['funds_cents'] > 0 else '❌ 破产'}

{color("💡 下一步:", BOLD)}
  • 运行完整基准测试: java -jar target/j-yc-bench.jar run --seed 1
  • Bot 策略对比:      ./scripts/demo.sh
  • 使用更大模型:      python3 scripts/llm_demo.py --model qwen3.5:32b
""")


# ─── 入口 ─────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="j-yc-bench LLM Agent 可视化演示 — 实时展示 AI 如何经营创业公司"
    )
    parser.add_argument("--model", default="qwen3.5:4b",
                        help="Ollama 模型名 (默认: qwen3.5:4b)")
    parser.add_argument("--base-url", default="http://localhost:11434/v1",
                        help="Ollama API 地址 (默认: http://localhost:11434/v1)")
    parser.add_argument("--turns", type=int, default=3,
                        help="演示回合数 (默认: 3)")
    args = parser.parse_args()

    run_demo(args.model, args.base_url, args.turns)


if __name__ == "__main__":
    main()
