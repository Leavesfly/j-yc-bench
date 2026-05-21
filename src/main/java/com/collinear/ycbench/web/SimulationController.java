package com.collinear.ycbench.web;

import com.collinear.ycbench.runner.RunArgs;
import com.collinear.ycbench.runner.RunCmdMain;
import com.collinear.ycbench.agent.AgentLoop;
import com.collinear.ycbench.scripts.BotRunner;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 仿真运行控制器 — 提供启动/中止/重跑 API。
 * 管理后台仿真线程的生命周期。
 */
public final class SimulationController {

    private static final Logger LOG = LoggerFactory.getLogger(SimulationController.class);

    /** 仿真运行状态 */
    public enum RunStatus { IDLE, RUNNING, STOPPING, COMPLETED, ERROR }

    private static final AtomicReference<RunStatus> status = new AtomicReference<>(RunStatus.IDLE);
    private static volatile Thread simThread = null;
    private static volatile String lastError = null;
    private static volatile int lastExitCode = -1;

    // 当前运行的元信息（用于刷新恢复）
    private static volatile String currentMode = null; // "llm" or "bot"
    private static volatile String currentBot = null;
    private static volatile String currentModel = null;
    private static volatile long currentSeed = 0;
    private static volatile String currentConfig = null;
    private static volatile long startTimeMs = 0;

    // 默认运行参数（可通过 WebCmd 传入）
    private static volatile String defaultModel = "ollama/qwen3.5:4b";
    private static volatile long defaultSeed = 1;
    private static volatile String defaultConfig = "default";

    private SimulationController() {}

    public static void setDefaults(String model, long seed, String config) {
        if (model != null && !model.isEmpty()) defaultModel = model;
        defaultSeed = seed;
        if (config != null && !config.isEmpty()) defaultConfig = config;
    }

    public static void register(Javalin app) {
        // 获取运行控制状态（供刷新后恢复）
        app.get("/api/run/status", ctx -> {
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("status", status.get().name());
            result.put("model", defaultModel);
            result.put("seed", defaultSeed);
            result.put("config", defaultConfig);
            result.put("lastError", lastError != null ? lastError : "");
            result.put("lastExitCode", lastExitCode);
            // 当前运行的元信息
            result.put("currentMode", currentMode != null ? currentMode : "");
            result.put("currentBot", currentBot != null ? currentBot : "");
            result.put("currentModel", currentModel != null ? currentModel : "");
            result.put("currentSeed", currentSeed);
            result.put("currentConfig", currentConfig != null ? currentConfig : "");
            result.put("startTimeMs", startTimeMs);
            ctx.json(result);
        });

        // 获取可用 Bot 策略列表
        app.get("/api/run/bots", ctx -> {
            ctx.json(Map.of("bots", BotRunner.availableBots()));
        });

        // 启动仿真
        app.post("/api/run/start", ctx -> {
            if (status.get() == RunStatus.RUNNING) {
                ctx.status(409).json(Map.of("error", "Simulation already running"));
                return;
            }

            // 从请求体获取可选参数
            Map<String, Object> body = Map.of();
            try {
                String raw = ctx.body();
                if (raw != null && !raw.isBlank()) {
                    body = WebServer.mapper().readValue(raw, Map.class);
                }
            } catch (Exception ignored) {}

            String mode = getStringOr(body, "mode", "llm"); // "llm" or "bot"
            String model = getStringOr(body, "model", defaultModel);
            String bot = getStringOr(body, "bot", "greedy");
            long seed = getLongOr(body, "seed", defaultSeed);
            String config = getStringOr(body, "config", defaultConfig);

            if ("bot".equals(mode)) {
                startBotSimulation(bot, seed, config);
                ctx.json(Map.of("status", "RUNNING", "mode", "bot", "bot", bot, "seed", seed, "config", config));
            } else {
                startSimulation(model, seed, config);
                ctx.json(Map.of("status", "RUNNING", "mode", "llm", "model", model, "seed", seed, "config", config));
            }
        });

        // 中止仿真
        app.post("/api/run/stop", ctx -> {
            if (status.get() != RunStatus.RUNNING) {
                ctx.status(409).json(Map.of("error", "No simulation running"));
                return;
            }
            stopSimulation();
            ctx.json(Map.of("status", "STOPPING"));
        });

        // 重跑仿真（先清除旧数据再启动）
        app.post("/api/run/restart", ctx -> {
            // 如果正在运行，先停止
            if (status.get() == RunStatus.RUNNING) {
                stopSimulation();
                for (int i = 0; i < 50 && status.get() == RunStatus.STOPPING; i++) {
                    Thread.sleep(100);
                }
            }

            Map<String, Object> body = Map.of();
            try {
                String raw = ctx.body();
                if (raw != null && !raw.isBlank()) {
                    body = WebServer.mapper().readValue(raw, Map.class);
                }
            } catch (Exception ignored) {}

            String mode = getStringOr(body, "mode", "llm");
            String model = getStringOr(body, "model", defaultModel);
            String bot = getStringOr(body, "bot", "greedy");
            long seed = getLongOr(body, "seed", defaultSeed);
            String config = getStringOr(body, "config", defaultConfig);

            if ("bot".equals(mode)) {
                // Bot 模式：BotRunner 内部自动清理旧文件
                startBotSimulation(bot, seed, config);
                ctx.json(Map.of("status", "RUNNING", "mode", "bot", "bot", bot, "seed", seed, "config", config, "restarted", true));
            } else {
                cleanDbFiles(model, seed, config);
                startSimulation(model, seed, config);
                ctx.json(Map.of("status", "RUNNING", "mode", "llm", "model", model, "seed", seed, "config", config, "restarted", true));
            }
        });
    }

