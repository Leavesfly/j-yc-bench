package com.collinear.ycbench.cli.commands.market;

import com.collinear.ycbench.cli.CliContext;
import com.collinear.ycbench.cli.JsonOutput;
import com.collinear.ycbench.config.ConfigLoader;
import com.collinear.ycbench.config.WorldConfig;
import com.collinear.ycbench.db.dao.ClientDao;
import com.collinear.ycbench.db.dao.CompanyDao;
import com.collinear.ycbench.db.dao.TaskDao;
import com.collinear.ycbench.db.model.Client;
import com.collinear.ycbench.db.model.ClientTrust;
import com.collinear.ycbench.db.model.CompanyPrestige;
import com.collinear.ycbench.db.model.Domain;
import com.collinear.ycbench.db.model.SimState;
import com.collinear.ycbench.db.model.Task;
import com.collinear.ycbench.db.model.TaskRequirement;
import picocli.CommandLine;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

/** {@code yc-bench market browse} */
@CommandLine.Command(name = "browse", description = "浏览可用的市场任务。")
public final class MarketBrowseCmd implements Callable<Integer> {

    @CommandLine.Option(names = "--domain", description = "按需求域过滤。")
    String domain;

    @CommandLine.Option(names = "--reward-min-cents", description = "最小奖励（美分）。")
    Long rewardMinCents;

    @CommandLine.Option(names = "--limit", description = "最大结果数（默认来自实验配置）。")
    Integer limit;

    @CommandLine.Option(names = "--offset", defaultValue = "0", description = "分页偏移量。")
    int offset;

    @Override
    public Integer call() {
        try (Connection db = CliContext.openDb()) {
            SimState st = CliContext.requireSimState(db);
            WorldConfig wc = ConfigLoader.getWorldConfig();
            int effLimit = limit != null ? limit : wc.marketBrowseDefaultLimit;

            Domain filterDomain = null;
            if (domain != null) {
                try {
                    filterDomain = Domain.fromValue(domain);
                } catch (IllegalArgumentException ex) {
                    return JsonOutput.err("Invalid domain: " + domain);
                }
            }

            Map<Domain, Integer> prestigeMap = new EnumMap<>(Domain.class);
            for (CompanyPrestige p : CompanyDao.listPrestige(db, st.companyId)) {
                prestigeMap.put(p.domain, (int) p.prestigeLevel);
            }
            int maxPrestige = prestigeMap.values().stream().mapToInt(Integer::intValue).max().orElse(1);

            Map<UUID, Double> trustMap = new HashMap<>();
            for (ClientTrust ct : ClientDao.listTrustByCompany(db, st.companyId)) {
                trustMap.put(ct.clientId, ct.trustLevel);
            }

            List<Task> rawTasks = TaskDao.listMarketTasks(db);

            List<Map<String, Object>> results = new ArrayList<>();
            int skipped = 0;
            for (Task task : rawTasks) {
                if (task.requiredPrestige > maxPrestige) continue;
                if (rewardMinCents != null && task.rewardFundsCents < rewardMinCents) continue;

                List<TaskRequirement> reqs = TaskDao.listRequirements(db, task.id);

                if (filterDomain != null) {
                    boolean has = false;
                    for (TaskRequirement r : reqs) {
                        if (r.domain == filterDomain) { has = true; break; }
                    }
                    if (!has) continue;
                }

                // 每个域的声望检查：所有任务域必须满足阈值
                boolean meets = true;
                for (TaskRequirement r : reqs) {
                    if (prestigeMap.getOrDefault(r.domain, 1) < task.requiredPrestige) {
                        meets = false; break;
                    }
                }
                if (!meets) continue;

                // 信任门控
                if (task.requiredTrust > 0 && task.clientId != null) {
                    double t = trustMap.getOrDefault(task.clientId, 0.0);
                    if (t < task.requiredTrust) continue;
                }

                if (skipped < offset) { skipped++; continue; }

                List<Map<String, Object>> reqRows = new ArrayList<>();
                for (TaskRequirement r : reqs) {
                    Map<String, Object> rr = new LinkedHashMap<>();
                    rr.put("domain", r.domain.value);
                    rr.put("required_qty", r.requiredQty);
                    reqRows.add(rr);
                }
                String clientName = null;
                if (task.clientId != null) {
                    Client c = ClientDao.findById(db, task.clientId);
                    if (c != null) clientName = c.name;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("task_id", task.title);
                row.put("client_name", clientName);
                row.put("required_prestige", task.requiredPrestige);
                row.put("required_trust", task.requiredTrust);
                row.put("reward_funds_cents", task.rewardFundsCents);
                row.put("reward_prestige_delta", task.rewardPrestigeDelta);
                row.put("skill_boost_pct", task.skillBoostPct);
                row.put("requirements", reqRows);
                results.add(row);
                if (results.size() >= effLimit) break;
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("total", results.size());
            out.put("offset", offset);
            out.put("limit", effLimit);
            out.put("tasks", results);
            return JsonOutput.ok(out);
        } catch (CliContext.CliException e) {
            return JsonOutput.err(e.getMessage());
        } catch (Exception ex) {
            return JsonOutput.err("market browse failed: " + ex.getMessage());
        }
    }
}
