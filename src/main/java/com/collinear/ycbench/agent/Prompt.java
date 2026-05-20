package com.collinear.ycbench.agent;

import java.util.List;
import java.util.Map;

/**
 * 系统提示和用户消息构建器。1:1 镜像 {@code agent/prompt.py}，
 * 包括 "ACTION REQUIRED" 警戒线和唤醒事件渲染。
 */
public final class Prompt {

    public static final String SYSTEM_PROMPT = String.join("\n",
            "You are the CEO of a startup in a business simulation. Maximize funds and prestige while avoiding bankruptcy.",
            "",
            "All actions use `yc-bench` CLI commands via `run_command`. All return JSON.",
            "",
            "## Core Workflow (repeat every turn)",
            "",
            "**You must always have active tasks running. Every turn, follow this loop:**",
            "",
            "1. `yc-bench market browse` \u2014 pick a task",
            "2. `yc-bench task accept --task-id Task-42` \u2014 accept it",
            "3. `yc-bench task assign --task-id Task-42 --employees Emp_1,Emp_4,Emp_7` \u2014 assign employees (check `employee list` for skill rates)",
            "4. `yc-bench task dispatch --task-id Task-42` \u2014 start work",
            "5. `yc-bench sim resume` \u2014 advance to next event (requires active tasks)",
            "",
            "Run multiple tasks concurrently when possible. Accept \u2192 assign \u2192 dispatch a second task before calling sim resume.",
            "",
            "**Use `yc-bench scratchpad write`** to save strategy notes \u2014 your conversation history is truncated after 20 turns, but scratchpad persists in the system prompt. Write reusable rules, not one-off observations.",
            "",
            "## Commands",
            "",
            "### Observe",
            "- `yc-bench company status` \u2014 funds, prestige, payroll",
            "- `yc-bench employee list` \u2014 employees with skill rates per domain",
            "- `yc-bench market browse [--domain X] [--reward-min-cents N] [--limit N]` \u2014 available tasks",
            "- `yc-bench task list [--status X]` \u2014 your tasks",
            "- `yc-bench task inspect --task-id Task-42` \u2014 task details",
            "- `yc-bench client list` \u2014 clients with trust levels",
            "- `yc-bench client history` \u2014 per-client success/failure rates",
            "- `yc-bench finance ledger` \u2014 financial history",
            "",
            "### Act",
            "- `yc-bench task accept --task-id Task-42` \u2014 accept from market",
            "- `yc-bench task assign --task-id Task-42 --employees Emp_1,Emp_4,Emp_7` \u2014 assign employees (comma-separated)",
            "- `yc-bench task dispatch --task-id Task-42` \u2014 start work (must assign first)",
            "- `yc-bench task cancel --task-id Task-42 --reason \"text\"` \u2014 cancel (prestige penalty)",
            "- `yc-bench sim resume` \u2014 advance time",
            "- `yc-bench scratchpad write --content \"text\"` \u2014 save notes",
            "- `yc-bench scratchpad append --content \"text\"` \u2014 append notes",
            "",
            "## Key Mechanics",
            "",
            "- **Salary bumps**: completed tasks raise salary for every assigned employee. More employees assigned = higher payroll growth.",
            "- **Throughput split**: employees on multiple active tasks split their rate (rate/N). Two tasks run at 50% each.",
            "- **Deadlines**: success before deadline = reward + prestige. Failure = prestige penalty, no reward.",
            "- **Trust**: completing tasks for a client builds trust \u2192 less work per task, access to gated tasks. Working for one client erodes trust with others.",
            "- **Not all clients are reliable.** Check `client history` for failure patterns.",
            "- **Payroll**: deducted monthly. Funds < 0 = bankruptcy.",
            "- Prestige grows per domain. Higher prestige unlocks better-paying tasks."
    );

    private Prompt() {
    }

    public static final class Snapshot {
        public String simTime;
        public String horizonEnd;
        public long fundsCents;
        public int activeTasks;
        public int plannedTasks;
        public int employeeCount;
        public long monthlyPayrollCents;
        public boolean bankrupt;
        public String scratchpad;
    }

