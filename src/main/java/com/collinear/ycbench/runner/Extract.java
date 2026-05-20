package com.collinear.ycbench.runner;

import com.collinear.ycbench.config.ConfigLoader;
import com.collinear.ycbench.config.WorldConfig;
import com.collinear.ycbench.db.JdbcUtils;
import com.collinear.ycbench.db.dao.ClientDao;
import com.collinear.ycbench.db.dao.CompanyDao;
import com.collinear.ycbench.db.dao.EmployeeDao;
import com.collinear.ycbench.db.dao.LedgerDao;
import com.collinear.ycbench.db.dao.ScratchpadDao;
import com.collinear.ycbench.db.dao.TaskDao;
import com.collinear.ycbench.db.model.Client;
import com.collinear.ycbench.db.model.ClientTrust;
import com.collinear.ycbench.db.model.Company;
import com.collinear.ycbench.db.model.CompanyPrestige;
import com.collinear.ycbench.db.model.Domain;
import com.collinear.ycbench.db.model.Employee;
import com.collinear.ycbench.db.model.EmployeeSkillRate;
import com.collinear.ycbench.db.model.LedgerEntry;
import com.collinear.ycbench.db.model.Scratchpad;
import com.collinear.ycbench.db.model.Task;
import com.collinear.ycbench.db.model.TaskAssignment;
import com.collinear.ycbench.db.model.TaskRequirement;
import com.collinear.ycbench.db.model.TaskStatus;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 运行结束时的数据提取。镜像 {@code runner/extract.py}。
 *
 * <p>Python 版本通过重放每个任务完成来重建完整的声望/信任时间序列。Java 端口保持相同的顶层键集合，
 * 但仅提供更简单的声望和客户信任"快照"视图——完整重建可以由后处理器从包含的 {@code tasks} + {@code ledger} 推导。
 */
public final class Extract {

    private Extract() {
    }

