package com.collinear.ycbench.cli.commands.sim;

import com.collinear.ycbench.cli.CliContext;
import com.collinear.ycbench.cli.JsonOutput;
import com.collinear.ycbench.config.ConfigLoader;
import com.collinear.ycbench.config.WorldConfig;
import com.collinear.ycbench.core.EventOps;
import com.collinear.ycbench.db.dao.SimStateDao;
import com.collinear.ycbench.db.model.EventType;
import com.collinear.ycbench.db.model.SimState;
import com.collinear.ycbench.services.SeedWorld;
import picocli.CommandLine;

import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * {@code yc-bench sim init} — 引导一个新的模拟。
 *
 * <p>镜像 {@code cli/sim_commands.py:sim_init}：种子化世界，调度
 * {@code horizon_end} 事件并写入 {@code sim_state} 行。
 */
@CommandLine.Command(name = "init", description = "初始化一个新的模拟。")
public final class SimInitCmd implements Callable<Integer> {

    @CommandLine.Option(names = "--seed", required = true, description = "RNG 种子。")
    long seed;

    @CommandLine.Option(names = "--start-date", required = true, description = "开始日期 MM/DD/YYYY。")
    String startDate;

    @CommandLine.Option(names = "--horizon-years", defaultValue = "1")
    int horizonYears;

    @CommandLine.Option(names = "--company-name", required = true)
    String companyName;

    @CommandLine.Option(names = "--employee-count", description = "覆盖配置中的 num_employees。")
    Integer employeeCount;

    @CommandLine.Option(names = "--market-task-count", description = "覆盖配置中的 num_market_tasks。")
    Integer marketTaskCount;

    @Override
    public Integer call() {
        try (Connection db = CliContext.openDb()) {
            WorldConfig wc = ConfigLoader.getWorldConfig();
            int empCount = employeeCount != null ? employeeCount : wc.numEmployees;
            int taskCount = marketTaskCount != null ? marketTaskCount : wc.numMarketTasks;

            OffsetDateTime startDt = CliContext.parseMmDdYyyy09Utc(startDate);
            OffsetDateTime horizonEnd = CliContext.addYears(startDt, horizonYears);

            if (SimStateDao.findFirst(db) != null) {
                return JsonOutput.err("A simulation already exists. Only one simulation per database is supported.");
            }

            SeedWorld.Request req = new SeedWorld.Request(
                    seed, companyName, horizonYears, empCount, taskCount, wc, startDt);
            SeedWorld.Result result = SeedWorld.seed(db, req);

            // horizon_end 事件
            EventOps.insertEvent(db, result.companyId, EventType.HORIZON_END,
                    horizonEnd, Map.of("reason", "horizon_end"), "horizon_end");

            // sim_state 行
            SimState st = new SimState();
            st.companyId = result.companyId;
            st.simTime = startDt;
            st.runSeed = (int) seed;
            st.horizonEnd = horizonEnd;
            st.replenishCounter = 0;
            SimStateDao.insert(db, st);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("simulation_id", result.companyId.toString());
            out.put("company_id", result.companyId.toString());
            out.put("sim_time", startDt.toString());
            out.put("horizon_end", horizonEnd.toString());
            out.put("company_name", companyName);
            out.put("seed", seed);
            return JsonOutput.ok(out);
        } catch (CliContext.CliException e) {
            return JsonOutput.err(e.getMessage());
        } catch (Exception ex) {
            return JsonOutput.err("sim init failed: " + ex.getMessage());
        }
    }
}
