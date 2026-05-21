package com.collinear.ycbench.web;

import com.collinear.ycbench.agent.AgentLoop;
import com.collinear.ycbench.agent.Prompt;
import com.collinear.ycbench.agent.RunState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;

/**
 * 将 AgentLoop 的每一步操作实时广播到 WebSocket 客户端。
 * 作为 TurnStartHook 和 TurnHook 注入到 AgentLoop 中。
 */
public final class RunEventBroadcaster implements AgentLoop.TurnStartHook, AgentLoop.TurnHook {

    private static final Logger LOG = LoggerFactory.getLogger(RunEventBroadcaster.class);

    @Override
    public void onStart(int turnNumber) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "turn_start");
        event.put("turn", turnNumber);
        event.put("timestamp", Instant.now().toString());
        WebServer.broadcast(event);
    }

    @Override
    public void onTurn(Prompt.Snapshot snapshot, RunState state, List<String> commands) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "turn_end");
        event.put("turn", state.turnCount);
        event.put("timestamp", Instant.now().toString());

        // 快照状态
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("simTime", snapshot.simTime);
        snap.put("fundsCents", snapshot.fundsCents);
        snap.put("activeTasks", snapshot.activeTasks);
        snap.put("plannedTasks", snapshot.plannedTasks);
        snap.put("employeeCount", snapshot.employeeCount);
        snap.put("monthlyPayrollCents", snapshot.monthlyPayrollCents);
        snap.put("bankrupt", snapshot.bankrupt);
        event.put("snapshot", snap);

        // Agent 执行的命令列表
        List<Map<String, String>> cmdList = new ArrayList<>();
        for (String cmd : commands) {
            Map<String, String> cmdEntry = new LinkedHashMap<>();
            int arrowIdx = cmd.indexOf(" -> ");
            if (arrowIdx > 0) {
                cmdEntry.put("command", cmd.substring(0, arrowIdx));
                String result = cmd.substring(arrowIdx + 4);
                cmdEntry.put("result", result.length() > 300 ? result.substring(0, 300) + "..." : result);
            } else {
                cmdEntry.put("command", cmd);
                cmdEntry.put("result", "");
            }
            cmdList.add(cmdEntry);
        }
        event.put("commands", cmdList);

        // 运行状态
        event.put("terminal", state.terminal);
        if (state.terminalReason != null) {
            event.put("terminalReason", state.terminalReason.value);
        }

        WebServer.broadcast(event);

        // 同时推送 dashboard 更新，让仪表盘数据跟随刷新
        Map<String, Object> dashUpdate = new LinkedHashMap<>();
        dashUpdate.put("type", "dashboard_update");
        dashUpdate.put("payload", snap);
        WebServer.broadcast(dashUpdate);
    }
}
