package com.collinear.ycbench.cli.commands.task;

import com.collinear.ycbench.cli.CliContext;
import com.collinear.ycbench.cli.JsonOutput;
import com.collinear.ycbench.db.dao.ClientDao;
import com.collinear.ycbench.db.dao.TaskDao;
import com.collinear.ycbench.db.model.Client;
import com.collinear.ycbench.db.model.SimState;
import com.collinear.ycbench.db.model.Task;
import com.collinear.ycbench.db.model.TaskRequirement;
import com.collinear.ycbench.db.model.TaskStatus;
import picocli.CommandLine;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/** {@code yc-bench task list [--status STATUS]} */
@CommandLine.Command(name = "list", description = "列出公司拥有的任务。")
public final class TaskListCmd implements Callable<Integer> {

    @CommandLine.Option(names = "--status", description = "按任务状态过滤。")
    String status;

    @Override
    public Integer call() {
        try (Connection db = CliContext.openDb()) {
            SimState st = CliContext.requireSimState(db);
            TaskStatus filter = null;
            if (status != null) {
                try {
                    filter = TaskStatus.fromValue(status);
                } catch (IllegalArgumentException ex) {
                    List<String> valid = new ArrayList<>();
                    for (TaskStatus s : TaskStatus.values()) valid.add(s.value);
                    return JsonOutput.err("Invalid status: " + status + ". Valid: " + valid);
                }
            }

            List<Task> tasks = filter != null
                    ? TaskDao.listByCompanyAndStatus(db, st.companyId, filter)
                    : TaskDao.listByCompany(db, st.companyId);
            tasks.sort(Comparator.comparing((Task t) -> t.acceptedAt,
                    Comparator.nullsLast(Comparator.reverseOrder())));

            List<Map<String, Object>> rows = new ArrayList<>();
            for (Task t : tasks) {
                List<TaskRequirement> reqs = TaskDao.listRequirements(db, t.id);
                double totalRequired = 0, totalCompleted = 0;
                for (TaskRequirement r : reqs) {
                    totalRequired += r.requiredQty;
                    totalCompleted += r.completedQty;
                }
                double pct = totalRequired > 0 ? totalCompleted / totalRequired * 100.0 : 0.0;
                pct = Math.round(pct * 100.0) / 100.0;

                boolean atRisk = t.deadline != null && t.status == TaskStatus.ACTIVE
                        && st.simTime.isAfter(t.deadline);

                String clientName = null;
                if (t.clientId != null) {
                    Client c = ClientDao.findById(db, t.clientId);
                    if (c != null) clientName = c.name;
                }

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("task_id", t.title);
                row.put("title", t.title);
                row.put("status", t.status.value);
                row.put("client_name", clientName);
                row.put("progress_pct", pct);
                row.put("deadline", t.deadline == null ? null : t.deadline.toString());
                row.put("at_risk", atRisk);
                rows.add(row);
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("count", rows.size());
            out.put("tasks", rows);
            return JsonOutput.ok(out);
        } catch (CliContext.CliException e) {
            return JsonOutput.err(e.getMessage());
        } catch (Exception ex) {
            return JsonOutput.err("task list failed: " + ex.getMessage());
        }
    }

    /** 避免旧 javac 中 Arrays 的 unused-import 警告。*/
    @SuppressWarnings("unused")
    private static final List<?> _unused = Arrays.asList();
}
