package com.collinear.ycbench.web;

import com.collinear.ycbench.db.dao.ClientDao;
import com.collinear.ycbench.db.dao.SimStateDao;
import com.collinear.ycbench.db.dao.TaskDao;
import com.collinear.ycbench.db.model.*;
import io.javalin.Javalin;

import java.sql.Connection;
import java.util.*;

public final class TaskApi {

    private TaskApi() {}

    public static void register(Javalin app) {
        // 获取公司所有任务（按状态分组）
        app.get("/api/tasks", ctx -> {
            try (Connection db = WebServer.openDb()) {
                SimState state = SimStateDao.findFirst(db);
                if (state == null) {
                    ctx.status(404).json(Map.of("error", "No simulation"));
                    return;
                }
                List<Task> tasks = TaskDao.listByCompany(db, state.companyId);
                List<Map<String, Object>> result = new ArrayList<>();
                for (Task t : tasks) {
                    result.add(mapTask(db, t));
                }
                ctx.json(result);
            }
        });

        // 获取市场任务
        app.get("/api/tasks/market", ctx -> {
            try (Connection db = WebServer.openDb()) {
                List<Task> tasks = TaskDao.listMarket(db);
                List<Map<String, Object>> result = new ArrayList<>();
                for (Task t : tasks) {
                    result.add(mapTask(db, t));
                }
                ctx.json(result);
            }
        });

        // 获取单个任务详情
        app.get("/api/tasks/{id}", ctx -> {
            try (Connection db = WebServer.openDb()) {
                UUID taskId = UUID.fromString(ctx.pathParam("id"));
                Task t = TaskDao.findById(db, taskId);
                if (t == null) {
                    ctx.status(404).json(Map.of("error", "Task not found"));
                    return;
                }
                Map<String, Object> detail = mapTask(db, t);

                // 添加需求详情
                List<TaskRequirement> reqs = TaskDao.listRequirements(db, taskId);
                List<Map<String, Object>> reqList = new ArrayList<>();
                for (TaskRequirement r : reqs) {
                    Map<String, Object> reqMap = new HashMap<>();
                    reqMap.put("domain", r.domain.value);
                    reqMap.put("requiredQty", r.requiredQty);
                    reqMap.put("completedQty", r.completedQty);
                    reqMap.put("progressPct", r.requiredQty > 0
                            ? Math.round(r.completedQty / r.requiredQty * 100) : 0);
                    reqList.add(reqMap);
                }
                detail.put("requirements", reqList);

                // 添加分配详情
                List<TaskAssignment> assignments = TaskDao.listAssignmentsForTask(db, taskId);
                List<Map<String, Object>> assignList = new ArrayList<>();
                for (TaskAssignment a : assignments) {
                    Map<String, Object> aMap = new HashMap<>();
                    aMap.put("employeeId", a.employeeId.toString());
                    aMap.put("assignedAt", a.assignedAt != null ? a.assignedAt.toString() : null);
                    assignList.add(aMap);
                }
                detail.put("assignments", assignList);

                ctx.json(detail);
            }
        });
    }

    private static Map<String, Object> mapTask(Connection db, Task t) throws Exception {
        Map<String, Object> item = new HashMap<>();
        item.put("id", t.id.toString());
        item.put("title", t.title);
        item.put("status", t.status.value);
        item.put("rewardCents", t.rewardFundsCents);
        item.put("rewardFormatted", "$" + String.format("%,d", t.rewardFundsCents / 100));
        item.put("requiredPrestige", t.requiredPrestige);
        item.put("rewardPrestigeDelta", t.rewardPrestigeDelta);
        item.put("deadline", t.deadline != null ? t.deadline.toString() : null);
        item.put("acceptedAt", t.acceptedAt != null ? t.acceptedAt.toString() : null);
        item.put("completedAt", t.completedAt != null ? t.completedAt.toString() : null);
        item.put("success", t.success);
        item.put("progressMilestonePct", computeProgress(db, t));
        item.put("marketSlot", t.marketSlot);

        if (t.clientId != null) {
            Client client = ClientDao.findById(db, t.clientId);
            if (client != null) {
                item.put("clientName", client.name);
                item.put("clientTier", client.tier);
            }
        }
        return item;
    }

    /** Compute real progress: completed=100%, failed=actual%, active/planned=from requirements */
    private static int computeProgress(Connection db, Task t) throws Exception {
        if (t.status == TaskStatus.COMPLETED_SUCCESS) return 100;
        if (t.status == TaskStatus.COMPLETED_FAIL) {
            // Failed tasks: compute actual progress from requirements
            List<TaskRequirement> reqs = TaskDao.listRequirements(db, t.id);
            if (reqs.isEmpty()) return 0;
            double totalProgress = 0;
            for (TaskRequirement r : reqs) {
                if (r.requiredQty > 0) {
                    totalProgress += Math.min(1.0, (double) r.completedQty / r.requiredQty);
                }
            }
            return (int) Math.round(totalProgress / reqs.size() * 100);
        }
        if (t.status == TaskStatus.ACTIVE || t.status == TaskStatus.PLANNED) {
            List<TaskRequirement> reqs = TaskDao.listRequirements(db, t.id);
            if (reqs.isEmpty()) return 0;
            double totalProgress = 0;
            for (TaskRequirement r : reqs) {
                if (r.requiredQty > 0) {
                    totalProgress += Math.min(1.0, (double) r.completedQty / r.requiredQty);
                }
            }
            return (int) Math.round(totalProgress / reqs.size() * 100);
        }
        return 0; // market tasks
    }
}
