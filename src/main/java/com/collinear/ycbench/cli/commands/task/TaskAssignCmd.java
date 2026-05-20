package com.collinear.ycbench.cli.commands.task;

import com.collinear.ycbench.cli.CliContext;
import com.collinear.ycbench.cli.JsonOutput;
import com.collinear.ycbench.core.Eta;
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

/** {@code yc-bench task assign --task-id ... --employees ...} */
@CommandLine.Command(name = "assign", description = "分配员工到任务。")
public final class TaskAssignCmd implements Callable<Integer> {

    @CommandLine.Option(names = "--task-id", required = true)
    String taskId;

    @CommandLine.Option(names = "--employees", required = true,
            description = "逗号分隔的员工名称（例如 Emp_1,Emp_4）。")
    String employees;

    @Override
    public Integer call() {
        try (Connection db = CliContext.openDb()) {
            SimState st = CliContext.requireSimState(db);
            Task task = TaskResolver.resolveTask(db, taskId);
            if (task == null) return JsonOutput.err("Task '" + taskId + "' not found.");
            if (task.status != TaskStatus.PLANNED && task.status != TaskStatus.ACTIVE) {
                return JsonOutput.err("Task " + taskId + " must be planned or active to assign (current: "
                        + task.status.value + ").");
            }
            if (!st.companyId.equals(task.companyId)) {
                return JsonOutput.err("Task " + taskId + " does not belong to your company.");
            }

            String[] parts = employees.split(",");
            List<String> identifiers = new ArrayList<>();
            for (String p : parts) {
                String t = p.trim();
                if (!t.isEmpty()) identifiers.add(t);
            }

            List<String> newlyAssigned = new ArrayList<>();
            for (String ident : identifiers) {
                Employee e = TaskResolver.resolveEmployee(db, st.companyId, ident);
                if (e == null) return JsonOutput.err("Employee '" + ident + "' not found.");
                if (TaskDao.findAssignment(db, task.id, e.id) != null) continue;
                TaskAssignment a = new TaskAssignment();
                a.taskId = task.id;
                a.employeeId = e.id;
                a.assignedAt = st.simTime;
                TaskDao.insertAssignment(db, a);
                newlyAssigned.add(e.name);
            }

            if (task.status == TaskStatus.ACTIVE) {
                Set<UUID> impacted = new HashSet<>();
                for (String ident : identifiers) {
                    Employee e = TaskResolver.resolveEmployee(db, st.companyId, ident);
                    if (e == null) continue;
                    for (TaskAssignment a : TaskDao.listAssignmentsForEmployee(db, e.id)) {
                        Task t = TaskDao.findById(db, a.taskId);
                        if (t != null && t.status == TaskStatus.ACTIVE) impacted.add(t.id);
                    }
                }
                if (!impacted.isEmpty()) {
                    Eta.recalculateEtas(db, st.companyId, st.simTime, impacted);
                }
            }

            List<String> currentAssignees = new ArrayList<>();
            for (TaskAssignment a : TaskDao.listAssignmentsForTask(db, task.id)) {
                Employee e = com.collinear.ycbench.db.dao.EmployeeDao.findById(db, a.employeeId);
                currentAssignees.add(e != null ? e.name : "unknown");
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("task_id", task.title);
            out.put("status", task.status.value);
            out.put("newly_assigned", newlyAssigned);
            out.put("total_assigned", currentAssignees);
            return JsonOutput.ok(out);
        } catch (CliContext.CliException e) {
            return JsonOutput.err(e.getMessage());
        } catch (Exception ex) {
            return JsonOutput.err("task assign failed: " + ex.getMessage());
        }
    }
}
