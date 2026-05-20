package com.collinear.ycbench.cli.commands.task;

import com.collinear.ycbench.cli.CliContext;
import com.collinear.ycbench.cli.JsonOutput;
import com.collinear.ycbench.config.ConfigLoader;
import com.collinear.ycbench.config.WorldConfig;
import com.collinear.ycbench.core.Eta;
import com.collinear.ycbench.db.dao.ClientDao;
import com.collinear.ycbench.db.dao.CompanyDao;
import com.collinear.ycbench.db.dao.EventDao;
import com.collinear.ycbench.db.dao.TaskDao;
import com.collinear.ycbench.db.model.ClientTrust;
import com.collinear.ycbench.db.model.Company;
import com.collinear.ycbench.db.model.CompanyPrestige;
import com.collinear.ycbench.db.model.SimEvent;
import com.collinear.ycbench.db.model.SimState;
import com.collinear.ycbench.db.model.Task;
import com.collinear.ycbench.db.model.TaskAssignment;
import com.collinear.ycbench.db.model.TaskRequirement;
import com.collinear.ycbench.db.model.TaskStatus;
import picocli.CommandLine;

import java.sql.Connection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;

/** {@code yc-bench task cancel --task-id ... --reason ...} */
@CommandLine.Command(name = "cancel", description = "取消任务并应用声望和信任惩罚。")
public final class TaskCancelCmd implements Callable<Integer> {

    @CommandLine.Option(names = "--task-id", required = true)
    String taskId;

    @CommandLine.Option(names = "--reason", required = true)
    String reason;

    @Override
    public Integer call() {
        try (Connection db = CliContext.openDb()) {
            SimState st = CliContext.requireSimState(db);
            WorldConfig cfg = ConfigLoader.getWorldConfig();
            Task task = TaskResolver.resolveTask(db, taskId);
            if (task == null) return JsonOutput.err("Task '" + taskId + "' not found.");
            if (task.status != TaskStatus.PLANNED && task.status != TaskStatus.ACTIVE) {
                return JsonOutput.err("Task " + taskId + " cannot be cancelled (current: " + task.status.value + ").");
            }
            if (!st.companyId.equals(task.companyId)) {
                return JsonOutput.err("Task " + taskId + " does not belong to your company.");
            }

            double cancelPenalty = cfg.penaltyCancelMultiplier * task.rewardPrestigeDelta;
            List<TaskRequirement> reqs = TaskDao.listRequirements(db, task.id);
            Map<String, Object> penaltiesApplied = new LinkedHashMap<>();

            double trustDelta = 0.0;
            if (task.clientId != null) {
                ClientTrust ct = ClientDao.findTrust(db, st.companyId, task.clientId);
                if (ct != null) {
                    double oldLvl = ct.trustLevel;
                    double newLvl = Math.max(cfg.trustMin, oldLvl - cfg.trustCancelPenalty);
                    trustDelta = newLvl - oldLvl;
                    ClientDao.updateTrust(db, st.companyId, task.clientId,
                            Math.round(newLvl * 1000.0) / 1000.0);
                }
            }

            for (TaskRequirement req : reqs) {
                CompanyPrestige cp = CompanyDao.findPrestige(db, st.companyId, req.domain);
                if (cp == null) continue;
                double oldVal = cp.prestigeLevel;
                double newVal = Math.max(cfg.prestigeMin, cp.prestigeLevel - cancelPenalty);
                CompanyDao.updatePrestige(db, st.companyId, req.domain, newVal);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("old", oldVal);
                entry.put("new", newVal);
                entry.put("delta", oldVal - newVal);
                penaltiesApplied.put(req.domain.value, entry);
            }

            task.status = TaskStatus.CANCELLED;
            TaskDao.update(db, task);

            // 删除引用此任务的待处理事件。
            for (SimEvent ev : EventDao.findUnconsumedByTaskInPayload(db, st.companyId, task.id)) {
                EventDao.markConsumed(db, ev.id);
            }

            // 重新计算共享释放员工的活跃任务的 ETA。
            Set<UUID> freed = new HashSet<>();
            for (TaskAssignment a : TaskDao.listAssignmentsForTask(db, task.id)) {
                freed.add(a.employeeId);
            }
            Set<UUID> impacted = new HashSet<>();
            for (UUID empId : freed) {
                for (TaskAssignment ea : TaskDao.listAssignmentsForEmployee(db, empId)) {
                    if (!ea.taskId.equals(task.id)) {
                        Task t = TaskDao.findById(db, ea.taskId);
                        if (t != null && t.status == TaskStatus.ACTIVE) impacted.add(t.id);
                    }
                }
            }
            if (!impacted.isEmpty()) {
                Eta.recalculateEtas(db, st.companyId, st.simTime, impacted);
            }

            Company co = CompanyDao.findById(db, st.companyId);
            boolean bankrupt = co.fundsCents < 0;

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("task_id", task.title);
            out.put("status", task.status.value);
            out.put("reason", reason);
            out.put("cancel_penalty_per_domain", cancelPenalty);
            out.put("prestige_changes", penaltiesApplied);
            out.put("trust_delta", trustDelta);
            out.put("bankrupt", bankrupt);
            return JsonOutput.ok(out);
        } catch (CliContext.CliException e) {
            return JsonOutput.err(e.getMessage());
        } catch (Exception ex) {
            return JsonOutput.err("task cancel failed: " + ex.getMessage());
        }
    }
}