    private static void startBotSimulation(String botName, long seed, String config) {
        status.set(RunStatus.RUNNING);
        lastError = null;
        lastExitCode = -1;

        // 记录运行元信息（供刷新恢复）
        currentMode = "bot";
        currentBot = botName;
        currentModel = null;
        currentSeed = seed;
        currentConfig = config;
        startTimeMs = System.currentTimeMillis();

        // 立即设置 DATABASE_URL，确保运行中刷新也能读到数据
        String botSlug = botName + "_bot";
        String dbPath = "db/" + config + "_" + seed + "_" + botSlug + ".db";
        System.setProperty("DATABASE_URL", "jdbc:sqlite:" + dbPath);

        simThread = new Thread(() -> {
            try {
                LOG.info("🤖 Bot simulation started: bot={}, seed={}, config={}", botName, seed, config);
                broadcastStatus("RUNNING", "Bot '" + botName + "' started (no LLM needed)");

                BotRunner.RunResult result = BotRunner.runByName(botName, config, seed);

                // 确保完成后 DATABASE_URL 仍指向此数据库
                System.setProperty("DATABASE_URL", "jdbc:sqlite:" + dbPath);

                lastExitCode = result.bankrupt ? 1 : 0;
                status.set(RunStatus.COMPLETED);

                // 广播完成结果摘要
                Map<String, Object> summary = new java.util.LinkedHashMap<>();
                summary.put("type", "turn_end");
                summary.put("turn", result.turns);
                summary.put("timestamp", java.time.Instant.now().toString());
                summary.put("terminal", true);
                summary.put("terminalReason", result.bankrupt ? "bankruptcy" : "horizon_end");
                Map<String, Object> snap = new java.util.LinkedHashMap<>();
                snap.put("fundsCents", result.finalBalanceCents);
                snap.put("simTime", "2026-01-01");
                summary.put("snapshot", snap);
                summary.put("commands", List.of(
                        Map.of("command", "Bot strategy: " + botName,
                                "result", String.format("Turns=%d OK=%d Fail=%d Balance=$%,d",
                                        result.turns, result.tasksCompleted, result.tasksFailed, result.finalBalanceCents / 100))
                ));
                WebServer.broadcast(summary);

                LOG.info("✅ Bot completed: bot={} balance=${} ok={} fail={}",
                        botName, result.finalBalanceCents / 100, result.tasksCompleted, result.tasksFailed);
                broadcastStatus("COMPLETED", String.format("Bot '%s' finished — $%,d, %d tasks OK",
                        botName, result.finalBalanceCents / 100, result.tasksCompleted));
            } catch (Exception e) {
                lastError = e.getMessage();
                status.set(RunStatus.ERROR);
                LOG.error("❌ Bot simulation failed", e);
                broadcastStatus("ERROR", e.getMessage());
            }
        }, "bot-runner");
        simThread.setDaemon(true);
        simThread.start();
    }

