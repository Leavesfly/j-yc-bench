package com.collinear.ycbench.services;

import com.collinear.ycbench.config.WorldConfig;
import com.collinear.ycbench.db.dao.ClientDao;
import com.collinear.ycbench.db.dao.CompanyDao;
import com.collinear.ycbench.db.dao.EmployeeDao;
import com.collinear.ycbench.db.dao.TaskDao;
import com.collinear.ycbench.db.model.Client;
import com.collinear.ycbench.db.model.ClientTrust;
import com.collinear.ycbench.db.model.Company;
import com.collinear.ycbench.db.model.CompanyPrestige;
import com.collinear.ycbench.db.model.Domain;
import com.collinear.ycbench.db.model.Employee;
import com.collinear.ycbench.db.model.EmployeeSkillRate;
import com.collinear.ycbench.db.model.Task;
import com.collinear.ycbench.db.model.TaskRequirement;
import com.collinear.ycbench.db.model.TaskStatus;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 种子化一个全新的世界（公司 + 声望 + 员工 + 客户 + 市场任务）。
 * 镜像 {@code services/seed_world.py:seed_world}。
 */
public final class SeedWorld {

    /** 员工和客户在不同运行种子之间是确定性的。 */
    private static final long FIXED_WORLD_SEED = 1L;

    private SeedWorld() {
    }

    public static final class Request {
        public final long runSeed;
        public final String companyName;
        public final int horizonYears;
        public final int employeeCount;
        public final int marketTaskCount;
        public final WorldConfig cfg;
        public final OffsetDateTime startDate;

        public Request(long runSeed, String companyName, int horizonYears,
                       int employeeCount, int marketTaskCount, WorldConfig cfg,
                       OffsetDateTime startDate) {
            this.runSeed = runSeed;
            this.companyName = companyName;
            this.horizonYears = horizonYears;
            this.employeeCount = employeeCount;
            this.marketTaskCount = marketTaskCount;
            this.cfg = cfg;
            this.startDate = startDate;
        }
    }

    public static final class Result {
        public final UUID companyId;
        public final OffsetDateTime seededAt;

        public Result(UUID companyId, OffsetDateTime seededAt) {
            this.companyId = companyId;
            this.seededAt = seededAt;
        }
    }

    public static Result seed(Connection db, Request req) throws SQLException {
        if (req.employeeCount <= 0) throw new IllegalArgumentException("employee_count must be positive");
        if (req.marketTaskCount <= 0) throw new IllegalArgumentException("market_task_count must be positive");

        Company company = seedCompany(db, req);
        seedCompanyPrestige(db, company, req.cfg);
        seedEmployees(db, company, req);
        List<Client> clients = seedClients(db, company, req);
        seedMarketTasks(db, company, req, clients);

        return new Result(company.id, req.startDate);
    }

    // ------------------------------------------------------------------

    private static Company seedCompany(Connection db, Request req) throws SQLException {
        Company co = new Company();
        co.id = UUID.randomUUID();
        co.name = req.companyName;
        co.fundsCents = req.cfg.initialFundsCents;
        CompanyDao.insert(db, co);
        return co;
    }

    private static void seedCompanyPrestige(Connection db, Company co, WorldConfig cfg) throws SQLException {
        for (Domain d : Domain.all()) {
            CompanyPrestige p = new CompanyPrestige();
            p.companyId = co.id;
            p.domain = d;
            p.prestigeLevel = cfg.initialPrestigeLevel;
            CompanyDao.insertPrestige(db, p);
        }
    }

    private static void seedEmployees(Connection db, Company co, Request req) throws SQLException {
        List<GenerateEmployees.Generated> generated =
                GenerateEmployees.generate(FIXED_WORLD_SEED, req.employeeCount, req.cfg);
        for (GenerateEmployees.Generated g : generated) {
            Employee e = new Employee();
            e.id = UUID.randomUUID();
            e.companyId = co.id;
            e.name = g.name;
            e.tier = g.tier;
            e.workHoursPerDay = g.workHoursPerDay;
            e.salaryCents = g.salaryCents;
            EmployeeDao.insert(db, e);
            for (Map.Entry<Domain, Double> sr : g.ratesByDomain.entrySet()) {
                EmployeeSkillRate s = new EmployeeSkillRate();
                s.employeeId = e.id;
                s.domain = sr.getKey();
                s.rateDomainPerHour = sr.getValue();
                EmployeeDao.insertSkill(db, s);
            }
        }
    }

    private static List<Client> seedClients(Connection db, Company co, Request req) throws SQLException {
        List<GenerateClients.Generated> generated =
                GenerateClients.generate(FIXED_WORLD_SEED, req.cfg.numClients, req.cfg);
        List<Client> out = new ArrayList<>(generated.size());
        for (GenerateClients.Generated g : generated) {
            Client c = new Client();
            c.id = UUID.randomUUID();
            c.name = g.name;
            c.rewardMultiplier = g.rewardMultiplier;
            c.tier = g.tier;
            c.specialtyDomains = g.specialtyDomains;
            c.loyalty = g.loyalty;
            ClientDao.insert(db, c);
            out.add(c);

            ClientTrust t = new ClientTrust();
            t.companyId = co.id;
            t.clientId = c.id;
            t.trustLevel = 0.0;
            ClientDao.insertTrust(db, t);
        }
        return out;
    }

    private static void seedMarketTasks(Connection db, Company co, Request req, List<Client> clients) throws SQLException {
        List<List<String>> specialties = new ArrayList<>(clients.size());
        List<Double> mults = new ArrayList<>(clients.size());
        for (Client c : clients) {
            specialties.add(c.specialtyDomains == null ? List.of() : c.specialtyDomains);
            mults.add(c.rewardMultiplier);
        }
        List<GenerateTasks.Generated> generated = GenerateTasks.generate(
                req.runSeed, req.marketTaskCount, req.cfg, specialties, mults);

        for (int slot = 0; slot < generated.size(); slot++) {
            GenerateTasks.Generated g = generated.get(slot);
            Client client = clients.isEmpty() ? null : clients.get(g.clientIndex % clients.size());

            Task t = new Task();
            t.id = UUID.randomUUID();
            t.companyId = null;
            t.clientId = client == null ? null : client.id;
            t.status = TaskStatus.MARKET;
            t.title = g.title;
            t.requiredPrestige = g.requiredPrestige;
            t.rewardFundsCents = g.rewardFundsCents;
            t.rewardPrestigeDelta = g.rewardPrestigeDelta;
            t.skillBoostPct = g.skillBoostPct;
            t.progressMilestonePct = 0;
            t.requiredTrust = g.requiredTrust;
            t.marketSlot = slot;
            TaskDao.insert(db, t);

            for (Map.Entry<Domain, Double> r : g.requirements.entrySet()) {
                TaskRequirement tr = new TaskRequirement();
                tr.taskId = t.id;
                tr.domain = r.getKey();
                tr.requiredQty = r.getValue();
                tr.completedQty = 0.0;
                TaskDao.insertRequirement(db, tr);
            }
        }
    }
}
