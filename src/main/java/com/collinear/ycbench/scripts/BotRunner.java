package com.collinear.ycbench.scripts;

import com.collinear.ycbench.config.ConfigLoader;
import com.collinear.ycbench.config.PyRandom;
import com.collinear.ycbench.config.WorldConfig;
import com.collinear.ycbench.core.BusinessTime;
import com.collinear.ycbench.core.AdvanceResult;
import com.collinear.ycbench.core.Engine;
import com.collinear.ycbench.core.Eta;
import com.collinear.ycbench.core.EventOps;
import com.collinear.ycbench.config.ExperimentConfig;
import com.collinear.ycbench.db.Database;
import com.collinear.ycbench.db.dao.ClientDao;
import com.collinear.ycbench.db.dao.CompanyDao;
import com.collinear.ycbench.db.dao.EmployeeDao;

import com.collinear.ycbench.db.dao.SimStateDao;
import com.collinear.ycbench.db.dao.TaskDao;
import com.collinear.ycbench.db.model.Client;
import com.collinear.ycbench.db.model.ClientTrust;
import com.collinear.ycbench.db.model.Company;
import com.collinear.ycbench.db.model.CompanyPrestige;
import com.collinear.ycbench.db.model.Domain;
import com.collinear.ycbench.db.model.Employee;
import com.collinear.ycbench.db.model.EventType;
import com.collinear.ycbench.db.model.SimEvent;
import com.collinear.ycbench.db.model.SimState;
import com.collinear.ycbench.db.model.Task;
import com.collinear.ycbench.db.model.TaskAssignment;
import com.collinear.ycbench.db.model.TaskRequirement;
import com.collinear.ycbench.db.model.TaskStatus;
import com.collinear.ycbench.runner.Extract;
import com.collinear.ycbench.services.GenerateTasks;
import com.collinear.ycbench.services.RngStreams;
import com.collinear.ycbench.services.SeedWorld;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bot 运行器 — {@code scripts/bot_runner.py} 的 Java 端口。
 *
 * <p>使用直接数据库访问和可插拔策略来玩 YC-Bench：
 * {@code greedy}、{@code random}、{@code throughput}、{@code prestige}。每个 bot
 * 在与 LLM 代理相同的经济规则下运行——区别仅在于任务选择策略。
 *
 * <p>用法：
 * <pre>
 *   java -cp ... com.collinear.ycbench.scripts.BotRunner [--bot NAME] [--config NAME] [--seed N]
 * </pre>
 * 默认：每个 bot × 每个配置 × 每个种子。
 */
public final class BotRunner {

    private static final String[] CONFIGS = {"default"};
    private static final int[] SEEDS = {1, 2, 3};
    private static final int MAX_CONCURRENT_TASKS = 1;     // baseline: 1 at a time.

    /** 层级平均速率（bot 对每域技能盲目）。 */
    private static final Map<String, Double> TIER_AVG_RATE = Map.of(
            "junior", 2.0, "mid", 3.5, "senior", 5.0
    );

    @FunctionalInterface
    interface StrategyFn {
        Candidate pick(List<Candidate> candidates, Context ctx);
    }

    static final class Candidate {
        final Task task;
        final long rewardCents;
        final double prestigeDelta;
        final double completionHours;

        Candidate(Task task, long rewardCents, double prestigeDelta, double hours) {
            this.task = task;
            this.rewardCents = rewardCents;
            this.prestigeDelta = prestigeDelta;
            this.completionHours = hours;
        }
    }

    static final class Context {
        final long seed;
        final int turn;
        final double maxPrestige;

        Context(long seed, int turn, double maxPrestige) {
            this.seed = seed;
            this.turn = turn;
            this.maxPrestige = maxPrestige;
        }
    }

    static final class RunResult {
        String config;
        long seed;
        String bot;
        int turns;
        long finalBalanceCents;
        boolean bankrupt;
        int tasksCompleted;
        int tasksFailed;
        double maxPrestige;
        String resultPath;
    }

    private BotRunner() { }

    // ---------------------------------------------------------------
    // 策略 — 候选列表上的纯函数。
    // ---------------------------------------------------------------

    static Candidate strategyGreedy(List<Candidate> cs, Context ctx) {
        return cs.isEmpty() ? null : cs.stream().max(Comparator.comparingLong(c -> c.rewardCents)).orElse(null);
    }

    static Candidate strategyRandom(List<Candidate> cs, Context ctx) {
        if (cs.isEmpty()) return null;
        PyRandom rng = new RngStreams(ctx.seed).stream("bot_random_select:" + ctx.turn);
        return cs.get((int) rng.randbelow(cs.size()));
    }

