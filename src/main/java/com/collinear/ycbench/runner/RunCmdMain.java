package com.collinear.ycbench.runner;

import com.collinear.ycbench.agent.AgentLoop;
import com.collinear.ycbench.agent.CommandExecutor;
import com.collinear.ycbench.agent.RunState;
import com.collinear.ycbench.agent.runtime.AgentRuntime;
import com.collinear.ycbench.agent.runtime.HttpLlmRuntime;
import com.collinear.ycbench.agent.runtime.RuntimeSettings;
import com.collinear.ycbench.config.ConfigLoader;
import com.collinear.ycbench.config.ExperimentConfig;
import com.collinear.ycbench.config.WorldConfig;
import com.collinear.ycbench.core.EventOps;
import com.collinear.ycbench.db.Database;
import com.collinear.ycbench.db.dao.CompanyDao;
import com.collinear.ycbench.db.dao.ScratchpadDao;
import com.collinear.ycbench.db.dao.SimStateDao;
import com.collinear.ycbench.db.model.Company;
import com.collinear.ycbench.db.model.EventType;
import com.collinear.ycbench.db.model.Scratchpad;
import com.collinear.ycbench.db.model.SimState;
import com.collinear.ycbench.services.SeedWorld;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * 基准测试入口点。镜像 {@code runner/main.py:run_benchmark}。
 *
 * <p>为每个剧集配置一个 SQLite 数据库，通过 {@link SeedWorld} 种子化世界，
 * 运行代理循环直到终止，然后将完整的 rollout（包括 {@link Extract} 时间序列）写入
 * {@code results/yc_bench_result_<config>_<seed>_<slug>.json}。
 */
public final class RunCmdMain {