    public static Map<String, Object> extractTimeSeries(Connection db, UUID companyId) throws SQLException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("funds", extractFunds(db, companyId));
        out.put("prestige", extractPrestige(db, companyId));
        out.put("tasks", extractTasks(db, companyId));
        out.put("ledger", extractLedger(db, companyId));
        out.put("client_trust", extractClientTrust(db, companyId));
        out.put("employees", extractEmployees(db, companyId));
        out.put("assignments", extractAssignments(db, companyId));
        out.put("clients", extractClients(db, companyId));
        out.put("scratchpad", extractScratchpad(db, companyId));
        return out;
    }

    // ------------------------------------------------------------------

    private static List<Map<String, Object>> extractFunds(Connection db, UUID companyId) throws SQLException {
        List<LedgerEntry> entries = LedgerDao.listByCompany(db, companyId, null, null, null);
        Company co = CompanyDao.findById(db, companyId);
        long totalDelta = 0;
        for (LedgerEntry e : entries) totalDelta += e.amountCents;
        long initial = co.fundsCents - totalDelta;

        List<Map<String, Object>> points = new ArrayList<>();
        if (!entries.isEmpty()) {
            Map<String, Object> start = new LinkedHashMap<>();
            start.put("time", entries.get(0).occurredAt.toString());
            start.put("funds_cents", initial);
            start.put("event", "start");
            points.add(start);
        }
        long running = initial;
        for (LedgerEntry e : entries) {
            running += e.amountCents;
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("time", e.occurredAt.toString());
            p.put("funds_cents", running);
            p.put("event", e.category.value);
            points.add(p);
        }
        return points;
    }

    /**
     * 通过从初始声望向前走来重建声望历史。
     * 镜像 {@code runner/extract.py:_extract_prestige}：应用任务增量（成功/失败）
     * 和事件之间的声望衰减。
     */
    private static List<Map<String, Object>> extractPrestige(Connection db, UUID companyId) throws SQLException {
        WorldConfig wc = ConfigLoader.getWorldConfig();

        List<CompanyPrestige> rows = CompanyDao.listPrestige(db, companyId);
        if (rows.isEmpty()) return new ArrayList<>();

        // 稳定的域排序（Python 按字符串值排序）。
        TreeMap<String, Double> domainLevels = new TreeMap<>();
        for (CompanyPrestige p : rows) domainLevels.put(p.domain.value, wc.initialPrestigeLevel);

        // 已完成的任务（成功 | 失败）按 completedAt 排序。
        List<Task> tasks = TaskDao.listByCompany(db, companyId);
        tasks.removeIf(t -> t.completedAt == null
                || (t.status != TaskStatus.COMPLETED_SUCCESS && t.status != TaskStatus.COMPLETED_FAIL));
        tasks.sort(Comparator.comparing(t -> t.completedAt));

        // 缓存任务 -> 域列表。
        Map<UUID, List<String>> taskDomains = new HashMap<>();
        for (Task t : tasks) {
            List<String> ds = new ArrayList<>();
            for (TaskRequirement r : TaskDao.listRequirements(db, t.id)) ds.add(r.domain.value);
            taskDomains.put(t.id, ds);
        }

        List<Map<String, Object>> events = new ArrayList<>();
        OffsetDateTime lastEventTime = null;

        if (!tasks.isEmpty()) {
            OffsetDateTime firstTime = tasks.get(0).completedAt;
            for (Map.Entry<String, Double> e : domainLevels.entrySet()) {
                Map<String, Object> pt = new LinkedHashMap<>();
                pt.put("time", firstTime.toString());
                pt.put("domain", e.getKey());
                pt.put("level", round4(e.getValue()));
                events.add(pt);
            }
            lastEventTime = firstTime;
        }

        for (Task t : tasks) {
            if (lastEventTime != null && t.completedAt.isAfter(lastEventTime)) {
                double days = secondsBetween(lastEventTime, t.completedAt) / 86400.0;
                double decay = wc.prestigeDecayPerDay * days;
                for (Map.Entry<String, Double> e : domainLevels.entrySet()) {
                    domainLevels.put(e.getKey(), Math.max(wc.prestigeMin, e.getValue() - decay));
                }
            }

            List<String> domains = taskDomains.getOrDefault(t.id, List.of());
            double delta = t.rewardPrestigeDelta;
            boolean success = t.status == TaskStatus.COMPLETED_SUCCESS;

            for (String d : domains) {
                Double cur = domainLevels.get(d);
                if (cur == null) continue;
                double next;
                if (success) {
                    next = Math.min(wc.prestigeMax, cur + delta);
                } else {
                    double penalty = wc.penaltyFailMultiplier * delta;
                    next = Math.max(wc.prestigeMin, cur - penalty);
                }
                domainLevels.put(d, next);
                Map<String, Object> pt = new LinkedHashMap<>();
                pt.put("time", t.completedAt.toString());
                pt.put("domain", d);
                pt.put("level", round4(next));
                events.add(pt);
            }
            lastEventTime = t.completedAt;
        }
        return events;
    }

    private static List<Map<String, Object>> extractLedger(Connection db, UUID companyId) throws SQLException {
        List<LedgerEntry> entries = LedgerDao.listByCompany(db, companyId, null, null, null);
        List<Map<String, Object>> rows = new ArrayList<>(entries.size());
        for (LedgerEntry e : entries) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("time", e.occurredAt.toString());
            r.put("category", e.category.value);
            r.put("amount_cents", e.amountCents);
            rows.add(r);
        }
        return rows;
    }

    /**
     * 重建客户信任历史。镜像 {@code runner/extract.py:_extract_client_trust} —— 
     * 遍历已完成/失败的任务，应用增益/惩罚和事件间衰减（每客户，所有客户）。
     */
    private static List<Map<String, Object>> extractClientTrust(Connection db, UUID companyId) throws SQLException {
        WorldConfig wc = ConfigLoader.getWorldConfig();

        List<ClientTrust> trustRows = ClientDao.listTrustByCompany(db, companyId);
        if (trustRows.isEmpty()) return new ArrayList<>();

        // 按客户名称排序以获得稳定的顺序（Python 按 Client.name 排序）。
        Map<UUID, String> clientNames = new HashMap<>();
        Map<UUID, Double> clientLoyalty = new HashMap<>();
        for (ClientTrust ct : trustRows) {
            Client c = ClientDao.findById(db, ct.clientId);
            if (c == null) continue;
            clientNames.put(ct.clientId, c.name);
            clientLoyalty.put(ct.clientId, c.loyalty);
        }
        // 按名称字母顺序排列客户 ID。
        List<UUID> orderedClients = new ArrayList<>(clientNames.keySet());
        orderedClients.sort(Comparator.comparing(clientNames::get));

        // 绑定到客户的已完成任务，按完成时间排序。
        List<Task> tasks = TaskDao.listByCompany(db, companyId);
        tasks.removeIf(t -> t.completedAt == null || t.clientId == null
                || (t.status != TaskStatus.COMPLETED_SUCCESS && t.status != TaskStatus.COMPLETED_FAIL));
        tasks.sort(Comparator.comparing(t -> t.completedAt));

        Map<UUID, Double> trustLevels = new HashMap<>();
        for (UUID cid : clientNames.keySet()) trustLevels.put(cid, 0.0);

        List<Map<String, Object>> points = new ArrayList<>();
        OffsetDateTime lastEventTime = null;

        if (!tasks.isEmpty()) {
            OffsetDateTime first = tasks.get(0).completedAt;
            for (UUID cid : orderedClients) {
                Map<String, Object> pt = new LinkedHashMap<>();
                pt.put("time", first.toString());
                pt.put("client_name", clientNames.get(cid));
                pt.put("trust_level", 0.0);
                pt.put("loyalty", clientLoyalty.getOrDefault(cid, 0.0));
                points.add(pt);
            }
            lastEventTime = first;
        }

        for (Task t : tasks) {
            UUID cid = t.clientId;
            if (!trustLevels.containsKey(cid)) continue;

            if (lastEventTime != null && t.completedAt.isAfter(lastEventTime)) {
                double days = secondsBetween(lastEventTime, t.completedAt) / 86400.0;
                double decay = wc.trustDecayPerDay * days;
                for (Map.Entry<UUID, Double> e : trustLevels.entrySet()) {
                    trustLevels.put(e.getKey(), Math.max(wc.trustMin, e.getValue() - decay));
                }
            }

            double cur = trustLevels.get(cid);
            double next;
            if (t.status == TaskStatus.COMPLETED_SUCCESS) {
                double ratio = cur / wc.trustMax;
                double gain = wc.trustGainBase * Math.pow(Math.max(0.0, 1.0 - ratio), wc.trustGainDiminishingPower);
                next = Math.min(wc.trustMax, cur + gain);
            } else {
                next = Math.max(wc.trustMin, cur - wc.trustFailPenalty);
            }
            trustLevels.put(cid, next);

            Map<String, Object> pt = new LinkedHashMap<>();
            pt.put("time", t.completedAt.toString());
            pt.put("client_name", clientNames.get(cid));
            pt.put("trust_level", round4(next));
            pt.put("loyalty", clientLoyalty.getOrDefault(cid, 0.0));
            points.add(pt);

            lastEventTime = t.completedAt;
        }
        return points;
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static long secondsBetween(OffsetDateTime a, OffsetDateTime b) {
        return ChronoUnit.SECONDS.between(a, b);
    }

    private static List<Map<String, Object>> extractTasks(Connection db, UUID companyId) throws SQLException {
        List<Task> tasks = TaskDao.listByCompany(db, companyId);
        tasks.removeIf(t -> t.status == TaskStatus.MARKET);
        tasks.sort(Comparator.comparing((Task t) -> t.acceptedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));
        List<Map<String, Object>> rows = new ArrayList<>(tasks.size());
        for (Task t : tasks) {
            List<TaskRequirement> reqs = TaskDao.listRequirements(db, t.id);
            List<String> domains = new ArrayList<>(reqs.size());
            for (TaskRequirement r : reqs) domains.add(r.domain.value);

            String clientName = null;
            if (t.clientId != null) {
                Client c = ClientDao.findById(db, t.clientId);
                if (c != null) clientName = c.name;
            }

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("task_id", t.id.toString());
            r.put("title", t.title);
            r.put("client_name", clientName);
            r.put("required_prestige", t.requiredPrestige);
            r.put("required_trust", t.requiredTrust);
            r.put("reward_funds_cents", t.rewardFundsCents);
            r.put("advertised_reward_cents", t.advertisedRewardCents == null
                    ? t.rewardFundsCents : t.advertisedRewardCents);
            r.put("reward_prestige_delta", t.rewardPrestigeDelta);
            r.put("status", t.status.value);
            r.put("accepted_at", t.acceptedAt == null ? null : t.acceptedAt.toString());
            r.put("deadline", t.deadline == null ? null : t.deadline.toString());
            r.put("completed_at", t.completedAt == null ? null : t.completedAt.toString());
            r.put("domains", domains);
            r.put("success", t.success);
            rows.add(r);
        }
        return rows;
    }

    private static List<Map<String, Object>> extractEmployees(Connection db, UUID companyId) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Employee emp : EmployeeDao.listByCompany(db, companyId)) {
            Map<String, Double> rates = new LinkedHashMap<>();
            for (EmployeeSkillRate s : EmployeeDao.listSkills(db, emp.id)) {
                rates.put(s.domain.value, Math.round(s.rateDomainPerHour * 10000.0) / 10000.0);
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("name", emp.name);
            r.put("tier", emp.tier);
            r.put("salary_cents", emp.salaryCents);
            r.put("skill_rates", rates);
            rows.add(r);
        }
        return rows;
    }

    private static List<Map<String, Object>> extractAssignments(Connection db, UUID companyId) throws SQLException {
        List<Task> tasks = TaskDao.listByCompany(db, companyId);
        tasks.removeIf(t -> t.status == TaskStatus.MARKET);
        tasks.sort(Comparator.comparing((Task t) -> t.acceptedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));
        List<Map<String, Object>> rows = new ArrayList<>(tasks.size());
        for (Task t : tasks) {
            List<String> names = new ArrayList<>();
            for (TaskAssignment a : TaskDao.listAssignmentsForTask(db, t.id)) {
                Employee e = EmployeeDao.findById(db, a.employeeId);
                if (e != null) names.add(e.name);
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("task_title", t.title);
            r.put("status", t.status.value);
            r.put("employees_assigned", names);
            r.put("num_assigned", names.size());
            r.put("accepted_at", t.acceptedAt == null ? null : t.acceptedAt.toString());
            r.put("completed_at", t.completedAt == null ? null : t.completedAt.toString());
            r.put("success", t.success);
            rows.add(r);
        }
        return rows;
    }

    private static List<Map<String, Object>> extractClients(Connection db, UUID companyId) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ClientTrust ct : ClientDao.listTrustByCompany(db, companyId)) {
            Client c = ClientDao.findById(db, ct.clientId);
            if (c == null) continue;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("name", c.name);
            r.put("loyalty", Math.round(c.loyalty * 10000.0) / 10000.0);
            r.put("is_rat", c.loyalty < -0.3);
            r.put("tier", c.tier);
            r.put("specialty_domains", c.specialtyDomains == null ? List.of() : c.specialtyDomains);
            r.put("final_trust", Math.round(ct.trustLevel * 10000.0) / 10000.0);
            rows.add(r);
        }
        return rows;
    }

    private static String extractScratchpad(Connection db, UUID companyId) throws SQLException {
        Scratchpad sp = ScratchpadDao.find(db, companyId);
        if (sp == null || sp.content == null || sp.content.isEmpty()) return null;
        return sp.content;
    }

    /** 未使用的辅助方法，保留供将来扩展。 */
    @SuppressWarnings("unused")
    private static String quote(String s) {
        return JdbcUtils.tsStr(null);
    }
}
