package com.collinear.ycbench.cli.commands.task;

import com.collinear.ycbench.cli.CliContext;
import com.collinear.ycbench.cli.JsonOutput;
import com.collinear.ycbench.core.Eta;
import com.collinear.ycbench.db.dao.EmployeeDao;
import com.collinear.ycbench.db.dao.TaskDao;
import com.collinear.ycbench.db.model.Employee;
import com.collinear.ycbench.db.model.SimState;
import com.collinear.ycbench.db.model.Task;
import com.collinear.ycbench.db.model.TaskAssignment;
import com.collinear.ycbench.db.model.TaskStatus;
import picocli.CommandLine;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;

/** {@code yc-bench task dispatch --task-id ...} */
@CommandLine.Command(name = "dispatch", description = "将计划中的任务分派为活跃状态。")
public final class TaskDispatchCmd implements Callable<Integer> {

    @CommandLine.Option(names = "--task-id", required = true)
    String taskId;

    @Override
    public Integer call() {
        try (Connection db = CliContext.openDb()) {
            SimState st = CliContext.requireSimState(db);
            Task task = TaskResolver.resolveTask(db, taskId);
            if (task == null) return JsonOutput.err("Task '" + taskId + "' not found.");
            if (task.status != TaskStatus.PLANNED) {
                return JsonOutput.err("Task " + taskId + " must be planned to dispatch (current: "
                        + task.status.value + ").");
            }
            if (!st.companyId.equals(task.companyId)) {
                return JsonOutput.err("Task " + taskId + " does not belong to your company.");
            }
            int existing = TaskDao.countAssignmentsForTask(db, task.id);
            if (existing == 0) {
                return JsonOutput.err("没有分配员工。请先使用 `task assign --task-id ... --employees Emp_1`。");
            }

            task.status = TaskStatus.ACTIVE;
            TaskDao.update(db, task);

            Set<UUID> impacted = new HashSet<>();
            impacted.add(task.id);
            for (TaskAssignment a : TaskDao.listAssignmentsForTask(db, task.id)) {
                for (TaskAssignment peer : TaskDao.listAssignmentsForEmployee(db, a.employeeId)) {
                    if (peer.taskId.equals(task.id)) continue;
                    Task pt = TaskDao.findById(db, peer.taskId);
                    if (pt != null && pt.status == TaskStatus.ACTIVE) impacted.add(pt.id);
                }
            }
            Eta.recalculateEtas(db, st.companyId, st.simTime, impacted);

            List<String> assignedNames = new ArrayList<>();
            for (TaskAssignment a : TaskDao.listAssignmentsForTask(db, task.id)) {
                Employee e = EmployeeDao.findById(db, a.employeeId);
                if (e != null) assignedNames.add(e.name);
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("task_id", task.title);
            out.put("status", task.status.value);
            out.put("assignment_count", assignedNames.size());
            out.put("assigned_employees", assignedNames);
            return JsonOutput.ok(out);
        } catch (CliContext.CliException e) {
            return JsonOutput.err(e.getMessage());
        } catch (Exception ex) {
            return JsonOutput.err("task dispatch failed: " + ex.getMessage());
        }
    }
}
