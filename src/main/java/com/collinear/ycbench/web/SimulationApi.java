package com.collinear.ycbench.web;

import com.collinear.ycbench.db.dao.CompanyDao;
import com.collinear.ycbench.db.dao.EmployeeDao;
import com.collinear.ycbench.db.dao.SimStateDao;
import com.collinear.ycbench.db.dao.TaskDao;
import com.collinear.ycbench.db.dao.ScratchpadDao;
import com.collinear.ycbench.db.model.Company;
import com.collinear.ycbench.db.model.CompanyPrestige;
import com.collinear.ycbench.db.model.SimState;
import com.collinear.ycbench.db.model.TaskStatus;
import com.collinear.ycbench.db.model.Scratchpad;
import io.javalin.Javalin;

import java.sql.Connection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 仿真概览 API — 提供仪表盘所需的聚合数据。
 */
public final class SimulationApi {

    private SimulationApi() {}

    public static void register(Javalin app) {
        // 获取仿真全局状态（仪表盘用）
        app.get("/api/simulation/dashboard", ctx -> {
            try (Connection db = WebServer.openDb()) {
                SimState state = SimStateDao.findFirst(db);
                if (state == null) {
                    ctx.status(404).json(Map.of("error", "No simulation found"));
                    return;
                }
                Company company = CompanyDao.findById(db, state.companyId);
                List<CompanyPrestige> prestiges = CompanyDao.listPrestige(db, state.companyId);
                int activeCount = TaskDao.countByCompanyAndStatus(db, state.companyId, TaskStatus.ACTIVE);
                int plannedCount = TaskDao.countByCompanyAndStatus(db, state.companyId, TaskStatus.PLANNED);
                int completedOk = TaskDao.countByCompanyAndStatus(db, state.companyId, TaskStatus.COMPLETED_SUCCESS);
                int completedFail = TaskDao.countByCompanyAndStatus(db, state.companyId, TaskStatus.COMPLETED_FAIL);
                int employeeCount = EmployeeDao.countByCompany(db, state.companyId);
                long monthlyPayroll = EmployeeDao.sumSalary(db, state.companyId);
                Scratchpad sp = ScratchpadDao.find(db, state.companyId);

                Map<String, Object> dashboard = new HashMap<>();
                dashboard.put("simTime", state.simTime.toString());
                dashboard.put("horizonEnd", state.horizonEnd.toString());
                dashboard.put("companyName", company.name);
                dashboard.put("fundsCents", company.fundsCents);
                dashboard.put("fundsFormatted", formatMoney(company.fundsCents));
                dashboard.put("bankrupt", company.fundsCents < 0);
                dashboard.put("employeeCount", employeeCount);
                dashboard.put("monthlyPayrollCents", monthlyPayroll);
                dashboard.put("monthlyPayrollFormatted", formatMoney(monthlyPayroll));
                dashboard.put("activeTasks", activeCount);
                dashboard.put("plannedTasks", plannedCount);
                dashboard.put("completedSuccess", completedOk);
                dashboard.put("completedFail", completedFail);
                dashboard.put("scratchpad", sp != null ? sp.content : null);

                Map<String, Double> prestigeMap = new HashMap<>();
                for (CompanyPrestige p : prestiges) {
                    prestigeMap.put(p.domain.value, p.prestigeLevel);
                }
                dashboard.put("prestige", prestigeMap);

                // 计算跑道（月）
                if (monthlyPayroll > 0) {
                    double runwayMonths = (double) company.fundsCents / monthlyPayroll;
                    dashboard.put("runwayMonths", Math.round(runwayMonths * 10.0) / 10.0);
                } else {
                    dashboard.put("runwayMonths", -1);
                }

                ctx.json(dashboard);
            }
        });

        // 检查仿真是否存在
        app.get("/api/simulation/status", ctx -> {
            try (Connection db = WebServer.openDb()) {
                SimState state = SimStateDao.findFirst(db);
                if (state == null) {
                    ctx.json(Map.of("exists", false));
                } else {
                    ctx.json(Map.of("exists", true, "simTime", state.simTime.toString(),
                            "horizonEnd", state.horizonEnd.toString(), "seed", state.runSeed));
                }
            }
        });
    }

    private static String formatMoney(long cents) {
        boolean negative = cents < 0;
        long absCents = Math.abs(cents);
        return (negative ? "-$" : "$") + String.format("%,d", absCents / 100);
    }
}