    static Candidate strategyThroughput(List<Candidate> cs, Context ctx) {
        if (cs.isEmpty()) return null;
        return cs.stream()
                .max(Comparator.comparingDouble(c -> c.rewardCents / Math.max(1e-9, c.completionHours)))
                .orElse(null);
    }

    static Candidate strategyPrestige(List<Candidate> cs, Context ctx) {
        if (cs.isEmpty()) return null;
        if (ctx.maxPrestige < 5) {
            List<Candidate> withDelta = cs.stream().filter(c -> c.prestigeDelta > 0).collect(java.util.stream.Collectors.toList());
            if (!withDelta.isEmpty()) {
                return withDelta.stream()
                        .max(Comparator.comparingDouble(c -> c.prestigeDelta / Math.max(1e-9, c.completionHours)))
                        .orElse(null);
            }
        }
        return strategyThroughput(cs, ctx);
    }

    private static final Map<String, Object[]> STRATEGIES = new LinkedHashMap<>();
    static {
        STRATEGIES.put("greedy",     new Object[]{"greedy_bot",     (StrategyFn) BotRunner::strategyGreedy});
        STRATEGIES.put("random",     new Object[]{"random_bot",     (StrategyFn) BotRunner::strategyRandom});
        STRATEGIES.put("throughput", new Object[]{"throughput_bot", (StrategyFn) BotRunner::strategyThroughput});
        STRATEGIES.put("prestige",   new Object[]{"prestige_bot",   (StrategyFn) BotRunner::strategyPrestige});
    }

    // ---------------------------------------------------------------
    // 候选构建 — 与 LLM 操作的约束相同。
    // ---------------------------------------------------------------

    static double estimateCompletionHours(List<TaskRequirement> reqs, List<String> empTiers, int nConcurrent) {
        double totalRate = 0;
        for (String t : empTiers) totalRate += TIER_AVG_RATE.getOrDefault(t, 0.0);
        double eff = totalRate / Math.max(1, nConcurrent);
        if (eff <= 0) return Double.POSITIVE_INFINITY;
        double max = 0;
        for (TaskRequirement r : reqs) max = Math.max(max, r.requiredQty / eff);
        return max;
    }

    static List<Candidate> buildCandidates(Connection db, UUID companyId, List<String> empTiers, int nActive) throws Exception {
        Map<Domain, Double> prestige = new EnumMap<>(Domain.class);
        for (CompanyPrestige p : CompanyDao.listPrestige(db, companyId)) prestige.put(p.domain, p.prestigeLevel);

        Map<UUID, Double> trustMap = new HashMap<>();
        for (ClientTrust ct : ClientDao.listTrustByCompany(db, companyId)) trustMap.put(ct.clientId, ct.trustLevel);

        List<Task> marketTasks = new ArrayList<>();
        // Market tasks have company_id IS NULL; iterate all tasks and filter.
        try (java.sql.PreparedStatement ps = db.prepareStatement(
                "SELECT id FROM tasks WHERE status = 'market'");
             java.sql.ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Task t = TaskDao.findById(db, UUID.fromString(rs.getString(1)));
                if (t != null) marketTasks.add(t);
            }
        }
        // sort by reward desc (matches Python order)
        marketTasks.sort(Comparator.comparingLong((Task t) -> t.rewardFundsCents).reversed());