    /** 构建运行开始时的初始用户消息。 */
    public static String buildInitialUserPrompt(Snapshot s, int episode) {
        StringBuilder sb = new StringBuilder();
        Double runwayMonths = s.monthlyPayrollCents > 0
                ? Math.round((double) s.fundsCents / s.monthlyPayrollCents * 10.0) / 10.0
                : null;
        String runwayStr = runwayMonths != null ? "~" + runwayMonths + " months" : "\u221e";

        if (episode > 1) {
            sb.append("## Episode ").append(episode).append(" \u2014 Restarting After Bankruptcy\n\n");
            sb.append("You went bankrupt in episode ").append(episode - 1).append(". The simulation has been reset,\n");
            sb.append("but your **scratchpad notes from the previous episode are preserved**.\n");
            sb.append("Check your scratchpad notes for strategy from the previous episode.\n");
            sb.append("and learn from past mistakes before taking action.\n\n");
        }
        sb.append("## Simulation Start \u2014 Take Immediate Action\n");
        sb.append("- current_time: ").append(s.simTime).append("\n");
        sb.append("- horizon_end: ").append(s.horizonEnd).append("\n");
        sb.append(String.format("- funds: $%,.2f%n", s.fundsCents / 100.0));
        sb.append(String.format("- monthly_payroll: $%,.2f%n", s.monthlyPayrollCents / 100.0));
        sb.append("- runway: ").append(runwayStr).append("\n");
        sb.append("- employees: ").append(s.employeeCount).append("\n");
        sb.append("- active_tasks: ").append(s.activeTasks).append("\n");
        sb.append("- planned_tasks: ").append(s.plannedTasks).append("\n\n");
        sb.append("**Your immediate priority**: generate revenue before payroll drains your runway.\n");
        sb.append("Complete these steps now (multiple commands per turn are fine):\n");
        sb.append("1. `yc-bench market browse` \u2014 see available tasks\n");
        sb.append("2. `yc-bench task accept --task-id Task-42` \u2014 accept a task\n");
        sb.append("3. `yc-bench task assign --task-id Task-42 --employees Emp_1,Emp_4,Emp_7` \u2014 assign employees\n");
        sb.append("4. `yc-bench task dispatch --task-id Task-42` \u2014 start work\n");
        sb.append("5. `yc-bench sim resume` \u2014 advance time\n\n");
        sb.append("**IMPORTANT**: Check each command's result before proceeding to the next.\n");
        sb.append("If `task accept` fails (trust or prestige too low), try a different task.\n");
        sb.append("Do NOT call `sim resume` unless you have at least one active task \u2014 it will skip forward with zero revenue.");
        if (s.bankrupt) sb.append("\nWARNING: company is already bankrupt at initialization.");
        return sb.toString();
    }

