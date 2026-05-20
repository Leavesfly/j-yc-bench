package com.collinear.ycbench.web;

import com.collinear.ycbench.db.dao.CompanyDao;
import com.collinear.ycbench.db.dao.SimStateDao;
import com.collinear.ycbench.db.model.Company;
import com.collinear.ycbench.db.model.CompanyPrestige;
import com.collinear.ycbench.db.model.SimState;
import io.javalin.Javalin;

import java.sql.Connection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class CompanyApi {

    private CompanyApi() {}

    public static void register(Javalin app) {
        app.get("/api/company", ctx -> {
            try (Connection db = WebServer.openDb()) {
                SimState state = SimStateDao.findFirst(db);
                if (state == null) {
                    ctx.status(404).json(Map.of("error", "No simulation"));
                    return;
                }
                Company company = CompanyDao.findById(db, state.companyId);
                List<CompanyPrestige> prestiges = CompanyDao.listPrestige(db, state.companyId);

                Map<String, Object> result = new HashMap<>();
                result.put("id", company.id.toString());
                result.put("name", company.name);
                result.put("fundsCents", company.fundsCents);

                Map<String, Double> prestigeMap = new HashMap<>();
                for (CompanyPrestige p : prestiges) {
                    prestigeMap.put(p.domain.value, p.prestigeLevel);
                }
                result.put("prestige", prestigeMap);
                ctx.json(result);
            }
        });
    }
}