        List<Candidate> out = new ArrayList<>();
        for (Task t : marketTasks) {
            List<TaskRequirement> reqs = TaskDao.listRequirements(db, t.id);
            boolean meetsP = reqs.stream()
                    .allMatch(r -> prestige.getOrDefault(r.domain, 1.0) >= t.requiredPrestige);
            if (!meetsP) continue;
            if (t.requiredTrust > 0 && t.clientId != null) {
                double tr = trustMap.getOrDefault(t.clientId, 0.0);
                if (tr < t.requiredTrust) continue;
            }
            double hours = estimateCompletionHours(reqs, empTiers, Math.max(1, nActive + 1));
            out.add(new Candidate(t, t.rewardFundsCents, t.rewardPrestigeDelta, hours));
        }
        return out;
    }

    // ---------------------------------------------------------------
    // 每个 (bot,config,seed) 的主模拟。
    // ---------------------------------------------------------------

    static RunResult runBot(String configName, long seed, String botSlug, StrategyFn strategy) throws Exception {
        // Force-load the experiment config so SeedWorld picks it up.
        System.setProperty("YC_BENCH_EXPERIMENT", configName);
        ExperimentConfig loaded = ConfigLoader.load(configName);
        WorldConfig worldCfg = loaded.world;
        int horizonYears = loaded.sim.horizonYears;
        int empCount = worldCfg.numEmployees;
        int taskCount = worldCfg.numMarketTasks;

        Path dbDir = Path.of("db");
        Files.createDirectories(dbDir);
        Path dbFile = dbDir.resolve(configName + "_" + seed + "_" + botSlug + ".db");
        Files.deleteIfExists(dbFile);
        String url = "jdbc:sqlite:" + dbFile.toAbsolutePath();
        System.setProperty("DATABASE_URL", url);

        UUID companyId;
        try (Connection db = Database.open(url)) {
            db.setAutoCommit(true);
            Database.initSchema(db);

            OffsetDateTime startDt = OffsetDateTime.of(2025, 1, 1, 9, 0, 0, 0, ZoneOffset.UTC);
            OffsetDateTime horizonEnd = startDt.plusYears(horizonYears);

            SeedWorld.Request req = new SeedWorld.Request(
                    seed, botSlug.replace("_", " "), horizonYears, empCount, taskCount, worldCfg, startDt);
            SeedWorld.Result result = SeedWorld.seed(db, req);
            companyId = result.companyId;

            EventOps.insertEvent(db, companyId, EventType.HORIZON_END, horizonEnd,
                    Map.of("reason", "horizon_end"), "horizon_end");

            SimState st = new SimState();
            st.companyId = companyId;
            st.simTime = startDt;
            st.runSeed = (int) seed;
            st.horizonEnd = horizonEnd;
            st.replenishCounter = 0;
            SimStateDao.insert(db, st);
        }

        int tasksCompleted = 0, tasksFailed = 0, turn = 0;
        boolean bankrupt = false;

        outer:
        while (true) {
            turn++;
            try (Connection db = Database.open(url)) {
                db.setAutoCommit(true);
                SimState st = SimStateDao.findFirst(db);
                Company co = CompanyDao.findById(db, companyId);
                if (co.fundsCents < 0) { bankrupt = true; break; }
                if (!st.simTime.isBefore(st.horizonEnd)) break;

                int activeCount = TaskDao.countByCompanyAndStatus(db, companyId, TaskStatus.ACTIVE);
                List<UUID> newlyAccepted = new ArrayList<>();

                // Accept at most 1 task per turn — match LLM cadence.
                if (activeCount < MAX_CONCURRENT_TASKS) {
                    List<Employee> emps = EmployeeDao.listByCompany(db, companyId);
                    List<String> tiers = new ArrayList<>();
                    List<UUID> empIds = new ArrayList<>();
                    for (Employee e : emps) { tiers.add(e.tier); empIds.add(e.id); }

                    List<Candidate> cs = buildCandidates(db, companyId, tiers, activeCount);
                    double maxP = 1.0;
                    for (CompanyPrestige p : CompanyDao.listPrestige(db, companyId)) maxP = Math.max(maxP, p.prestigeLevel);
                    Candidate chosen = strategy.pick(cs, new Context(seed, turn, maxP));
                    if (chosen != null) {
                        acceptAndDispatch(db, chosen.task, companyId, st, worldCfg, empIds);
                        newlyAccepted.add(chosen.task.id);
                    }
                }

                if (!newlyAccepted.isEmpty()) {
                    Eta.recalculateEtas(db, companyId, st.simTime, new HashSet<>(newlyAccepted));
                }

                int totalActive = activeCount + newlyAccepted.size();
                SimEvent nextEvent = EventOps.fetchNextEvent(db, companyId, st.horizonEnd);
                if (nextEvent == null) break;

                AdvanceResult adv;
                if (totalActive == 0) {
                    adv = Engine.advanceTime(db, companyId, nextEvent.scheduledAt);
                    if (adv.bankrupt) { bankrupt = true; break; }
                    if (adv.horizonReached) break;
                    continue;
                }
                adv = Engine.advanceTime(db, companyId, nextEvent.scheduledAt);
                for (Map<String, Object> we : adv.wakeEvents) {
                    if ("task_completed".equals(we.get("type"))) {
                        Object suc = we.get("success");
                        if (Boolean.TRUE.equals(suc)) tasksCompleted++;
                        else tasksFailed++;
                    }
                }
                if (adv.bankrupt) { bankrupt = true; break; }
                if (adv.horizonReached) break;
            }
            if (turn > 100000) break outer;          // hard safety stop
        }

        // Snapshot + write result JSON.
        long finalBalance;
        double maxP;
        Map<String, Object> timeSeries;
        try (Connection db = Database.open(url)) {
            db.setAutoCommit(true);
            Company co = CompanyDao.findById(db, companyId);
            finalBalance = co.fundsCents;
            double m = 1.0;
            for (CompanyPrestige p : CompanyDao.listPrestige(db, companyId)) m = Math.max(m, p.prestigeLevel);
            maxP = m;
            timeSeries = Extract.extractTimeSeries(db, companyId);
        }

        Path resultsDir = Path.of("results");
        Files.createDirectories(resultsDir);
        Path resultFile = resultsDir.resolve("yc_bench_result_" + configName + "_" + seed + "_" + botSlug + ".json");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("session_id", "bot-" + seed + "-" + botSlug);
        result.put("model", botSlug);
        result.put("seed", seed);
        result.put("horizon_years", horizonYears);
        result.put("turns_completed", turn);
        result.put("terminal", true);
        result.put("terminal_reason", bankrupt ? "bankrupt" : "horizon_end");
        result.put("terminal_detail", bankrupt ? "bankrupt" : "horizon_end");
        result.put("total_cost_usd", 0);
        result.put("time_series", timeSeries);
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                .writeValue(resultFile.toFile(), result);

        RunResult r = new RunResult();
        r.config = configName;
        r.seed = seed;
        r.bot = botSlug;
        r.turns = turn;
        r.finalBalanceCents = finalBalance;
        r.bankrupt = bankrupt;
        r.tasksCompleted = tasksCompleted;
        r.tasksFailed = tasksFailed;
        r.maxPrestige = maxP;
        r.resultPath = resultFile.toString();
        return r;
    }

    /** 复制 {@code TaskAcceptCmd} + 全部分配 + 分派于一处。 */
    private static void acceptAndDispatch(Connection db, Task task, UUID companyId, SimState st,
                                          WorldConfig cfg, List<UUID> empIds) throws Exception {
        List<TaskRequirement> reqs = TaskDao.listRequirements(db, task.id);

        Client clientRow = task.clientId != null ? ClientDao.findById(db, task.clientId) : null;
        double trustLevel = 0.0;
        if (task.clientId != null) {
            ClientTrust ct = ClientDao.findTrust(db, companyId, task.clientId);
            if (ct != null) trustLevel = ct.trustLevel;
        }
        boolean isRat = clientRow != null && clientRow.loyalty < -0.3;

        if (!isRat && task.clientId != null) {
            double reduction = cfg.trustWorkReductionMax * (trustLevel / cfg.trustMax);
            for (TaskRequirement r : reqs) {
                int reduced = (int) (r.requiredQty * (1 - reduction));
                r.requiredQty = Math.max(200, reduced);
                TaskDao.updateRequirement(db, r.taskId, r.domain, r.requiredQty, r.completedQty);
            }
        }
        reqs = TaskDao.listRequirements(db, task.id);

        double maxQty = 0;
        for (TaskRequirement r : reqs) maxQty = Math.max(maxQty, r.requiredQty);
        OffsetDateTime acceptedAt = st.simTime;
        double biz = Math.max(cfg.deadlineMinBizDays,
                (int) (maxQty / Math.max(1.0, cfg.deadlineQtyPerDay)));
        OffsetDateTime deadline = BusinessTime.addBusinessHours(acceptedAt, biz * 9.0);

        task.advertisedRewardCents = task.rewardFundsCents;

        if (isRat) {
            double intensity = Math.abs(clientRow.loyalty);
            double inflation = Math.max(3.0, cfg.scopeCreepMax * intensity);
            for (TaskRequirement r : reqs) {
                int inflated = (int) (r.requiredQty * (1 + inflation));
                r.requiredQty = Math.min(25000, Math.max(200, inflated));
                TaskDao.updateRequirement(db, r.taskId, r.domain, r.requiredQty, r.completedQty);
            }
        }

        task.status = TaskStatus.PLANNED;
        task.companyId = companyId;
        task.acceptedAt = acceptedAt;
        task.deadline = deadline;
        TaskDao.update(db, task);

        int slot = task.marketSlot != null ? task.marketSlot : 0;
        int generation = TaskDao.countAcceptedForSlot(db, slot);
        int replacedClientIndex = 0;
        List<Client> clientsByName = ClientDao.listAllOrderedByName(db);
        if (task.clientId != null) {
            for (int i = 0; i < clientsByName.size(); i++) {
                if (clientsByName.get(i).id.equals(task.clientId)) { replacedClientIndex = i; break; }
            }
        }
        List<String> specDomains = clientRow != null ? clientRow.specialtyDomains : null;
        GenerateTasks.Generated rep = GenerateTasks.generateReplacement(
                st.runSeed, slot * 1000 + generation,
                task.requiredPrestige, replacedClientIndex, cfg, specDomains);
        Client repClient = clientsByName.isEmpty() ? null
                : clientsByName.get(Math.floorMod(rep.clientIndex, clientsByName.size()));
        Task repRow = new Task();
        repRow.id = UUID.randomUUID();
        repRow.clientId = repClient != null ? repClient.id : null;
        repRow.status = TaskStatus.MARKET;
        repRow.title = rep.title;
        repRow.requiredPrestige = rep.requiredPrestige;
        repRow.rewardFundsCents = rep.rewardFundsCents;
        repRow.rewardPrestigeDelta = rep.rewardPrestigeDelta;
        repRow.skillBoostPct = rep.skillBoostPct;
        repRow.progressMilestonePct = 0;
        repRow.requiredTrust = rep.requiredTrust;
        repRow.marketSlot = slot;
        TaskDao.insert(db, repRow);
        for (Map.Entry<Domain, Double> e : rep.requirements.entrySet()) {
            TaskRequirement tr = new TaskRequirement();
            tr.taskId = repRow.id;
            tr.domain = e.getKey();
            tr.requiredQty = e.getValue();
            tr.completedQty = 0.0;
            TaskDao.insertRequirement(db, tr);
        }

        // 分配所有员工并分派（激活）任务。
        for (UUID eid : empIds) {
            TaskAssignment a = new TaskAssignment();
            a.taskId = task.id;
            a.employeeId = eid;
            a.assignedAt = st.simTime;
            TaskDao.insertAssignment(db, a);
        }
        task.status = TaskStatus.ACTIVE;
        TaskDao.update(db, task);
    }

    // ---------------------------------------------------------------
    // CLI 入口点。
    // ---------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        String filterBot = null, filterConfig = null;
        Long filterSeed = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--bot":    filterBot    = args[++i]; break;
                case "--config": filterConfig = args[++i]; break;
                case "--seed":   filterSeed   = Long.parseLong(args[++i]); break;
                default: System.err.println("Unknown arg: " + args[i]); System.exit(2);
            }
        }

        List<String> bots    = filterBot == null
                ? new ArrayList<>(STRATEGIES.keySet())
                : List.of(filterBot);
        List<String> configs = filterConfig == null ? Arrays.asList(CONFIGS) : List.of(filterConfig);
        List<Long> seeds     = filterSeed == null
                ? Arrays.stream(SEEDS).boxed().mapToLong(Integer::longValue).boxed().collect(java.util.stream.Collectors.toList())
                : List.of(filterSeed);

        int total = bots.size() * configs.size() * seeds.size();
        System.out.println("Running " + total + " bot simulations...\n");

        List<RunResult> all = new ArrayList<>();
        for (String botName : bots) {
            Object[] entry = STRATEGIES.get(botName);
            String slug = (String) entry[0];
            StrategyFn strat = (StrategyFn) entry[1];
            for (String cfg : configs) {
                for (long seed : seeds) {
                    System.out.print("  " + slug + " | " + cfg + " seed=" + seed + " ... ");
                    System.out.flush();
                    RunResult r = runBot(cfg, seed, slug, strat);
                    all.add(r);
                    String tag = r.bankrupt ? "BANKRUPT" : String.format("$%,d", r.finalBalanceCents / 100);
                    System.out.printf("%s | %d OK, %d fail | prestige %.1f | %d turns%n",
                            tag, r.tasksCompleted, r.tasksFailed, r.maxPrestige, r.turns);
                }
            }
        }

        System.out.println();
        System.out.printf("%-16s %-12s %-5s %14s %4s %5s %9s%n",
                "Bot", "Config", "Seed", "Final Balance", "OK", "Fail", "Prestige");
        System.out.println("----------------------------------------------------------------------");
        for (RunResult r : all) {
            String fb = r.bankrupt ? "BANKRUPT" : String.format("$%,d", r.finalBalanceCents / 100);
            System.out.printf("%-16s %-12s %-5d %14s %4d %5d %8.1f%n",
                    r.bot, r.config, r.seed, fb, r.tasksCompleted, r.tasksFailed, r.maxPrestige);
        }
        long bankruptCount = all.stream().filter(r -> r.bankrupt).count();
        System.out.println("\nBankruptcies: " + bankruptCount + "/" + all.size());
    }
}
