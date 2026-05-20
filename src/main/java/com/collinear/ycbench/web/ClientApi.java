package com.collinear.ycbench.web;

import com.collinear.ycbench.db.dao.ClientDao;
import com.collinear.ycbench.db.dao.SimStateDao;
import com.collinear.ycbench.db.dao.TaskDao;
import com.collinear.ycbench.db.model.*;
import io.javalin.Javalin;

import java.sql.Connection;
import java.util.*;

public final class ClientApi {

    private ClientApi() {}

    public static void register(Javalin app) {
        app.get("/api/clients", ctx -> {
            try (Connection db = WebServer.openDb()) {
                SimState state = SimStateDao.findFirst(db);
                if (state == null) {
                    ctx.status(404).json(Map.of("error", "No simulation"));
                    return;
                }
                List<Client> clients = ClientDao.listAllOrderedByName(db);
                List<ClientTrust> trusts = ClientDao.listTrustByCompany(db, state.companyId);

                Map<UUID, Double> trustMap = new HashMap<>();
                for (ClientTrust t : trusts) {
                    trustMap.put(t.clientId, t.trustLevel);
                }

                List<Map<String, Object>> result = new ArrayList<>();
                for (Client c : clients) {
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", c.id.toString());
                    item.put("name", c.name);
                    item.put("tier", c.tier);
                    item.put("rewardMultiplier", c.rewardMultiplier);
                    item.put("specialtyDomains", c.specialtyDomains);
                    item.put("loyalty", c.loyalty);
                    item.put("trustLevel", trustMap.getOrDefault(c.id, 0.0));

                    // 判断是否是 RAT（loyalty < 0.5 通常是 RAT）
                    item.put("isRat", c.loyalty < 0.5);

                    // 该客户的任务统计
                    int successCount = TaskDao.countByCompanyAndClientAndStatus(
                            db, state.companyId, c.id, TaskStatus.COMPLETED_SUCCESS);
                    int failCount = TaskDao.countByCompanyAndClientAndStatus(
                            db, state.companyId, c.id, TaskStatus.COMPLETED_FAIL);
                    item.put("completedSuccess", successCount);
                    item.put("completedFail", failCount);

                    result.add(item);
                }
                ctx.json(result);
            }
        });
    }
}
