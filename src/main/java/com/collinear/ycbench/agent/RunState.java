package com.collinear.ycbench.agent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单个基准测试运行的可变状态。镜像 {@code agent/run_state.py}。
 *
 * <p>跟踪每轮转录、终止状态和多回合记录。
 */
public final class RunState {

    public enum TerminalReason {
        BANKRUPTCY("bankruptcy"),
        HORIZON_END("horizon_end"),
        ERROR("error");

        public final String value;

        TerminalReason(String value) {
            this.value = value;
        }
    }

    public static final class TranscriptEntry {
        public int turn;
        public String timestamp;
        public String userInput;
        public String agentOutput;
        public List<String> commandsExecuted = new ArrayList<>();
        public int promptTokens;
        public int completionTokens;
        public double costUsd;
    }

    public final String sessionId;
    public final long seed;
    public final String model;
    public final int horizonYears;

    public int turnCount;
    public boolean terminal;
    public TerminalReason terminalReason;
    public String terminalDetail;
    public String startedAt;
    public String endedAt;
    public List<TranscriptEntry> transcript = new ArrayList<>();
    /** 携带用户消息到下一轮（在 sim resume 后设置）。 */
    public String nextUserInput;
    public double totalCostUsd;

    /** 多回合记录（每个回合一个快照）。 */
    public int currentEpisode = 1;
    public List<Map<String, Object>> episodeResults = new ArrayList<>();

    public RunState(String sessionId, long seed, String model, int horizonYears) {
        this.sessionId = sessionId;
        this.seed = seed;
        this.model = model;
        this.horizonYears = horizonYears;
    }

    public void start() {
        startedAt = Instant.now().toString();
    }

    public void recordTurn(String userInput, String agentOutput, List<String> commands,
                           double turnCostUsd, int promptTokens, int completionTokens) {
        turnCount++;
        totalCostUsd += turnCostUsd;
        TranscriptEntry e = new TranscriptEntry();
        e.turn = turnCount;
        e.timestamp = Instant.now().toString();
        e.userInput = userInput;
        e.agentOutput = agentOutput;
        e.commandsExecuted = commands == null ? new ArrayList<>() : new ArrayList<>(commands);
        e.promptTokens = promptTokens;
        e.completionTokens = completionTokens;
        e.costUsd = turnCostUsd;
        transcript.add(e);
    }

    public void markTerminal(TerminalReason reason, String detail) {
        terminal = true;
        terminalReason = reason;
        terminalDetail = detail;
        endedAt = Instant.now().toString();
    }

    /** 快照当前回合并追加到 episode_results。 */
    public Map<String, Object> finishEpisode() {
        Map<String, Object> ep = new LinkedHashMap<>();
        ep.put("episode", currentEpisode);
        ep.put("turns_completed", turnCount);
        ep.put("terminal_reason", terminalReason == null ? null : terminalReason.value);
        ep.put("terminal_detail", terminalDetail);
        ep.put("cost_usd", round6(totalCostUsd));
        ep.put("started_at", startedAt);
        ep.put("ended_at", endedAt);
        ep.put("transcript", transcriptAsMaps());
        episodeResults.add(ep);
        return ep;
    }

    public void resetForNewEpisode() {
        currentEpisode++;
        turnCount = 0;
        terminal = false;
        terminalReason = null;
        terminalDetail = null;
        startedAt = null;
        endedAt = null;
        transcript = new ArrayList<>();
        nextUserInput = null;
        totalCostUsd = 0.0;
    }

    public Map<String, Object> fullRollout() {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("session_id", sessionId);
        base.put("model", model);
        base.put("seed", seed);
        base.put("horizon_years", horizonYears);
        base.put("total_episodes", currentEpisode);
        base.put("terminal", terminal);
        base.put("terminal_reason", terminalReason == null ? null : terminalReason.value);
        base.put("terminal_detail", terminalDetail);
        base.put("started_at", startedAt);
        base.put("ended_at", endedAt);

        if (!episodeResults.isEmpty()) {
            int totalTurns = 0;
            double totalCost = 0.0;
            for (Map<String, Object> ep : episodeResults) {
                totalTurns += (int) ep.getOrDefault("turns_completed", 0);
                Object c = ep.get("cost_usd");
                if (c instanceof Number) totalCost += ((Number) c).doubleValue();
            }
            base.put("turns_completed", totalTurns);
            base.put("total_cost_usd", round6(totalCost));
            base.put("episodes", episodeResults);
        } else {
            base.put("turns_completed", turnCount);
            base.put("total_cost_usd", round6(totalCostUsd));
            base.put("transcript", transcriptAsMaps());
        }
        return base;
    }

    public Map<String, Object> summary() {
        Map<String, Object> rollout = fullRollout();
        rollout.remove("transcript");
        Object episodes = rollout.get("episodes");
        if (episodes instanceof List) {
            for (Object ep : (List<?>) episodes) {
                if (ep instanceof Map) ((Map<?, ?>) ep).remove("transcript");
            }
        }
        return rollout;
    }

    private List<Map<String, Object>> transcriptAsMaps() {
        List<Map<String, Object>> out = new ArrayList<>(transcript.size());
        for (TranscriptEntry t : transcript) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("turn", t.turn);
            m.put("timestamp", t.timestamp);
            m.put("user_input", t.userInput);
            m.put("agent_output", t.agentOutput);
            m.put("commands_executed", t.commandsExecuted);
            m.put("prompt_tokens", t.promptTokens);
            m.put("completion_tokens", t.completionTokens);
            m.put("cost_usd", t.costUsd);
            out.add(m);
        }
        return out;
    }

    private static double round6(double v) {
        return Math.round(v * 1_000_000.0) / 1_000_000.0;
    }
}
