package com.collinear.ycbench.web;

import com.collinear.ycbench.db.dao.LedgerDao;
import com.collinear.ycbench.db.dao.MonthlyMetricDao;
import com.collinear.ycbench.db.dao.SimStateDao;
import com.collinear.ycbench.db.model.LedgerEntry;
import com.collinear.ycbench.db.model.MonthlyMetric;
import com.collinear.ycbench.db.model.SimState;
import io.javalin.Javalin;

import java.sql.Connection;
import java.util.*;

public final class FinanceApi {

    private FinanceApi() {}

    public static void register(Javalin app) {
        // 获取财务流水
        app.get("/api/finance/ledger", ctx -> {
            try (Connection db = WebServer.openDb()) {
                SimState state = SimStateDao.findFirst(db);
                if (state == null) {
                    ctx.status(404).json(Map.of("error", "No simulation"));
                    return;
                }
                List<LedgerEntry> entries = LedgerDao.listByCompany(db, state.companyId, null, null, null);
                List<Map<String, Object>> result = new ArrayList<>();
                for (LedgerEntry e : entries) {
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", e.id.toString());
                    item.put("occurredAt", e.occurredAt.toString());
                    item.put("category", e.category.value);
                    item.put("amountCents", e.amountCents);
                    item.put("amountFormatted", formatMoney(e.amountCents));
                    item.put("refType", e.refType);
                    result.add(item);
                }
                ctx.json(result);
            }
        });

        // 获取月度指标（用于图表）
        app.get("/api/finance/metrics", ctx -> {
            try (Connection db = WebServer.openDb()) {
                SimState state = SimStateDao.findFirst(db);
                if (state == null) {
                    ctx.status(404).json(Map.of("error", "No simulation"));
                    return;
                }
                List<MonthlyMetric> metrics = MonthlyMetricDao.listByCompany(db, state.companyId);
                if (!metrics.isEmpty()) {
                    List<Map<String, Object>> result = new ArrayList<>();
                    for (MonthlyMetric m : metrics) {
                        Map<String, Object> item = new HashMap<>();
                        item.put("month", m.monthStart.toString());
                        item.put("revenueCents", m.revenueCents);
                        item.put("costCents", m.costCents);
                        item.put("returnCents", m.returnCents);
                        item.put("endingFundsCents", m.endingFundsCents);
                        result.add(item);
                    }
                    ctx.json(result);
                } else {
                    // Fallback: generate from ledger entries (for Bot runs)
                    ctx.json(buildMetricsFromLedger(db, state.companyId));
                }
            }
        });
    }

    /**
     * Fallback: build monthly metrics from ledger entries when monthly_metrics table is empty.
     * Groups ledger by month, computes cumulative ending balance.
     */
    private static List<Map<String, Object>> buildMetricsFromLedger(java.sql.Connection db, java.util.UUID companyId) throws Exception {
        List<LedgerEntry> entries = LedgerDao.listByCompany(db, companyId, null, null, null);
        if (entries.isEmpty()) return List.of();

        // Get initial funds from company
        com.collinear.ycbench.db.model.Company co = com.collinear.ycbench.db.dao.CompanyDao.findById(db, companyId);
        long startingFunds = co != null ? co.fundsCents : 20000000L; // $200k default

        // Group by month: compute revenue/cost per month
        java.util.TreeMap<String, long[]> monthlyData = new java.util.TreeMap<>();
        for (LedgerEntry e : entries) {
            String month = e.occurredAt.toLocalDate().withDayOfMonth(1).toString();
            monthlyData.computeIfAbsent(month, k -> new long[2]); // [revenue, cost]
            if (e.amountCents >= 0) {
                monthlyData.get(month)[0] += e.amountCents;
            } else {
                monthlyData.get(month)[1] += Math.abs(e.amountCents);
            }
        }

        // Build cumulative balance series
        // First compute what the starting balance should be by working backwards from current
        // Sum all ledger entries to get net change, then startingFunds = current - netChange
        long totalNet = entries.stream().mapToLong(e -> e.amountCents).sum();
        long computedStart = (co != null ? co.fundsCents : 20000000L) - totalNet;

        List<Map<String, Object>> result = new ArrayList<>();
        long runningBalance = computedStart;
        for (Map.Entry<String, long[]> entry : monthlyData.entrySet()) {
            long revenue = entry.getValue()[0];
            long cost = entry.getValue()[1];
            runningBalance += (revenue - cost);
            Map<String, Object> item = new HashMap<>();
            item.put("month", entry.getKey());
            item.put("revenueCents", revenue);
            item.put("costCents", cost);
            item.put("returnCents", revenue - cost);
            item.put("endingFundsCents", runningBalance);
            result.add(item);
        }
        return result;
    }

    private static String formatMoney(long cents) {
        boolean negative = cents < 0;
        long absCents = Math.abs(cents);
        return (negative ? "-$" : "+$") + String.format("%,d", absCents / 100);
    }
}
