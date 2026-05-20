package com.collinear.ycbench.plots;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 加载 YC-Bench 结果 JSON（由 {@code RunCmdMain} 或 {@code BotRunner} 生成）
 * 到便于绘图的类型化列表中。
 */
public final class ResultData {

    private static final ObjectMapper JSON = new ObjectMapper();

    public final String filePath;
    public final String model;
    public final int seed;
    public final int turnsCompleted;
    public final String terminalReason;
    public final double totalCostUsd;

    // time_series sub-objects
    public final List<FundsPoint> funds;
    public final List<TaskPoint> tasks;
    public final List<PrestigePoint> prestige;
    public final List<TrustPoint> clientTrust;
    public final List<LedgerPoint> ledger;
    public final List<AssignmentPoint> assignments;
    public final List<TranscriptPoint> transcript;

    // ───── 类型化点类 ─────

    public static final class FundsPoint {
        public final Date time;
        public final long fundsCents;
        FundsPoint(Date t, long fc) { this.time = t; this.fundsCents = fc; }
    }
    public static final class TaskPoint {
        public final Date completedAt;
        public final boolean success;
        public final int requiredTrust;
        TaskPoint(Date t, boolean s, int rt) { this.completedAt = t; this.success = s; this.requiredTrust = rt; }
    }
    public static final class PrestigePoint {
        public final Date time;
        public final String domain;
        public final double level;
        PrestigePoint(Date t, String d, double l) { this.time = t; this.domain = d; this.level = l; }
    }
    public static final class TrustPoint {
        public final Date time;
        public final String clientName;
        public final double trustLevel;
        public final double loyalty;
        TrustPoint(Date t, String cn, double tl, double lo) { this.time = t; this.clientName = cn; this.trustLevel = tl; this.loyalty = lo; }
    }
    public static final class LedgerPoint {
        public final Date time;
        public final String category;
        public final long amountCents;
        LedgerPoint(Date t, String c, long a) { this.time = t; this.category = c; this.amountCents = a; }
    }
    public static final class AssignmentPoint {
        public final Date completedAt;
        public final int numAssigned;
        AssignmentPoint(Date t, int n) { this.completedAt = t; this.numAssigned = n; }
    }
    public static final class TranscriptPoint {
        public final int turn;
        public final int promptTokens;
        public final int completionTokens;
        public final double costUsd;
        TranscriptPoint(int t, int pt, int ct, double c) { this.turn = t; this.promptTokens = pt; this.completionTokens = ct; this.costUsd = c; }
    }

    private ResultData(String fp, String model, int seed, int turns, String reason, double cost,
                       List<FundsPoint> funds, List<TaskPoint> tasks, List<PrestigePoint> prestige,
                       List<TrustPoint> trust, List<LedgerPoint> ledger, List<AssignmentPoint> assign,
                       List<TranscriptPoint> transcript) {
        this.filePath = fp; this.model = model; this.seed = seed;
        this.turnsCompleted = turns; this.terminalReason = reason; this.totalCostUsd = cost;
        this.funds = funds; this.tasks = tasks; this.prestige = prestige;
        this.clientTrust = trust; this.ledger = ledger; this.assignments = assign;
        this.transcript = transcript;
    }

    public String shortName() {
        if (model != null && model.contains("/")) return model.substring(model.lastIndexOf('/') + 1);
        Path p = Path.of(filePath);
        String stem = p.getFileName().toString();
        int dot = stem.lastIndexOf('.');
        if (dot > 0) stem = stem.substring(0, dot);
        String[] parts = stem.split("_", 5);
        return parts.length > 4 ? parts[4] : stem;
    }

    // ───── 加载器 ─────

    public static ResultData load(String path) throws IOException {
        JsonNode root = JSON.readTree(new File(path));
        String model = text(root, "model");
        int seed = intVal(root, "seed");
        int turns = intVal(root, "turns_completed");
        String reason = text(root, "terminal_reason");
        double cost = doubleVal(root, "total_cost_usd");
        JsonNode ts = root.path("time_series");

        List<FundsPoint> funds = new ArrayList<>();
        for (JsonNode n : iter(ts.path("funds"))) {
            funds.add(new FundsPoint(parseDate(n.path("time").asText()), n.path("funds_cents").asLong()));
        }

        List<TaskPoint> tasks = new ArrayList<>();
        for (JsonNode n : iter(ts.path("tasks"))) {
            if (!n.has("completed_at") || n.path("completed_at").isNull()) continue;
            tasks.add(new TaskPoint(
                    parseDate(n.path("completed_at").asText()),
                    n.path("success").asBoolean(false),
                    n.path("required_trust").asInt(0)));
        }

        List<PrestigePoint> prestige = new ArrayList<>();
        for (JsonNode n : iter(ts.path("prestige"))) {
            prestige.add(new PrestigePoint(
                    parseDate(n.path("time").asText()),
                    n.path("domain").asText(),
                    n.path("level").asDouble()));
        }

        List<TrustPoint> trust = new ArrayList<>();
        for (JsonNode n : iter(ts.path("client_trust"))) {
            trust.add(new TrustPoint(
                    parseDate(n.path("time").asText()),
                    n.path("client_name").asText(),
                    n.path("trust_level").asDouble(),
                    n.path("loyalty").asDouble(0)));
        }

        List<LedgerPoint> ledger = new ArrayList<>();
        for (JsonNode n : iter(ts.path("ledger"))) {
            ledger.add(new LedgerPoint(
                    parseDate(n.path("time").asText()),
                    n.path("category").asText(),
                    n.path("amount_cents").asLong()));
        }

        List<AssignmentPoint> assign = new ArrayList<>();
        for (JsonNode n : iter(ts.path("assignments"))) {
            if (!n.has("completed_at") || n.path("completed_at").isNull()) continue;
            assign.add(new AssignmentPoint(
                    parseDate(n.path("completed_at").asText()),
                    n.path("num_assigned").asInt()));
        }

        List<TranscriptPoint> transcript = new ArrayList<>();
        for (JsonNode n : iter(root.path("transcript"))) {
            transcript.add(new TranscriptPoint(
                    n.path("turn").asInt(),
                    n.path("prompt_tokens").asInt(0),
                    n.path("completion_tokens").asInt(0),
                    n.path("cost_usd").asDouble(0)));
        }

        return new ResultData(path, model, seed, turns, reason, cost,
                funds, tasks, prestige, trust, ledger, assign, transcript);
    }

    // ───── 辅助方法 ─────

    private static Date parseDate(String iso) {
        try {
            return Date.from(OffsetDateTime.parse(iso).toInstant());
        } catch (Exception e) {
            try {
                return Date.from(java.time.Instant.parse(iso));
            } catch (Exception e2) {
                return new Date(0);
            }
        }
    }
    private static String text(JsonNode n, String f) { return n.has(f) ? n.path(f).asText("") : ""; }
    private static int intVal(JsonNode n, String f) { return n.has(f) ? n.path(f).asInt(0) : 0; }
    private static double doubleVal(JsonNode n, String f) { return n.has(f) ? n.path(f).asDouble(0) : 0; }
    private static Iterable<JsonNode> iter(JsonNode arr) { return arr.isArray() ? arr : List.of(); }
}