    /** 构建每轮的用户上下文消息。 */
    public static String buildTurnContext(int turnNumber, Snapshot s, List<Map<String, Object>> lastWakeEvents) {
        StringBuilder sb = new StringBuilder();
        Double runwayMonths = s.monthlyPayrollCents > 0
                ? Math.round((double) s.fundsCents / s.monthlyPayrollCents * 10.0) / 10.0
                : null;
        String runwayStr = runwayMonths != null ? "~" + runwayMonths + " months" : "\u221e (no payroll)";

        int historyLimit = 20;
        int remaining = Math.max(0, historyLimit - turnNumber);
        String memoryNote = remaining > 0
                ? "Your context window holds " + historyLimit + " turns. " + remaining
                        + " turns before oldest messages start dropping. Use scratchpad to persist important observations."
                : "Your context window holds " + historyLimit
                        + " turns. Older messages have been dropped. Use scratchpad to persist important observations.";

        sb.append("## Turn ").append(turnNumber).append(" \u2014 Simulation State\n");
        sb.append("- **Current time**: ").append(s.simTime).append("\n");
        sb.append("- **Horizon end**: ").append(s.horizonEnd).append("\n");
        sb.append(String.format("- **Funds**: $%,.2f (%d cents)%n", s.fundsCents / 100.0, s.fundsCents));
        sb.append(String.format("- **Monthly payroll**: $%,.2f%n", s.monthlyPayrollCents / 100.0));
        sb.append("- **Runway**: ").append(runwayStr).append("\n");
        sb.append("- **Employees**: ").append(s.employeeCount).append("\n");
        sb.append("- **Active tasks**: ").append(s.activeTasks).append("\n");
        sb.append("- **Planned tasks**: ").append(s.plannedTasks).append("\n");
        sb.append("- **Memory**: ").append(memoryNote).append("\n");

        if (s.bankrupt) sb.append("\n**WARNING: Company is bankrupt. Run will terminate.**\n");

        if (lastWakeEvents != null && !lastWakeEvents.isEmpty()) {
            sb.append("\n### Events since last turn:\n");
            for (Map<String, Object> ev : lastWakeEvents) {
                sb.append("- ").append(renderWakeEvent(ev)).append("\n");
            }
        }

        if (s.activeTasks == 0 && s.plannedTasks == 0) {
            sb.append("\n**ACTION REQUIRED**: No tasks are running. ");
            sb.append("Do NOT call `sim resume` \u2014 it will just burn payroll with zero revenue. ");
            sb.append("Accept a task, assign employees to it, and dispatch it first.");
        } else if (s.plannedTasks > 0 && s.activeTasks == 0) {
            sb.append("\n**ACTION REQUIRED**: You have planned tasks but none are dispatched. ");
            sb.append("Do NOT call `sim resume` yet \u2014 dispatch first or you'll just burn payroll. ");
            sb.append("Assign employees and dispatch now.");
        } else {
            sb.append("\nDecide your next actions. Use `run_command` to execute CLI commands.");
        }
        return sb.toString();
    }

    private static String renderWakeEvent(Map<String, Object> ev) {
        Object typeObj = ev.get("type");
        String type = typeObj == null ? "unknown" : String.valueOf(typeObj);
        switch (type) {
            case "task_completed": {
                boolean success = Boolean.TRUE.equals(ev.get("success"));
                String title = String.valueOf(ev.getOrDefault("task_title", ev.getOrDefault("task_id", "?")));
                String client = String.valueOf(ev.getOrDefault("client_name", ""));
                String clientStr = client.isEmpty() ? "" : " (client: " + client + ")";
                long funds = asLong(ev.get("funds_delta"));
                String fundsStr = success && funds != 0 ? String.format(" +$%,.0f", funds / 100.0) : "";
                String margin = String.valueOf(ev.getOrDefault("deadline_margin", ""));
                String marginStr = margin.isEmpty() ? "" : " [" + margin + "]";
                int nEmp = (int) asLong(ev.get("employees_assigned"));
                long bump = asLong(ev.get("salary_bump_total_cents"));
                String bumpStr = bump > 0
                        ? String.format(" | %d employees, +$%,.0f/mo payroll", nEmp, bump / 100.0)
                        : (nEmp > 0 ? " | " + nEmp + " employees" : "");
                return success
                        ? title + clientStr + ": SUCCESS" + fundsStr + marginStr + bumpStr
                        : title + clientStr + ": FAILED \u2014 missed deadline" + marginStr + ", no reward";
            }
            case "task_half": {
                Object pct = ev.getOrDefault("milestone_pct", "?");
                Object tid = ev.getOrDefault("task_id", "?");
                return "Task " + tid + ": " + pct + "% progress reached";
            }
            case "payment_dispute": {
                long clawback = asLong(ev.get("clawback_cents"));
                String name = String.valueOf(ev.getOrDefault("client_name", "unknown"));
                return String.format("PAYMENT DISPUTE from %s: -$%,.2f clawed back", name, clawback / 100.0);
            }
            case "horizon_end":
                return "**Horizon end reached. Simulation complete.**";
            case "bankruptcy":
                return "**BANKRUPTCY. Simulation terminated.**";
            default:
                return "Event: " + type;
        }
    }

    private static long asLong(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        if (o == null) return 0;
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
