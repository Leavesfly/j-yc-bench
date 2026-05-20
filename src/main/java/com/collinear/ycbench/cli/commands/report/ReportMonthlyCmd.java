package com.collinear.ycbench.cli.commands.report;

import com.collinear.ycbench.cli.CliContext;
import com.collinear.ycbench.cli.JsonOutput;
import com.collinear.ycbench.db.dao.MonthlyMetricDao;
import com.collinear.ycbench.db.model.MonthlyMetric;
import com.collinear.ycbench.db.model.SimState;
import picocli.CommandLine;

import java.sql.Connection;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "monthly", description = "月度指标（收入、成本、回报、期末资金）。")
public final class ReportMonthlyCmd implements Callable<Integer> {

    @CommandLine.Option(names = "--from-month", description = "开始月份 YYYY-MM。")
    String fromMonth;

    @CommandLine.Option(names = "--to-month", description = "结束月份 YYYY-MM。")
    String toMonth;

    @Override
    public Integer call() {
        try (Connection db = CliContext.openDb()) {
            SimState st = CliContext.requireSimState(db);
            LocalDate from = fromMonth != null ? CliContext.parseMonth(fromMonth) : null;
            LocalDate to = toMonth != null ? CliContext.parseMonth(toMonth) : null;
            List<MonthlyMetric> metrics = MonthlyMetricDao.list(db, st.companyId, from, to);

            List<Map<String, Object>> rows = new ArrayList<>(metrics.size());
            for (MonthlyMetric m : metrics) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("month_start", m.monthStart.toString());
                r.put("revenue_cents", m.revenueCents);
                r.put("cost_cents", m.costCents);
                r.put("return_cents", m.returnCents);
                r.put("ending_funds_cents", m.endingFundsCents);
                rows.add(r);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("count", rows.size());
            out.put("months", rows);
            return JsonOutput.ok(out);
        } catch (CliContext.CliException e) {
            return JsonOutput.err(e.getMessage());
        } catch (Exception ex) {
            return JsonOutput.err("report monthly failed: " + ex.getMessage());
        }
    }
}
