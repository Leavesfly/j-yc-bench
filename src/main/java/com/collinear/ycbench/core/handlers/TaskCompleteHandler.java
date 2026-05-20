package com.collinear.ycbench.core.handlers;

import com.collinear.ycbench.config.ConfigLoader;
import com.collinear.ycbench.config.WorldConfig;
import com.collinear.ycbench.core.Progress;
import com.collinear.ycbench.db.dao.ClientDao;
import com.collinear.ycbench.db.dao.CompanyDao;
import com.collinear.ycbench.db.dao.EmployeeDao;
import com.collinear.ycbench.db.dao.LedgerDao;
import com.collinear.ycbench.db.dao.TaskDao;
import com.collinear.ycbench.db.model.Client;
import com.collinear.ycbench.db.model.ClientTrust;
import com.collinear.ycbench.db.model.Company;
import com.collinear.ycbench.db.model.CompanyPrestige;
import com.collinear.ycbench.db.model.Domain;
import com.collinear.ycbench.db.model.Employee;
import com.collinear.ycbench.db.model.EmployeeSkillRate;
import com.collinear.ycbench.db.model.LedgerCategory;
import com.collinear.ycbench.db.model.LedgerEntry;
import com.collinear.ycbench.db.model.SimEvent;
import com.collinear.ycbench.db.model.Task;
import com.collinear.ycbench.db.model.TaskAssignment;
import com.collinear.ycbench.db.model.TaskRequirement;
import com.collinear.ycbench.db.model.TaskStatus;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@code task_completed} 事件的处理程序。镜像 {@code handlers/task_complete.py}。
 *
 * <p>结果分支：
 * <ul>
 *   <li>{@code completedAt <= deadline}：成功 → 奖励资金 + 声望 + 技能提升 +
 *       顶级贡献者的薪资提升；客户信任度以递减回报增长。</li>
 *   <li>{@code completedAt > deadline}：失败 → 声望惩罚 + 可选资金惩罚；
 *       客户信任度减少 {@code trustFailPenalty}。</li>
 * </ul>
 * 两种结果之后都会运行跨客户信任度衰减。
 */
public final class TaskCompleteHandler {

    public static final class Result {
        public UUID taskId;
        public boolean success;
        public long fundsDelta;
        public long listedReward;
        public Map<String, Double> prestigeChanges = new HashMap<>();
        public double trustDelta;
        public boolean bankrupt;
    }

    public static Result handle(Connection db, SimEvent event, OffsetDateTime simTime) throws SQLException {
        Result r = new Result();
        UUID taskId = UUID.fromString((String) event.payload.get("task_id"));
        r.taskId = taskId;

        Task task = TaskDao.findById(db, taskId);
        UUID companyId = task.companyId;
        WorldConfig wc = ConfigLoader.getWorldConfig();

        // 在应用结果之前将进度最终化为 100%
        List<TaskRequirement> reqs = TaskDao.listRequirements(db, taskId);
        for (TaskRequirement req : reqs) {
            if (req.completedQty < req.requiredQty) {
                TaskDao.updateRequirementCompleted(db, taskId, req.domain, req.requiredQty);
                req.completedQty = req.requiredQty;
            }
        }

        task.completedAt = simTime;
        boolean success = !simTime.isAfter(task.deadline);
        r.success = success;

        if (success) {
            task.status = TaskStatus.COMPLETED_SUCCESS;
            task.success = true;
            TaskDao.update(db, task);

            // 奖励资金
            Company company = CompanyDao.findById(db, companyId);
            company.fundsCents += task.rewardFundsCents;
            CompanyDao.updateFunds(db, companyId, company.fundsCents);
            r.fundsDelta = task.rewardFundsCents;

            LedgerEntry le = new LedgerEntry();
            le.id = UUID.randomUUID();
            le.companyId = companyId;
            le.occurredAt = simTime;
            le.category = LedgerCategory.TASK_REWARD;
            le.amountCents = task.rewardFundsCents;
            le.refType = "task";
            le.refId = taskId;
            LedgerDao.insert(db, le);

            // 每个所需域的声望提升
            for (TaskRequirement req : reqs) {
                CompanyPrestige cp = CompanyDao.findPrestige(db, companyId, req.domain);
                if (cp == null) continue;
                double old = cp.prestigeLevel;
                double next = Math.min(wc.prestigeMax, old + task.rewardPrestigeDelta);
                CompanyDao.updatePrestige(db, companyId, req.domain, next);
                r.prestigeChanges.put(req.domain.value, next - old);
            }

            applySkillBoost(db, task, reqs, wc);
            applySalaryBump(db, task, wc);
        } else {
            task.status = TaskStatus.COMPLETED_FAIL;
            task.success = false;
            TaskDao.update(db, task);

            // 声望惩罚
            double penalty = wc.penaltyFailMultiplier * task.rewardPrestigeDelta;
            for (TaskRequirement req : reqs) {
                CompanyPrestige cp = CompanyDao.findPrestige(db, companyId, req.domain);
                if (cp == null) continue;
                double old = cp.prestigeLevel;
                double next = Math.max(wc.prestigeMin, old - penalty);
                CompanyDao.updatePrestige(db, companyId, req.domain, next);
                r.prestigeChanges.put(req.domain.value, next - old);
            }

            // 资金惩罚
            if (wc.penaltyFailFundsPct > 0) {
                long advertised = task.advertisedRewardCents != null
                        ? task.advertisedRewardCents : task.rewardFundsCents;
                long penaltyCents = (long) (advertised * wc.penaltyFailFundsPct);
                Company company = CompanyDao.findById(db, companyId);
                company.fundsCents -= penaltyCents;
                CompanyDao.updateFunds(db, companyId, company.fundsCents);
                r.fundsDelta = -penaltyCents;

                LedgerEntry le = new LedgerEntry();
                le.id = UUID.randomUUID();
                le.companyId = companyId;
                le.occurredAt = simTime;
                le.category = LedgerCategory.TASK_REWARD;
                le.amountCents = -penaltyCents;
                le.refType = "task";
                le.refId = taskId;
                LedgerDao.insert(db, le);
            }
        }

        // --- 客户信任度更新 ---
        if (task.clientId != null) {
            ClientTrust ct = ClientDao.findTrust(db, companyId, task.clientId);
            if (ct != null) {
                double oldLevel = ct.trustLevel;
                double newLevel;
                if (success) {
                    double ratio = oldLevel / wc.trustMax;
                    double gain = wc.trustGainBase * Math.pow(1.0 - ratio, wc.trustGainDiminishingPower);
                    newLevel = Math.min(wc.trustMax, oldLevel + gain);
                } else {
                    newLevel = Math.max(wc.trustMin, oldLevel - wc.trustFailPenalty);
                }
                newLevel = round3(newLevel);
                ClientDao.updateTrust(db, companyId, task.clientId, newLevel);
                r.trustDelta = newLevel - oldLevel;
            }

            // 跨客户衰减
            if (wc.trustCrossClientDecay > 0) {
                List<ClientTrust> others = ClientDao.listOtherTrust(db, companyId, task.clientId);
                for (ClientTrust o : others) {
                    if (o.trustLevel > wc.trustMin) {
                        double next = Math.max(wc.trustMin, round3(o.trustLevel - wc.trustCrossClientDecay));
                        ClientDao.updateTrust(db, companyId, o.clientId, next);
                    }
                }
            }
        }

        r.listedReward = task.advertisedRewardCents != null
                ? task.advertisedRewardCents : task.rewardFundsCents;

        Company c = CompanyDao.findById(db, companyId);
        r.bankrupt = c.fundsCents < 0;
        return r;
    }

