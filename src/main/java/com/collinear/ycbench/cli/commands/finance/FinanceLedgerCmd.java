package com.collinear.ycbench.cli.commands.finance;

import com.collinear.ycbench.cli.CliContext;
import com.collinear.ycbench.cli.JsonOutput;
import com.collinear.ycbench.db.dao.LedgerDao;
import com.collinear.ycbench.db.model.LedgerCategory;
import com.collinear.ycbench.db.model.LedgerEntry;
import com.collinear.ycbench.db.model.SimState;
import picocli.CommandLine;

import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "ledger", description = "查看账本条目，可选日期/类别过滤器。")
public final class FinanceLedgerCmd implements Callable<Integer> {

    @CommandLine.Option(names = "--from", description = "开始日期 MM/DD/YYYY。")
    String fromDate;

    @CommandLine.Option(names = "--to", description = "结束日期 MM/DD/YYYY。")
    String toDate;

    @CommandLine.Option(names = "--category", description = "按账本类别过滤。")
    String category;

    @Override
    public Integer call() {
        try (Connection db = CliContext.openDb()) {
            SimState st = CliContext.requireSimState(db);
            OffsetDateTime from = fromDate != null ? CliContext.parseMmDdYyyyMidnightUtc(fromDate) : null;
            OffsetDateTime to = toDate != null ? CliContext.parseMmDdYyyyMidnightUtc(toDate) : null;
            LedgerCategory cat = null;
            if (category != null) {
                try {
                    cat = LedgerCategory.fromValue(category);
                } catch (IllegalArgumentException ex) {
                    List<String> valid = new ArrayList<>();
                    for (LedgerCategory c : LedgerCategory.values()) valid.add(c.value);
                    return JsonOutput.err("Invalid category: " + category + ". Valid: " + valid);
                }
            }

            List<LedgerEntry> entries = LedgerDao.listByCompany(db, st.companyId, from, to, cat);
            long total = 0;
            List<Map<String, Object>> rows = new ArrayList<>(entries.size());
            for (LedgerEntry e : entries) {
                total += e.amountCents;
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("id", e.id.toString());
                r.put("occurred_at", e.occurredAt.toString());
                r.put("category", e.category.value);
                r.put("amount_cents", e.amountCents);
                r.put("ref_type", e.refType);
                r.put("ref_id", e.refId == null ? null : e.refId.toString());
                rows.add(r);
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("count", rows.size());
            out.put("total_amount_cents", total);
            out.put("entries", rows);
            return JsonOutput.ok(out);
        } catch (CliContext.CliException e) {
            return JsonOutput.err(e.getMessage());
        } catch (Exception ex) {
            return JsonOutput.err("finance ledger failed: " + ex.getMessage());
        }
    }

}