    private static final Logger LOGGER = Logger.getLogger(RunCmdMain.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private RunCmdMain() {
    }

    /** 外部注入的额外回调（如 Web 实时广播） */
    private static volatile AgentLoop.TurnHook externalTurnHook = null;
    private static volatile AgentLoop.TurnStartHook externalTurnStartHook = null;

    public static void setExternalHooks(AgentLoop.TurnStartHook startHook, AgentLoop.TurnHook turnHook) {
        externalTurnStartHook = startHook;
        externalTurnHook = turnHook;
    }

    public static void clearExternalHooks() {
        externalTurnStartHook = null;
        externalTurnHook = null;
    }

    public static int run(RunArgs args) {
        // 传播到子进程 CLI 调用（镜像 Python 的 YC_BENCH_EXPERIMENT）。
        // 我们无法在进程中设置环境变量，但 ConfigLoader 通过系统属性获取此值。
        System.setProperty("YC_BENCH_EXPERIMENT", args.configName);

        ExperimentConfig cfg = ConfigLoader.load(args.configName);
        // CLI --model overrides the experiment's agent model (if provided).
        if (args.model != null && !args.model.isEmpty()) {
            cfg.agent.model = args.model;
        }
        String effectiveModel = cfg.agent.model;
        // 确保 args.model 始终有值，供后续辅助方法使用
        args.model = effectiveModel;
        int horizonYears = args.horizonYears != null ? args.horizonYears : cfg.sim.horizonYears;

        LOGGER.info(String.format(
                "YC-Bench starting: experiment=%s model=%s seed=%d horizon=%dy max_episodes=%d",
                cfg.name, effectiveModel, args.seed, horizonYears, args.maxEpisodes));

        RuntimeSettings settings = new RuntimeSettings(
                cfg.agent.model, cfg.agent.baseUrl, cfg.agent.temperature, cfg.agent.topP,
                cfg.agent.requestTimeoutSeconds, cfg.agent.retryMaxAttempts,
                cfg.agent.retryBackoffSeconds, cfg.agent.historyKeepRounds,
                cfg.agent.systemPrompt);
        CommandExecutor cmdExec = new CommandExecutor((long) cfg.agent.requestTimeoutSeconds);
        AgentRuntime runtime = new HttpLlmRuntime(settings, cmdExec);

        String sessionId = "run-" + args.seed + "-" + effectiveModel;
        RunState runState = new RunState(sessionId, args.seed, effectiveModel, horizonYears);

        String carriedScratchpad = "";
        UUID lastCompanyId = null;
        String lastDbUrl = null;

        for (int episode = 1; episode <= args.maxEpisodes; episode++) {
            LOGGER.info("=== Episode " + episode + " / " + args.maxEpisodes + " ===");

            String dbUrl = buildDbUrl(args, episode);
            // CLI 子进程（CommandExecutor）继承环境变量；我们使用系统属性
            // 以便 Database#resolveJdbcUrl 可以通过 DATABASE_URL 回退获取它。
            System.setProperty("DATABASE_URL", dbUrl);
            lastDbUrl = dbUrl;

            UUID companyId;
            try (Connection db = openEpisodeDb(dbUrl)) {
                companyId = initSimulation(db, args, cfg, horizonYears);
                if (episode > 1 && !carriedScratchpad.isEmpty()) {
                    upsertScratchpad(db, companyId, carriedScratchpad);
                    LOGGER.info("Restored scratchpad (" + carriedScratchpad.length() + " chars).");
                }
            } catch (Exception ex) {
                runState.markTerminal(RunState.TerminalReason.ERROR,
                        "init failed: " + ex.getMessage());
                break;
            }
            lastCompanyId = companyId;

            Path transcriptPath = transcriptPath(args);
            Path sessionMessagesPath = sessionMessagesPath(args);
            boolean isResume = Files.exists(transcriptPath) && Files.exists(sessionMessagesPath);
            if (!isResume) {
                // 干净启动：删除任何过时的转录文件，以免混合运行。
                try {
                    Files.deleteIfExists(transcriptPath);
                } catch (IOException ignored) {
                }
            } else {
                int restored = runtime.restoreSessionMessages(sessionId, sessionMessagesPath);
                if (restored > 0) {
                    int priorTurns = countTranscriptLines(transcriptPath);
                    runState.turnCount = priorTurns;
                    LOGGER.info("Resumed: " + priorTurns + " prior turns, "
                            + restored + " session messages.");
                }
            }

            AgentLoop.TurnHook onTurn = (snapshot, rs, commands) -> {
                appendTranscript(transcriptPath, snapshot, rs);
                // 每次轮次后保存会话消息以进行崩溃恢复。
                runtime.saveSessionMessages(sessionId, sessionMessagesPath);
                // 调用外部回调（如 Web 实时广播）
                if (externalTurnHook != null) {
                    try { externalTurnHook.onTurn(snapshot, rs, commands); } catch (Exception ignored) {}
                }
            };

            AgentLoop.run(runtime, companyId, runState, cmdExec,
                    cfg.loop.autoAdvanceAfterTurns, cfg.loop.maxTurns,
                    externalTurnStartHook, onTurn, episode);

            if (args.maxEpisodes > 1) runState.finishEpisode();
            LOGGER.info("Episode " + episode + " finished: reason=" + runState.terminalReason);

            if (runState.terminalReason != RunState.TerminalReason.BANKRUPTCY
                    || episode == args.maxEpisodes) break;

            // 将 scratchpad 向前携带，然后为下一剧集重置。
            try (Connection db = openEpisodeDb(dbUrl)) {
                Scratchpad sp = ScratchpadDao.find(db, companyId);
                carriedScratchpad = sp == null || sp.content == null ? "" : sp.content;
            } catch (Exception ex) {
                LOGGER.warning("scratchpad read failed: " + ex.getMessage());
            }
            runtime.clearSession(sessionId);
            runState.resetForNewEpisode();
        }

        // 最终提取 + rollout 写入。
        Map<String, Object> rollout = runState.fullRollout();
        if (lastCompanyId != null) {
            try (Connection db = openEpisodeDb(lastDbUrl)) {
                rollout.put("time_series", Extract.extractTimeSeries(db, lastCompanyId));
            } catch (Exception ex) {
                LOGGER.warning("extract failed: " + ex.getMessage());
            }
        }

        String slug = args.model.replace('/', '_');
        Path resultsDir = Path.of("results");
        try {
            Files.createDirectories(resultsDir);
            Path out = resultsDir.resolve("yc_bench_result_" + args.configName + "_"
                    + args.seed + "_" + slug + ".json");
            Files.writeString(out, JSON.writeValueAsString(rollout));
            LOGGER.info("Full rollout written to " + out);
        } catch (IOException ex) {
            LOGGER.warning("rollout write failed: " + ex.getMessage());
        }

        try {
            LOGGER.info("Run complete: " + JSON.writeValueAsString(runState.summary()));
        } catch (Exception ignored) {
        }
        return runState.terminalReason == RunState.TerminalReason.ERROR ? 1 : 0;
    }

    // ------------------------------------------------------------------

    private static String buildDbUrl(RunArgs args, int episode) {
        String slug = args.model.replace('/', '_');
        Path dir = Path.of("db");
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
        }
        String base = args.configName + "_" + args.seed + "_" + slug;
        String fname = args.maxEpisodes > 1 ? base + ".ep" + episode + ".db" : base + ".db";
        return "jdbc:sqlite:" + dir.resolve(fname).toAbsolutePath();
    }

    private static Path transcriptPath(RunArgs args) {
        String slug = args.model.replace('/', '_');
        return Path.of("db", args.configName + "_" + args.seed + "_" + slug + ".transcript.jsonl");
    }

