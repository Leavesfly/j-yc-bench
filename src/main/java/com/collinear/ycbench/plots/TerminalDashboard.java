package com.collinear.ycbench.plots;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * ANSI 终端仪表板 — 监视结果目录并显示实时摘要表格。
 * {@code scripts/watch_dashboard.py} 报告方面的 Java 移植版本（Streamlit 版本已替换为纯终端）。
 *
 * <p>用法：
 * <pre>
 *   java -cp ... com.collinear.ycbench.plots.TerminalDashboard [--dir results] [--interval 5]
 * </pre>
 *
 * <p>按 Ctrl-C 停止。
 */
public final class TerminalDashboard {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ANSI 代码
    private static final String RESET  = "\u001b[0m";
    private static final String BOLD   = "\u001b[1m";
    private static final String DIM    = "\u001b[2m";
    private static final String GREEN  = "\u001b[32m";
    private static final String RED    = "\u001b[31m";
    private static final String CYAN   = "\u001b[36m";
    private static final String YELLOW = "\u001b[33m";
    private static final String MAGENTA= "\u001b[35m";
    private static final String CLEAR  = "\u001b[2J\u001b[H";

    private TerminalDashboard() { }

    public static void main(String[] args) throws Exception {
        String dir = "results";
        int interval = 5;
        boolean oneShot = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--dir":      dir = args[++i]; break;
                case "--interval": interval = Integer.parseInt(args[++i]); break;
                case "--once":     oneShot = true; break;
                default: System.err.println("Unknown: " + args[i]); System.exit(2);
            }
        }
        Path resultsDir = Paths.get(dir);
        if (!Files.isDirectory(resultsDir)) {
            System.err.println("Results directory not found: " + resultsDir);
            System.exit(1);
        }

        if (oneShot) {
            render(resultsDir);
            return;
        }

        // 监视循环
        while (true) {
            System.out.print(CLEAR);
            render(resultsDir);
            System.out.printf("%n%s[refresh in %ds — Ctrl-C to exit]%s%n", DIM, interval, RESET);
            Thread.sleep(interval * 1000L);
        }
    }

    private static void render(Path resultsDir) throws IOException {
        List<RunSummary> runs = loadAll(resultsDir);
        if (runs.isEmpty()) {
            System.out.println(YELLOW + "No result files found in " + resultsDir + RESET);
            return;
        }

        // 头部
        System.out.printf("%s%s╔══════════════════════════════════════════════════════════════════════════════╗%s%n",
                BOLD, CYAN, RESET);
        System.out.printf("%s%s║              YC-Bench Terminal Dashboard  (%d runs)                         ║%s%n",
                BOLD, CYAN, runs.size(), RESET);
        System.out.printf("%s%s╚══════════════════════════════════════════════════════════════════════════════╝%s%n",
                BOLD, CYAN, RESET);
        System.out.println();

        // 聚合指标
        long totalOk = runs.stream().mapToInt(r -> r.tasksOk).sum();
        long totalFail = runs.stream().mapToInt(r -> r.tasksFail).sum();
        int bankruptCount = (int) runs.stream().filter(r -> r.bankrupt).count();
        double avgBalance = runs.stream().mapToDouble(r -> r.finalBalanceCents / 100.0).average().orElse(0);
        double totalCost = runs.stream().mapToDouble(r -> r.costUsd).sum();

        System.out.printf("  %s%sMetrics%s  Tasks: %s%d OK%s / %s%d fail%s  |  Bankruptcies: %s%d%s/%d  |  Avg Balance: %s$%,.0f%s  |  API Cost: $%.2f%n",
                BOLD, MAGENTA, RESET,
                GREEN, totalOk, RESET,
                RED, totalFail, RESET,
                bankruptCount > 0 ? RED : GREEN, bankruptCount, RESET, runs.size(),
                avgBalance >= 0 ? GREEN : RED, avgBalance, RESET,
                totalCost);
        System.out.println();

        // 表格头部
        System.out.printf("  %s%-20s %-8s %5s %14s %6s %5s %8s %6s %6s%s%n",
                BOLD, "Model", "Config", "Seed", "Final Balance", "Tasks", "Fail", "Prestige", "Turns", "Cost", RESET);
        System.out.printf("  %s%s%s%n", DIM, "─".repeat(78), RESET);

        // 排序：按模型，然后配置，然后种子
        runs.sort(Comparator.comparing((RunSummary r) -> r.model)
                .thenComparing(r -> r.config)
                .thenComparingInt(r -> r.seed));

        for (RunSummary r : runs) {
            String balStr = r.bankrupt ? RED + "BANKRUPT" + RESET
                    : GREEN + String.format("$%,d", r.finalBalanceCents / 100) + RESET;
            String prestStr = r.maxPrestige >= 5 ? YELLOW + String.format("%.1f", r.maxPrestige) + RESET
                    : String.format("%.1f", r.maxPrestige);
            System.out.printf("  %-20s %-8s %5d %14s %6d %5d %8s %6d %6s%n",
                    truncate(r.model, 20), r.config, r.seed,
                    balStr, r.tasksOk, r.tasksFail, prestStr, r.turns,
                    r.costUsd > 0 ? String.format("$%.2f", r.costUsd) : "-");
        }

        // 每个模型的平均值
        Map<String, List<RunSummary>> byModel = new TreeMap<>();
        for (RunSummary r : runs) byModel.computeIfAbsent(r.model, k -> new ArrayList<>()).add(r);
        if (byModel.size() > 1) {
            System.out.printf("%n  %s%sModel Averages%s%n", BOLD, MAGENTA, RESET);
            System.out.printf("  %s%-20s %14s %6s %5s %8s%s%n", BOLD, "Model", "Avg Balance", "Avg OK", "Fail", "Prestige", RESET);
            System.out.printf("  %s%s%s%n", DIM, "─".repeat(60), RESET);
            for (Map.Entry<String, List<RunSummary>> e : byModel.entrySet()) {
                List<RunSummary> group = e.getValue();
                double avgB = group.stream().mapToLong(r -> r.finalBalanceCents).average().orElse(0) / 100;
                double avgOk = group.stream().mapToInt(r -> r.tasksOk).average().orElse(0);
                double avgFail = group.stream().mapToInt(r -> r.tasksFail).average().orElse(0);
                double avgP = group.stream().mapToDouble(r -> r.maxPrestige).average().orElse(0);
                System.out.printf("  %-20s %s%14s%s %6.1f %5.1f %8.1f%n",
                        truncate(e.getKey(), 20),
                        avgB >= 0 ? GREEN : RED, String.format("$%,.0f", avgB), RESET,
                        avgOk, avgFail, avgP);
            }
        }
    }

    // ───── 数据加载 ─────────────────────────────────────────────────────

    static final class RunSummary {
        String model; String config; int seed;
        long finalBalanceCents; boolean bankrupt;
        int tasksOk; int tasksFail; double maxPrestige; int turns; double costUsd;
    }

    private static List<RunSummary> loadAll(Path dir) throws IOException {
        List<RunSummary> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json")) {
            for (Path f : ds) {
                try {
                    RunSummary r = parseResult(f);
                    if (r != null) out.add(r);
                } catch (Exception ignored) { }
            }
        }
        return out;
    }

    private static RunSummary parseResult(Path f) throws IOException {
        JsonNode root = JSON.readTree(f.toFile());
        if (!root.has("model")) return null;

        RunSummary r = new RunSummary();
        r.model = root.path("model").asText("");
        r.seed = root.path("seed").asInt(0);
        r.turns = root.path("turns_completed").asInt(0);
        r.costUsd = root.path("total_cost_usd").asDouble(0);
        r.bankrupt = "bankrupt".equals(root.path("terminal_reason").asText(""));

        // 从文件名推导配置：yc_bench_result_{config}_{seed}_{slug}.json
        String stem = f.getFileName().toString().replace(".json", "");
        String[] parts = stem.split("_", 6);
        r.config = parts.length >= 5 ? parts[3] : "?";

        // 从 time_series 计算余额 + 任务
        JsonNode ts = root.path("time_series");
        JsonNode fundsArr = ts.path("funds");
        if (fundsArr.isArray() && fundsArr.size() > 0) {
            r.finalBalanceCents = fundsArr.get(fundsArr.size() - 1).path("funds_cents").asLong(0);
        }
        JsonNode tasksArr = ts.path("tasks");
        if (tasksArr.isArray()) {
            for (JsonNode t : tasksArr) {
                if (t.path("success").asBoolean(false)) r.tasksOk++;
                else r.tasksFail++;
            }
        }
        // 声望
        JsonNode prestigeArr = ts.path("prestige");
        double maxP = 1.0;
        if (prestigeArr.isArray()) {
            for (JsonNode p : prestigeArr) {
                double lv = p.path("level").asDouble(1.0);
                if (lv > maxP) maxP = lv;
            }
        }
        r.maxPrestige = maxP;
        return r;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
