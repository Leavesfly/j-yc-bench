package com.collinear.ycbench.cli.commands.sim;

import com.collinear.ycbench.cli.CliContext;
import com.collinear.ycbench.cli.JsonOutput;
import com.collinear.ycbench.core.AdvanceResult;
import com.collinear.ycbench.core.Engine;
import com.collinear.ycbench.core.EventOps;
import com.collinear.ycbench.db.dao.CompanyDao;
import com.collinear.ycbench.db.dao.SimStateDao;
import com.collinear.ycbench.db.dao.TaskDao;
import com.collinear.ycbench.db.model.Company;
import com.collinear.ycbench.db.model.SimEvent;
import com.collinear.ycbench.db.model.SimState;
import com.collinear.ycbench.db.model.TaskStatus;
import picocli.CommandLine;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * {@code yc-bench sim resume} — 快进到下一个可操作事件。
 * 镜像 {@code cli/sim_commands.py:sim_resume}。
 */
@CommandLine.Command(name = "resume", description = "推进到下一个可操作事件。")
public final class SimResumeCmd implements Callable<Integer> {

    @Override
    public Integer call() {
        try (Connection db = CliContext.openDb()) {
            SimState st = CliContext.requireSimState(db);

            int activeCount = TaskDao.countByCompanyAndStatus(db, st.companyId, TaskStatus.ACTIVE);
            if (activeCount == 0) {
                return JsonOutput.err("没有活跃任务。在调用 sim resume 之前接受并分发一个任务。"
                        + "使用：market browse → task accept → task dispatch → sim resume");
            }

            int totalEvents = 0;
            int totalPayrolls = 0;
            long totalBalanceDelta = 0;
            List<Map<String, Object>> wakeEvents = new ArrayList<>();
            String lastCheckpointType = null;

            while (true) {
                SimEvent nextEvent = EventOps.fetchNextEvent(db, st.companyId, st.horizonEnd);
                if (nextEvent == null) break;

                lastCheckpointType = nextEvent.eventType.value;
                AdvanceResult r = Engine.advanceTime(db, st.companyId, nextEvent.scheduledAt);

                totalEvents += r.eventsProcessed;
                totalPayrolls += r.payrollsApplied;
                totalBalanceDelta += r.balanceDelta;
                wakeEvents.addAll(r.wakeEvents);

                if (r.bankrupt || r.horizonReached) break;
                if (!r.wakeEvents.isEmpty()) break;

                int active = TaskDao.countByCompanyAndStatus(db, st.companyId, TaskStatus.ACTIVE);
                if (active == 0) break;

                st = SimStateDao.findByCompany(db, st.companyId);
            }

            st = SimStateDao.findByCompany(db, st.companyId);
            Company c = CompanyDao.findById(db, st.companyId);
            boolean bankrupt = c.fundsCents < 0;
            boolean horizonReached = !st.simTime.isBefore(st.horizonEnd);
            String terminal = bankrupt ? "bankruptcy" : (horizonReached ? "horizon_end" : null);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("old_sim_time", st.simTime.toString());
            out.put("new_sim_time", st.simTime.toString());
            out.put("checkpoint_event_type", lastCheckpointType);
            out.put("events_processed", totalEvents);
            out.put("payrolls_applied", totalPayrolls);
            out.put("balance_delta", totalBalanceDelta);
            out.put("wake_events", wakeEvents);
            out.put("bankrupt", bankrupt);
            out.put("horizon_reached", horizonReached);
            out.put("terminal_reason", terminal);
            return JsonOutput.ok(out);
        } catch (CliContext.CliException e) {
            return JsonOutput.err(e.getMessage());
        } catch (Exception ex) {
            return JsonOutput.err("sim resume failed: " + ex.getMessage());
        }
    }
}
