package com.collinear.ycbench.agent;

import com.collinear.ycbench.agent.runtime.AgentRuntime;
import com.collinear.ycbench.agent.runtime.TurnRequest;
import com.collinear.ycbench.agent.runtime.TurnResult;
import com.collinear.ycbench.db.Database;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Agent 基准测试运行的驱动器。镜像 {@code agent/loop.py:run_agent_loop}。
 *
 * <p>每次迭代：
 * <ol>
 *   <li>读取数据库快照，构建用户上下文消息</li>
 *   <li>委托给运行时执行 LLM 调用 + 工具执行</li>
 *   <li>如果 agent 调用了 {@code sim resume}，解析载荷并安排后续用户消息或标记运行终止（破产/时间范围结束）</li>
 *   <li>如果连续太多轮次没有 resume，通过 {@link CommandExecutor} 强制执行一次</li>
 * </ol>
 */
public final class AgentLoop {

    private static final Logger LOGGER = Logger.getLogger(AgentLoop.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();

    public interface TurnHook {
        void onTurn(Prompt.Snapshot snapshot, RunState state, List<String> commands);
    }

    public interface TurnStartHook {
        void onStart(int turnNumber);
    }

    public static RunState run(AgentRuntime runtime,
                               UUID companyId,
                               RunState runState,
                               CommandExecutor commandExecutor,
                               int autoAdvanceAfterTurns,
                               Integer maxTurns,
                               TurnStartHook onTurnStart,
                               TurnHook onTurn,
                               int episode) {
        runState.start();
        int turnsSinceResume = 0;

        LOGGER.info(String.format(
                "Starting agent loop: model=%s seed=%d auto_advance_after=%d max_turns=%s",
                runState.model, runState.seed, autoAdvanceAfterTurns,
                maxTurns == null ? "unlimited" : maxTurns.toString()));

        while (!runState.terminal) {
            if (maxTurns != null && runState.turnCount >= maxTurns) {
                runState.markTerminal(RunState.TerminalReason.ERROR, "max_turns=" + maxTurns + " reached");
                break;
            }
            int turnNum = runState.turnCount + 1;

            Prompt.Snapshot snapshot;
            try (Connection db = openDb()) {
                snapshot = StateSnapshot.read(db, companyId);
            } catch (Exception ex) {
                runState.markTerminal(RunState.TerminalReason.ERROR, "snapshot failed: " + ex.getMessage());
                break;
            }

            String userInput;
            if (runState.turnCount == 0) {
                userInput = Prompt.buildInitialUserPrompt(snapshot, episode);
            } else if (runState.nextUserInput != null) {
                userInput = runState.nextUserInput;
                runState.nextUserInput = null;
            } else {
                userInput = Prompt.buildTurnContext(turnNum, snapshot, null);
            }

            if (onTurnStart != null) onTurnStart.onStart(turnNum);

            TurnResult result;
            try {
                result = runtime.runTurn(new TurnRequest(runState.sessionId, userInput, snapshot.scratchpad));
            } catch (Exception ex) {
                LOGGER.warning("Runtime error on turn " + turnNum + ": " + ex.getMessage());
                runState.markTerminal(RunState.TerminalReason.ERROR, ex.getMessage());
                break;
            }

            List<String> commandsExecuted = extractCommands(result);
            Map<String, Object> resumePayload = result.resumePayload;

            if (result.checkpointAdvanced && resumePayload != null) {
                turnsSinceResume = 0;
            } else {
                turnsSinceResume++;
                if (commandExecutor != null && turnsSinceResume >= autoAdvanceAfterTurns) {
                    LOGGER.info("Auto-advancing after " + turnsSinceResume + " idle turns.");
                    Map<String, Object> auto = autoResume(commandExecutor);
                    if (auto != null) {
                        resumePayload = auto;
                        turnsSinceResume = 0;
                    }
                }
            }

            if (resumePayload != null) {
                List<Map<String, Object>> wakeEvents = asMapList(resumePayload.get("wake_events"));
                Prompt.Snapshot post;
                try (Connection db = openDb()) {
                    post = StateSnapshot.read(db, companyId);
                } catch (Exception ex) {
                    runState.markTerminal(RunState.TerminalReason.ERROR, ex.getMessage());
                    break;
                }
                runState.nextUserInput = Prompt.buildTurnContext(runState.turnCount + 1, post, wakeEvents);
                Object reasonObj = resumePayload.get("terminal_reason");
                if (reasonObj != null) {
                    String reason = String.valueOf(reasonObj);
                    if ("bankruptcy".equals(reason)) {
                        runState.markTerminal(RunState.TerminalReason.BANKRUPTCY, reason);
                    } else if ("horizon_end".equals(reason)) {
                        runState.markTerminal(RunState.TerminalReason.HORIZON_END, reason);
                    }
                }
            }

            runState.recordTurn(userInput, result.finalOutput, commandsExecuted,
                    result.turnCostUsd, result.promptTokens, result.completionTokens);

            if (onTurn != null) {
                try (Connection db = openDb()) {
                    Prompt.Snapshot post = StateSnapshot.read(db, companyId);
                    onTurn.onTurn(post, runState, commandsExecuted);
                } catch (Exception ignored) {
                }
            }

            LOGGER.info(String.format("Turn %d complete. output_len=%d cmds=%d",
                    turnNum, result.finalOutput == null ? 0 : result.finalOutput.length(),
                    commandsExecuted.size()));
        }

        LOGGER.info(String.format("Agent loop finished: turns=%d terminal=%s reason=%s",
                runState.turnCount, runState.terminal,
                runState.terminalReason == null ? null : runState.terminalReason.value));
        return runState;
    }

    private static Connection openDb() throws Exception {
        Connection c = Database.open();
        c.setAutoCommit(true);
        Database.initSchema(c);
        return c;
    }

    private static List<String> extractCommands(TurnResult result) {
        List<String> out = new ArrayList<>();
        if (result.toolCalls == null) return out;
        for (Map<String, Object> tc : result.toolCalls) {
            Object cmd = tc.get("command");
            if (cmd == null) continue;
            String cs = String.valueOf(cmd);
            Object res = tc.get("result");
            if (res == null) {
                out.add(cs);
            } else {
                String rs = String.valueOf(res);
                if (rs.length() > 500) rs = rs.substring(0, 500);
                out.add(cs + " -> " + rs);
            }
        }
        return out;
    }

    private static Map<String, Object> autoResume(CommandExecutor commandExecutor) {
        CommandExecutor.Result r = commandExecutor.run("yc-bench sim resume");
        try {
            String stdout = r.stdout == null ? "" : r.stdout.trim();
            if (stdout.isEmpty()) return null;
            JsonNode node = JSON.readTree(stdout);
            if (!node.isObject()) return null;
            @SuppressWarnings("unchecked")
            Map<String, Object> m = JSON.convertValue(node, Map.class);
            return m;
        } catch (IOException ex) {
            LOGGER.warning("auto-resume parse failed: " + ex.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asMapList(Object o) {
        if (!(o instanceof List)) return new ArrayList<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object e : (List<?>) o) {
            if (e instanceof Map) {
                out.add(new LinkedHashMap<>((Map<String, Object>) e));
            }
        }
        return out;
    }

    private AgentLoop() {
    }
}