    private static void startSimulation(String model, long seed, String config) {
        status.set(RunStatus.RUNNING);
        lastError = null;
        lastExitCode = -1;

        // 记录运行元信息（供刷新恢复）
        currentMode = "llm";
        currentBot = null;
        currentModel = model;
        currentSeed = seed;
        currentConfig = config;
        startTimeMs = System.currentTimeMillis();

        // 设置 DATABASE_URL 以便运行中刷新也能读到数据
        String slug = model.replace('/', '_').replace(':', '_');
        String dbPath = "db/" + config + "_" + seed + "_" + slug + ".db";
        System.setProperty("DATABASE_URL", "jdbc:sqlite:" + dbPath);

        RunArgs args = new RunArgs();
        args.model = model;
        args.seed = seed;
        args.configName = config;
        args.noLive = true;
        args.maxEpisodes = 1;

        // 注入实时事件广播回调
        RunEventBroadcaster broadcaster = new RunEventBroadcaster();
        RunCmdMain.setExternalHooks(broadcaster, broadcaster);

        simThread = new Thread(() -> {
            try {
                LOG.info("🚀 Simulation started: model={}, seed={}, config={}", model, seed, config);
                broadcastStatus("RUNNING", "Simulation started");

                int exitCode = RunCmdMain.run(args);
                lastExitCode = exitCode;

                if (status.get() == RunStatus.STOPPING) {
                    status.set(RunStatus.IDLE);
                    LOG.info("⏹ Simulation stopped by user");
                    broadcastStatus("IDLE", "Simulation stopped by user");
                } else {
                    status.set(RunStatus.COMPLETED);
                    LOG.info("✅ Simulation completed with exit code: {}", exitCode);
                    broadcastStatus("COMPLETED", "Simulation finished (exit=" + exitCode + ")");
                }
            } catch (Exception e) {
                lastError = e.getMessage();
                status.set(RunStatus.ERROR);
                LOG.error("❌ Simulation failed", e);
                broadcastStatus("ERROR", e.getMessage());
            } finally {
                RunCmdMain.clearExternalHooks();
            }
        }, "sim-runner");
        simThread.setDaemon(true);
        simThread.start();
    }

    private static void stopSimulation() {
        status.set(RunStatus.STOPPING);
        if (simThread != null && simThread.isAlive()) {
            simThread.interrupt();
            LOG.info("⏹ Stop signal sent to simulation thread");
        }
        broadcastStatus("STOPPING", "Stop signal sent");
    }

    private static void cleanDbFiles(String model, long seed, String config) {
        String slug = model.replace('/', '_').replace(':', '_');
        String baseName = config + "_" + seed + "_" + slug;
        Path dbDir = Path.of("db");

        try {
            Files.deleteIfExists(dbDir.resolve(baseName + ".db"));
            Files.deleteIfExists(dbDir.resolve(baseName + ".transcript.jsonl"));
            Files.deleteIfExists(dbDir.resolve(baseName + ".session.json"));
            LOG.info("🗑 Cleaned old files for: {}", baseName);
        } catch (Exception e) {
            LOG.warn("Clean failed: {}", e.getMessage());
        }
    }

    private static void broadcastStatus(String statusText, String message) {
        WebServer.broadcast(Map.of(
                "type", "run_status",
                "status", statusText,
                "message", message
        ));
    }

    private static String getStringOr(Map<String, Object> map, String key, String fallback) {
        Object val = map.get(key);
        return val != null ? val.toString() : fallback;
    }

    private static long getLongOr(Map<String, Object> map, String key, long fallback) {
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).longValue();
        if (val instanceof String) {
            try { return Long.parseLong((String) val); } catch (Exception e) { return fallback; }
        }
        return fallback;
    }

    public static RunStatus getStatus() {
        return status.get();
    }
}