    private static Path sessionMessagesPath(RunArgs args) {
        String slug = args.model.replace('/', '_');
        return Path.of("db", args.configName + "_" + args.seed + "_" + slug + ".session.json");
    }

    private static int countTranscriptLines(Path path) {
        try {
            int n = 0;
            for (String line : Files.readAllLines(path)) {
                if (!line.isBlank()) n++;
            }
            return n;
        } catch (IOException ex) {
            return 0;
        }
    }

    private static Connection openEpisodeDb(String url) throws Exception {
        Connection c = Database.open(url);
        c.setAutoCommit(true);
        Database.initSchema(c);
        return c;
    }

    private static UUID initSimulation(Connection db, RunArgs args, ExperimentConfig cfg,
                                       int horizonYears) throws Exception {
        SimState existing = SimStateDao.findFirst(db);
        if (existing != null) {
            Company co = CompanyDao.findById(db, existing.companyId);
            boolean bankrupt = co != null && co.fundsCents < 0;
            boolean horizonReached = !existing.simTime.isBefore(existing.horizonEnd);
            if (bankrupt || horizonReached) {
                LOGGER.info("现有模拟已终止 — 清除以重新种子化。");
                wipeSimulation(db);
            } else {
                LOGGER.info("恢复非终止模拟（公司=" + existing.companyId + "）。");
                return existing.companyId;
            }
        }

        OffsetDateTime startDt = parseStartDate(args.startDate);
        OffsetDateTime horizonEnd = startDt.plusYears(horizonYears);
        WorldConfig wc = cfg.world;

        SeedWorld.Request req = new SeedWorld.Request(
                args.seed, args.companyName, horizonYears,
                wc.numEmployees, wc.numMarketTasks, wc, startDt);
        LOGGER.info(String.format("Initializing simulation: seed=%d employees=%d tasks=%d horizon=%dy",
                args.seed, wc.numEmployees, wc.numMarketTasks, horizonYears));
        SeedWorld.Result result = SeedWorld.seed(db, req);

        EventOps.insertEvent(db, result.companyId, EventType.HORIZON_END, horizonEnd,
                Map.of("reason", "horizon_end"), "horizon_end");

        SimState st = new SimState();
        st.companyId = result.companyId;
        st.simTime = startDt;
        st.runSeed = (int) args.seed;
        st.horizonEnd = horizonEnd;
        st.replenishCounter = 0;
        SimStateDao.insert(db, st);

        LOGGER.info("Simulation initialized: company=" + result.companyId);
        return result.companyId;
    }

    private static OffsetDateTime parseStartDate(String s) {
        DateTimeFormatter f = s.contains("-")
                ? DateTimeFormatter.ofPattern("yyyy-MM-dd")
                : DateTimeFormatter.ofPattern("MM/dd/yyyy");
        LocalDate d = LocalDate.parse(s, f);
        return OffsetDateTime.of(d, java.time.LocalTime.of(9, 0), ZoneOffset.UTC);
    }

    private static void wipeSimulation(Connection db) throws SQLException {
        // 顺序尊重 FK 约束 — 子表优先。
        String[] tables = {
                "scratchpads", "ledger_entries", "task_assignments", "task_requirements",
                "tasks", "sim_events", "employee_skill_rates", "employees",
                "company_prestige", "client_trust", "clients", "companies", "sim_state",
                "agent_sessions", "monthly_metrics"
        };
        try (Statement s = db.createStatement()) {
            for (String t : tables) {
                try {
                    s.execute("DELETE FROM " + t);
                } catch (SQLException ignored) {
                    // 某些表在精简模式中可能不存在。
                }
            }
        }
    }

    private static void upsertScratchpad(Connection db, UUID companyId, String content) throws SQLException {
        ScratchpadDao.upsert(db, companyId, content);
    }

    private static void appendTranscript(Path path, com.collinear.ycbench.agent.Prompt.Snapshot snapshot,
                                         RunState rs) {
        if (rs.transcript.isEmpty()) return;
        RunState.TranscriptEntry e = rs.transcript.get(rs.transcript.size() - 1);
        try {
            Files.createDirectories(path.getParent());
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("turn", e.turn);
            line.put("timestamp", e.timestamp);
            line.put("agent_output", e.agentOutput);
            line.put("commands_executed", e.commandsExecuted);
            line.put("sim_time", snapshot.simTime);
            line.put("funds_cents", snapshot.fundsCents);
            byte[] bytes = (JSON.writeValueAsString(line).replace('\n', ' ') + "\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            LOGGER.fine("transcript append failed: " + ex.getMessage());
        }
    }
}
