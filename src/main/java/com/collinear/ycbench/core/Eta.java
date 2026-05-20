package com.collinear.ycbench.core;

import com.collinear.ycbench.config.ConfigLoader;
import com.collinear.ycbench.config.WorldConfig;
import com.collinear.ycbench.db.dao.EventDao;
import com.collinear.ycbench.db.dao.TaskDao;
import com.collinear.ycbench.db.model.Domain;
import com.collinear.ycbench.db.model.EventType;
import com.collinear.ycbench.db.model.SimEvent;
import com.collinear.ycbench.db.model.Task;
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
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * ETA 求解器和投影事件管理。镜像 {@code core/eta.py}。
 *
 * <p>对于每个活动任务，我们插入 {@link EventType#TASK_COMPLETED} 和（最多一个
 * 即将到来的）{@link EventType#TASK_HALF_PROGRESS} 事件。每当分配拓扑
 * 发生变化时——分配/取消分配/分派/取消/完成——调用 {@link #recalculateEtas}
 * 删除过时的投影并重新插入新的投影。
 */
public final class Eta {

    private Eta() {
    }

    /** 求解单个任务的完成时间。如果无法到达则返回 {@code null}。 */
    public static OffsetDateTime solveTaskCompletionTime(Connection db, UUID taskId,
                                                         OffsetDateTime now,
                                                         List<Progress.EffectiveRate> rates) throws SQLException {
        List<TaskRequirement> reqs = TaskDao.listRequirements(db, taskId);
        if (reqs.isEmpty()) return null;

        Map<Domain, Double> rateMap = new HashMap<>();
        for (Progress.EffectiveRate er : rates) {
            if (er.taskId.equals(taskId)) rateMap.put(er.domain, er.ratePerHour);
        }

        double maxHours = 0.0;
        for (TaskRequirement req : reqs) {
            double remaining = req.requiredQty - req.completedQty;
            if (remaining <= 0) continue;
            double rate = rateMap.getOrDefault(req.domain, 0.0);
            if (rate <= 0) return null;
            double h = remaining / rate;
            if (h > maxHours) maxHours = h;
        }
        if (maxHours <= 0) return now;
        return BusinessTime.addBusinessHours(now, maxHours);
    }

    /** 求解加权完成比率首次达到 {@code halfThreshold} 的时间。 */
    public static OffsetDateTime solveTaskHalfwayTime(Connection db, UUID taskId,
                                                      OffsetDateTime now,
                                                      List<Progress.EffectiveRate> rates,
                                                      double halfThreshold) throws SQLException {
        List<TaskRequirement> reqs = TaskDao.listRequirements(db, taskId);
        if (reqs.isEmpty()) return null;

        Map<Domain, Double> rateMap = new HashMap<>();
        for (Progress.EffectiveRate er : rates) {
            if (er.taskId.equals(taskId)) rateMap.put(er.domain, er.ratePerHour);
        }

        double totalRequired = 0.0;
        for (TaskRequirement r : reqs) totalRequired += r.requiredQty;
        if (totalRequired <= 0) return now;
        double target = halfThreshold * totalRequired;

        double currentCompleted = 0.0;
        for (TaskRequirement r : reqs) currentCompleted += Math.min(r.requiredQty, r.completedQty);
        if (currentCompleted >= target) return now;

        // 构建每个域的片段
        List<DomainPiece> pieces = new ArrayList<>(reqs.size());
        for (TaskRequirement req : reqs) {
            double remaining = req.requiredQty - req.completedQty;
            double rate = rateMap.getOrDefault(req.domain, 0.0);
            Double capHours;
            if (remaining > 0 && rate <= 0) capHours = null;
            else if (remaining <= 0) capHours = 0.0;
            else capHours = remaining / rate;
            pieces.add(new DomainPiece(req.completedQty, req.requiredQty, rate, capHours));
        }

        TreeSet<Double> bps = new TreeSet<>();
        for (DomainPiece d : pieces) {
            if (d.capHours != null && d.capHours > 0) bps.add(d.capHours);
        }

        double h = 0.0;
        double completedSum = currentCompleted;
        for (Double bp : bps) {
            double slope = 0.0;
            for (DomainPiece d : pieces) {
                if (d.capHours != null && d.capHours > h) slope += d.rate;
            }
            if (slope <= 0) {
                if (completedSum >= target) return BusinessTime.addBusinessHours(now, h);
                h = bp;
                double recompute = 0.0;
                for (DomainPiece d : pieces) {
                    recompute += Math.min(d.required, d.completed + d.rate * h);
                }
                completedSum = recompute;
                continue;
            }
            double needed = target - completedSum;
            double deltaH = needed / slope;
            if (h + deltaH <= bp) return BusinessTime.addBusinessHours(now, h + deltaH);
            completedSum += slope * (bp - h);
            h = bp;
        }

        // 最后一个断点之后
        double slope = 0.0;
        for (DomainPiece d : pieces) {
            if (d.capHours != null && d.capHours > h) slope += d.rate;
        }
        if (slope > 0) {
            double needed = target - completedSum;
            if (needed <= 0) return BusinessTime.addBusinessHours(now, h);
            double deltaH = needed / slope;
            return BusinessTime.addBusinessHours(now, h + deltaH);
        }
        if (completedSum >= target) return BusinessTime.addBusinessHours(now, h);
        return null;
    }

    /**
     * 重新计算投影事件。传递 {@code impactedTaskIds=null} 以刷新每个
     * 活动任务；传递一个集合以限制范围。
     */
    public static void recalculateEtas(Connection db, UUID companyId, OffsetDateTime now,
                                       Set<UUID> impactedTaskIds, List<Double> milestones) throws SQLException {
        if (milestones == null || milestones.isEmpty()) milestones = List.of(0.5);

        Set<UUID> taskIds;
        if (impactedTaskIds == null) {
            taskIds = new java.util.HashSet<>();
            for (Task t : TaskDao.listByCompanyAndStatus(db, companyId, TaskStatus.ACTIVE)) {
                taskIds.add(t.id);
            }
        } else {
            taskIds = impactedTaskIds;
        }
        if (taskIds.isEmpty()) return;

        // 删除过时的投影事件
        for (UUID tid : taskIds) {
            List<SimEvent> stale = EventDao.findStaleProjections(db, companyId, tid);
            for (SimEvent ev : stale) EventDao.delete(db, ev.id);
        }

        List<Progress.EffectiveRate> rates = Progress.computeEffectiveRates(db, companyId);

        List<Double> sortedMilestones = new ArrayList<>(milestones);
        Collections.sort(sortedMilestones);

        for (UUID tid : taskIds) {
            Task task = TaskDao.findById(db, tid);
            if (task == null || task.status != TaskStatus.ACTIVE) continue;

            OffsetDateTime ct = solveTaskCompletionTime(db, tid, now, rates);
            if (ct != null) {
                EventOps.insertEvent(db, companyId, EventType.TASK_COMPLETED, ct,
                        Map.of("task_id", tid.toString()),
                        "task:" + tid + ":completed");
            }

            int emittedPct = task.progressMilestonePct;
            for (Double m : sortedMilestones) {
                int milestonePct = (int) (m * 100);
                if (milestonePct <= emittedPct) continue;
                OffsetDateTime mt = solveTaskHalfwayTime(db, tid, now, rates, m);
                if (mt != null) {
                    Map<String, Object> payload = new java.util.LinkedHashMap<>();
                    payload.put("task_id", tid.toString());
                    payload.put("milestone_pct", milestonePct);
                    EventOps.insertEvent(db, companyId, EventType.TASK_HALF_PROGRESS, mt,
                            payload, "task:" + tid + ":milestone:" + milestonePct);
                    break; // 仅下一个即将到来的里程碑
                }
            }
        }
    }

    /** 从 {@link ConfigLoader} 加载里程碑的便捷重载。 */
    public static void recalculateEtas(Connection db, UUID companyId, OffsetDateTime now,
                                       Set<UUID> impactedTaskIds) throws SQLException {
        WorldConfig wc = ConfigLoader.getWorldConfig();
        recalculateEtas(db, companyId, now, impactedTaskIds, wc.taskProgressMilestones);
    }

    private static final class DomainPiece {
        final double completed;
        final double required;
        final double rate;
        final Double capHours;     // null = unreachable

        DomainPiece(double completed, double required, double rate, Double capHours) {
            this.completed = completed;
            this.required = required;
            this.rate = rate;
            this.capHours = capHours;
        }
    }
}
