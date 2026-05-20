package com.collinear.ycbench.web;

import com.collinear.ycbench.runner.RunArgs;
import com.collinear.ycbench.runner.RunCmdMain;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
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
        // 获取运行控制状态
        app.get("/api/run/status", ctx -> {
            ctx.json(Map.of(
                    "status", status.get().name(),
                    "model", defaultModel,
                    "seed", defaultSeed,
                    "config", defaultConfig,
                    "lastError", lastError != null ? lastError : "",
                    "lastExitCode", lastExitCode
            ));
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

            String model = getStringOr(body, "model", defaultModel);
            long seed = getLongOr(body, "seed", defaultSeed);
            String config = getStringOr(body, "config", defaultConfig);

            startSimulation(model, seed, config);
            ctx.json(Map.of("status", "RUNNING", "model", model, "seed", seed, "config", config));
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
                // 等待线程停止（最多5秒）
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

            String model = getStringOr(body, "model", defaultModel);
            long seed = getLongOr(body, "seed", defaultSeed);
            String config = getStringOr(body, "config", defaultConfig);

            // 删除旧数据库文件以触发重跑
            cleanDbFiles(model, seed, config);

            startSimulation(model, seed, config);
            ctx.json(Map.of("status", "RUNNING", "model", model, "seed", seed, "config", config, "restarted", true));
        });
    }

    private static void startSimulation(String model, long seed, String config) {
        status.set(RunStatus.RUNNING);
        lastError = null;
        lastExitCode = -1;

        RunArgs args = new RunArgs();
        args.model = model;
        args.seed = seed;
        args.configName = config;
        args.noLive = true;
        args.maxEpisodes = 1;

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
