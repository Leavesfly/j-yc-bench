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
            }
        });
    }

    private static String formatMoney(long cents) {
        boolean negative = cents < 0;
        long absCents = Math.abs(cents);
        return (negative ? "-$" : "+$") + String.format("%,d", absCents / 100);
    }
}
