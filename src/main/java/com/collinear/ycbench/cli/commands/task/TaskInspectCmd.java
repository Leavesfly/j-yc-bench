package com.collinear.ycbench.cli.commands.task;

import com.collinear.ycbench.cli.CliContext;
import com.collinear.ycbench.cli.JsonOutput;
import com.collinear.ycbench.db.dao.ClientDao;
import com.collinear.ycbench.db.dao.EmployeeDao;
import com.collinear.ycbench.db.dao.TaskDao;
import com.collinear.ycbench.db.model.Client;
import com.collinear.ycbench.db.model.Employee;
import com.collinear.ycbench.db.model.Task;
import com.collinear.ycbench.db.model.TaskAssignment;
import com.collinear.ycbench.db.model.TaskRequirement;
import picocli.CommandLine;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/** {@code yc-bench task inspect --task-id ...} */
@CommandLine.Command(name = "inspect", description = "检查详细的任务信息。")
public final class TaskInspectCmd implements Callable<Integer> {

    @CommandLine.Option(names = "--task-id", required = true)
    String taskId;

    @Override
    public Integer call() {
        try (Connection db = CliContext.openDb()) {
            Task t = TaskResolver.resolveTask(db, taskId);
            if (t == null) return JsonOutput.err("Task '" + taskId + "' not found.");

            List<TaskRequirement> reqs = TaskDao.listRequirements(db, t.id);
            List<Map<String, Object>> reqRows = new ArrayList<>(reqs.size());
            double totalRequired = 0, totalCompleted = 0;
            for (TaskRequirement r : reqs) {
                Map<String, Object> rr = new LinkedHashMap<>();
                rr.put("domain", r.domain.value);
                rr.put("required_qty", r.requiredQty);
                rr.put("completed_qty", r.completedQty);
                rr.put("remaining_qty", r.requiredQty - r.completedQty);
                reqRows.add(rr);
                totalRequired += r.requiredQty;
                totalCompleted += r.completedQty;
            }
            double pct = totalRequired > 0 ? totalCompleted / totalRequired * 100.0 : 0.0;
            pct = Math.round(pct * 100.0) / 100.0;

            List<Map<String, Object>> assignRows = new ArrayList<>();
            for (TaskAssignment a : TaskDao.listAssignmentsForTask(db, t.id)) {
                Employee e = EmployeeDao.findById(db, a.employeeId);
                Map<String, Object> ar = new LinkedHashMap<>();
                ar.put("employee", e != null ? e.name : "unknown");
                ar.put("assigned_at", a.assignedAt.toString());
                assignRows.add(ar);
            }

            String clientName = null;
            if (t.clientId != null) {
                Client c = ClientDao.findById(db, t.clientId);
                if (c != null) clientName = c.name;
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("task_id", t.title);
            out.put("title", t.title);
            out.put("status", t.status.value);
            out.put("client_name", clientName);
            out.put("required_prestige", t.requiredPrestige);
            out.put("required_trust", t.requiredTrust);
            out.put("reward_funds_cents", t.rewardFundsCents);
            out.put("reward_prestige_delta", t.rewardPrestigeDelta);
            out.put("skill_boost_pct", t.skillBoostPct);
            out.put("accepted_at", t.acceptedAt == null ? null : t.acceptedAt.toString());
            out.put("deadline", t.deadline == null ? null : t.deadline.toString());
            out.put("completed_at", t.completedAt == null ? null : t.completedAt.toString());
            out.put("success", t.success);
            out.put("progress_pct", pct);
            out.put("requirements", reqRows);
            out.put("assignments", assignRows);
            return JsonOutput.ok(out);
        } catch (CliContext.CliException e) {
            return JsonOutput.err(e.getMessage());
        } catch (Exception ex) {
            return JsonOutput.err("task inspect failed: " + ex.getMessage());
        }
    }
}