    // ------------------------------------------------------------------

    private static void applySkillBoost(Connection db, Task task, List<TaskRequirement> reqs,
                                        WorldConfig wc) throws SQLException {
        if (task.skillBoostPct <= 0) return;
        List<TaskAssignment> assignments = TaskDao.listAssignmentsForTask(db, task.id);

        Set<Domain> taskDomains = EnumSet.noneOf(Domain.class);
        for (TaskRequirement r : reqs) taskDomains.add(r.domain);

        for (Domain domain : taskDomains) {
            List<EmployeeSkillRate> empRates = new ArrayList<>();
            for (TaskAssignment a : assignments) {
                EmployeeSkillRate skill = EmployeeDao.findSkill(db, a.employeeId, domain);
                if (skill != null) empRates.add(skill);
            }
            empRates.sort(Comparator.comparingDouble((EmployeeSkillRate s) -> s.rateDomainPerHour).reversed());

            int top = Math.min(Progress.EFFICIENT_TEAM_SIZE, empRates.size());
            for (int i = 0; i < top; i++) {
                EmployeeSkillRate skill = empRates.get(i);
                double boost = skill.rateDomainPerHour * task.skillBoostPct;
                double next = Math.min(skill.rateDomainPerHour + boost, wc.skillRateMax);
                EmployeeDao.updateSkillRate(db, skill.employeeId, skill.domain, next);
            }
        }
    }

    private static void applySalaryBump(Connection db, Task task, WorldConfig wc) throws SQLException {
        if (wc.salaryBumpPct <= 0) return;
        Map<String, Long> tierMid = Map.of(
                "junior", (wc.salaryJunior.minCents + wc.salaryJunior.maxCents) / 2,
                "mid",    (wc.salaryMid.minCents + wc.salaryMid.maxCents) / 2,
                "senior", (wc.salarySenior.minCents + wc.salarySenior.maxCents) / 2
        );
        for (TaskAssignment a : TaskDao.listAssignmentsForTask(db, task.id)) {
            Employee e = EmployeeDao.findById(db, a.employeeId);
            if (e == null || e.salaryCents >= wc.salaryMaxCents) continue;
            long bump = (long) (tierMid.getOrDefault(e.tier, 0L) * wc.salaryBumpPct);
            long next = Math.min(wc.salaryMaxCents, e.salaryCents + bump);
            EmployeeDao.updateSalary(db, a.employeeId, next);
        }
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private TaskCompleteHandler() {
    }
}
