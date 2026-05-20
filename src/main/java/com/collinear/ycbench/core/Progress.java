package com.collinear.ycbench.core;

import com.collinear.ycbench.db.dao.EmployeeDao;
import com.collinear.ycbench.db.dao.TaskDao;
import com.collinear.ycbench.db.model.Domain;
import com.collinear.ycbench.db.model.EmployeeSkillRate;
import com.collinear.ycbench.db.model.Task;
import com.collinear.ycbench.db.model.TaskAssignment;
import com.collinear.ycbench.db.model.TaskRequirement;
import com.collinear.ycbench.db.model.TaskStatus;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 有效费率计算和进度刷新。镜像 {@code core/progress.py}。
 *
 * <p>布鲁克斯定律：只有任务+域中前 {@link #EFFICIENT_TEAM_SIZE} 名贡献者
 * 获得费率积分——超出此范围的都是纯开销。并发分配将单个员工的费率平均分配到其任务中
 * （无并行奖励）。
 */
public final class Progress {

    /** 每个任务+域的前 N 名员工贡献全额费率。 */
    public static final int EFFICIENT_TEAM_SIZE = 4;

    /** 超出 {@link #EFFICIENT_TEAM_SIZE} 的每位员工的贡献（0 = 纯开销）。 */
    public static final double OVERCROWD_PENALTY = 0.0;

    private Progress() {
    }

    /** 一个（任务，域）的有效费率——单位/小时。 */
    public static final class EffectiveRate {
        public final UUID taskId;
        public final Domain domain;
        public final double ratePerHour;

        public EffectiveRate(UUID taskId, Domain domain, double ratePerHour) {
            this.taskId = taskId;
            this.domain = domain;
            this.ratePerHour = ratePerHour;
        }
    }

    /** 计算每个活动任务 × 所需域的有效费率。 */
    public static List<EffectiveRate> computeEffectiveRates(Connection db, UUID companyId) throws SQLException {
        List<Task> active = TaskDao.listByCompanyAndStatus(db, companyId, TaskStatus.ACTIVE);
        if (active.isEmpty()) return List.of();

        List<UUID> taskIds = new ArrayList<>(active.size());
        for (Task t : active) taskIds.add(t.id);

        List<TaskRequirement> reqs = TaskDao.listRequirementsForTasks(db, taskIds);

        // 每个任务的分配
        Map<UUID, List<TaskAssignment>> assignmentsByTask = new HashMap<>();
        Map<UUID, Integer> assignmentCounts = new HashMap<>();
        for (UUID tid : taskIds) {
            List<TaskAssignment> as = TaskDao.listAssignmentsForTask(db, tid);
            assignmentsByTask.put(tid, as);
            for (TaskAssignment a : as) {
                assignmentCounts.merge(a.employeeId, 1, Integer::sum);
            }
        }
        if (assignmentCounts.isEmpty()) {
            List<EffectiveRate> out = new ArrayList<>(reqs.size());
            for (TaskRequirement r : reqs) out.add(new EffectiveRate(r.taskId, r.domain, 0.0));
            return out;
        }

        // 基础费率：(employee, domain) -> rate
        List<UUID> empIds = new ArrayList<>(assignmentCounts.keySet());
        List<EmployeeSkillRate> skillRows = EmployeeDao.listSkillsForEmployees(db, empIds);
        Map<EmpDom, Double> baseRates = new HashMap<>();
        for (EmployeeSkillRate s : skillRows) {
            baseRates.put(new EmpDom(s.employeeId, s.domain), s.rateDomainPerHour);
        }

        List<EffectiveRate> out = new ArrayList<>(reqs.size());
        for (TaskRequirement r : reqs) {
            double rate = effectiveTaskDomainRate(
                    r.taskId, r.domain,
                    assignmentsByTask.getOrDefault(r.taskId, List.of()),
                    assignmentCounts, baseRates);
            out.add(new EffectiveRate(r.taskId, r.domain, rate));
        }
        return out;
    }

    /**
     * 刷新 {@code [t0, t1)} 范围内每个活动任务的进度：每个域推进
     * {@code effectiveRate × businessHours}，上限为 {@code required_qty}。
     */
    public static void flushProgress(Connection db, UUID companyId,
                                     OffsetDateTime t0, OffsetDateTime t1) throws SQLException {
        double hours = BusinessTime.businessHoursBetween(t0, t1);
        if (hours <= 0) return;

        List<EffectiveRate> rates = computeEffectiveRates(db, companyId);
        if (rates.isEmpty()) return;

        // 按（任务，域）索引需求以进行更新
        for (EffectiveRate er : rates) {
            // load the current requirement
            List<TaskRequirement> reqs = TaskDao.listRequirements(db, er.taskId);
            for (TaskRequirement r : reqs) {
                if (r.domain != er.domain) continue;
                double delta = er.ratePerHour * hours;
                double after = Math.min(r.requiredQty, r.completedQty + delta);
                if (after < 0) after = 0;
                if (after != r.completedQty) {
                    TaskDao.updateRequirementCompleted(db, r.taskId, r.domain, after);
                }
            }
        }
    }

    private static double effectiveTaskDomainRate(UUID taskId, Domain domain,
                                                  List<TaskAssignment> assignments,
                                                  Map<UUID, Integer> assignmentCounts,
                                                  Map<EmpDom, Double> baseRates) {
        List<Double> contributions = new ArrayList<>();
        for (TaskAssignment a : assignments) {
            if (!a.taskId.equals(taskId)) continue;
            int k = assignmentCounts.getOrDefault(a.employeeId, 0);
            if (k <= 0) continue;
            double base = baseRates.getOrDefault(new EmpDom(a.employeeId, domain), 0.0);
            contributions.add(base / k);
        }
        contributions.sort(Collections.reverseOrder());
        double total = 0.0;
        for (int i = 0; i < contributions.size(); i++) {
            total += i < EFFICIENT_TEAM_SIZE ? contributions.get(i) : contributions.get(i) * OVERCROWD_PENALTY;
        }
        return total;
    }

    private static final class EmpDom {
        final UUID employeeId;
        final Domain domain;

        EmpDom(UUID employeeId, Domain domain) {
            this.employeeId = employeeId;
            this.domain = domain;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof EmpDom)) return false;
            EmpDom e = (EmpDom) o;
            return employeeId.equals(e.employeeId) && domain == e.domain;
        }

        @Override
        public int hashCode() {
            return employeeId.hashCode() * 31 + domain.hashCode();
        }
    }
}
