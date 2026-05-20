package com.collinear.ycbench.core;

import com.collinear.ycbench.config.ConfigLoader;
import com.collinear.ycbench.config.WorldConfig;
import com.collinear.ycbench.core.handlers.BankruptcyHandler;
import com.collinear.ycbench.core.handlers.HorizonEndHandler;
import com.collinear.ycbench.core.handlers.TaskCompleteHandler;
import com.collinear.ycbench.core.handlers.TaskHalfHandler;
import com.collinear.ycbench.db.dao.ClientDao;
import com.collinear.ycbench.db.dao.CompanyDao;
import com.collinear.ycbench.db.dao.EmployeeDao;
import com.collinear.ycbench.db.dao.LedgerDao;
import com.collinear.ycbench.db.dao.SimStateDao;
import com.collinear.ycbench.db.model.Client;
import com.collinear.ycbench.db.model.ClientTrust;
import com.collinear.ycbench.db.model.Company;
import com.collinear.ycbench.db.model.CompanyPrestige;
import com.collinear.ycbench.db.model.Employee;
import com.collinear.ycbench.db.model.EventType;
import com.collinear.ycbench.db.model.LedgerCategory;
import com.collinear.ycbench.db.model.LedgerEntry;
import com.collinear.ycbench.db.model.SimEvent;
import com.collinear.ycbench.db.model.SimState;
import com.collinear.ycbench.db.model.Task;
import com.collinear.ycbench.db.model.TaskAssignment;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 离散事件模拟引擎。镜像 {@code core/engine.py:advance_time}。
 *
 * <p>主循环：在每一步我们选择 {@code (next_payroll, next_event, target_time)} 中最早的一个并推进到该时间点。
 * 平局时按此确切顺序打破，因此发薪日总是在与其时间戳相同的任何事件之前落在一天开始时。
 */
public final class Engine {

    private Engine() {
    }

    /** 为所有员工运行工资扣除。如果之后破产则返回 {@code true}。 */
    public static boolean applyPayroll(Connection db, UUID companyId, OffsetDateTime time) throws SQLException {
        Company co = CompanyDao.findById(db, companyId);
        List<Employee> emps = EmployeeDao.listByCompany(db, companyId);
        long totalPayroll = 0;
        for (Employee e : emps) {
            totalPayroll += e.salaryCents;
            LedgerEntry le = new LedgerEntry();
            le.id = UUID.randomUUID();
            le.companyId = companyId;
            le.occurredAt = time;
            le.category = LedgerCategory.MONTHLY_PAYROLL;
            le.amountCents = -e.salaryCents;
            le.refType = "employee";
            le.refId = e.id;
            LedgerDao.insert(db, le);
        }
        co.fundsCents -= totalPayroll;
        CompanyDao.updateFunds(db, companyId, co.fundsCents);
        return co.fundsCents < 0;
    }

    /** 将事件分派到其处理程序并将结果转换为 JSON map。 */
    public static Map<String, Object> dispatchEvent(Connection db, SimEvent event,
                                                    OffsetDateTime simTime, UUID companyId) throws SQLException {
        WorldConfig wc = ConfigLoader.getWorldConfig();

        if (event.eventType == EventType.TASK_HALF_PROGRESS) {
            TaskHalfHandler.Result r = TaskHalfHandler.handle(db, event);
            Eta.recalculateEtas(db, companyId, simTime, null, wc.taskProgressMilestones);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("type", "task_half");
            out.put("task_id", r.taskId.toString());
            out.put("milestone_pct", r.milestonePct);
            out.put("handled", r.handled);
            return out;
        }

        if (event.eventType == EventType.TASK_COMPLETED) {
            TaskCompleteHandler.Result r = TaskCompleteHandler.handle(db, event, simTime);
            Eta.recalculateEtas(db, companyId, simTime, null, wc.taskProgressMilestones);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("type", "task_completed");
            out.put("task_id", r.taskId.toString());

            // 用操作细节丰富代理信息
            Task taskRow = com.collinear.ycbench.db.dao.TaskDao.findById(db, r.taskId);
            String clientName = null;
            String taskTitle = null;
            String deadlineInfo = null;
            int empCount = 0;
            long salaryBumpTotal = 0;
            if (taskRow != null) {
                taskTitle = taskRow.title;
                if (taskRow.clientId != null) {
                    Client cl = ClientDao.findById(db, taskRow.clientId);
                    if (cl != null) clientName = cl.name;
                }
                if (taskRow.deadline != null && taskRow.completedAt != null) {
                    double hoursDiff = java.time.Duration.between(taskRow.completedAt, taskRow.deadline).toMillis() / 3_600_000.0;
                    deadlineInfo = (hoursDiff >= 0 ? "ahead by " : "late by ")
                            + String.format("%.0fh", Math.abs(hoursDiff));
                }
                List<TaskAssignment> assignments = com.collinear.ycbench.db.dao.TaskDao.listAssignmentsForTask(db, r.taskId);
                empCount = assignments.size();
                for (TaskAssignment a : assignments) {
                    Employee e = EmployeeDao.findById(db, a.employeeId);
                    if (e != null && r.success) {
                        salaryBumpTotal += (long) (e.salaryCents * wc.salaryBumpPct);
                    }
                }
            }
            out.put("task_title", taskTitle);
            out.put("client_name", clientName);
            out.put("success", r.success);
            out.put("funds_delta", r.fundsDelta);
            out.put("listed_reward", r.listedReward);
            out.put("trust_delta", r.trustDelta);
            out.put("deadline_margin", deadlineInfo);
            out.put("employees_assigned", empCount);
            out.put("salary_bump_total_cents", salaryBumpTotal);
            out.put("bankrupt", r.bankrupt);
            return out;
        }

        if (event.eventType == EventType.HORIZON_END) {
            HorizonEndHandler.Result r = HorizonEndHandler.handle(db, event);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("type", "horizon_end");
            out.put("reached", r.reached);
            return out;
        }

        if (event.eventType == EventType.BANKRUPTCY) {
            BankruptcyHandler.Result r = BankruptcyHandler.handle(db, event);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("type", "bankruptcy");
            out.put("bankrupt", r.bankrupt);
            return out;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "unknown");
        out.put("event_type", event.eventType.value);
        return out;
    }

    public static void applyPrestigeDecay(Connection db, UUID companyId, double daysElapsed) throws SQLException {
        WorldConfig wc = ConfigLoader.getWorldConfig();
        if (wc.prestigeDecayPerDay <= 0 || daysElapsed <= 0) return;
        double decay = wc.prestigeDecayPerDay * daysElapsed;
        double floor = wc.prestigeMin;
        for (CompanyPrestige p : CompanyDao.listPrestige(db, companyId)) {
            double next = Math.max(floor, p.prestigeLevel - decay);
            if (next != p.prestigeLevel) {
                CompanyDao.updatePrestige(db, companyId, p.domain, next);
            }
        }
    }

    public static void applyTrustDecay(Connection db, UUID companyId, double daysElapsed) throws SQLException {
        WorldConfig wc = ConfigLoader.getWorldConfig();
        if (wc.trustDecayPerDay <= 0 || daysElapsed <= 0) return;
        double decay = wc.trustDecayPerDay * daysElapsed;
        double floor = wc.trustMin;
        for (ClientTrust ct : ClientDao.listTrustByCompany(db, companyId)) {
            double next = Math.max(floor, ct.trustLevel - decay);
            if (next != ct.trustLevel) {
                ClientDao.updateTrust(db, companyId, ct.clientId, next);
            }
        }
    }

    /** 从当前 {@code sim_time} 推进模拟到 {@code targetTime}。 */
    public static AdvanceResult advanceTime(Connection db, UUID companyId,
                                            OffsetDateTime targetTime) throws SQLException {
        SimState st = SimStateDao.findByCompany(db, companyId);
        if (st == null) throw new IllegalStateException("No SimState for company " + companyId);
        OffsetDateTime currentTime = st.simTime;
        OffsetDateTime oldTime = currentTime;

        Company company = CompanyDao.findById(db, companyId);
        long startingFunds = company.fundsCents;

        AdvanceResult result = new AdvanceResult();
        result.oldSimTime = oldTime.toString();
        result.newSimTime = targetTime.toString();

        List<OffsetDateTime> payrolls = BusinessTime.iterMonthlyPayrollBoundaries(currentTime, targetTime);
        int payrollIdx = 0;

        while (true) {
            OffsetDateTime nextPayroll = payrollIdx < payrolls.size() ? payrolls.get(payrollIdx) : null;
            SimEvent nextEvent = EventOps.fetchNextEvent(db, companyId, targetTime);

            // 选择最早的动作（平局时 payroll < event < target）
            String actionType = "target";
            OffsetDateTime actionTime = targetTime;
            int actionPriority = 2;

            if (nextPayroll != null && !nextPayroll.isAfter(targetTime)) {
                if (nextPayroll.isBefore(actionTime)
                        || (nextPayroll.isEqual(actionTime) && 0 < actionPriority)) {
                    actionType = "payroll";
                    actionTime = nextPayroll;
                    actionPriority = 0;
                }
            }
            if (nextEvent != null) {
                OffsetDateTime evTime = nextEvent.scheduledAt;
                if (evTime.isBefore(actionTime)
                        || (evTime.isEqual(actionTime) && 1 < actionPriority)) {
                    actionType = "event";
                    actionTime = evTime;
                    actionPriority = 1;
                }
            }

            // 刷新进度 + 应用衰减直到 action_time
            if (actionTime.isAfter(currentTime)) {
                double daysElapsed = java.time.Duration.between(currentTime, actionTime).toMillis() / 86_400_000.0;
                Progress.flushProgress(db, companyId, currentTime, actionTime);
                applyPrestigeDecay(db, companyId, daysElapsed);
                applyTrustDecay(db, companyId, daysElapsed);
                currentTime = actionTime;
            }

            if ("target".equals(actionType)) break;

            if ("payroll".equals(actionType)) {
                boolean bankrupt = applyPayroll(db, companyId, currentTime);
                result.payrollsApplied++;
                payrollIdx++;

                company = CompanyDao.findById(db, companyId);
                Map<String, Object> wake = new LinkedHashMap<>();
                wake.put("type", "monthly_payroll");
                wake.put("funds_after", company.fundsCents);
                result.wakeEvents.add(wake);

                if (bankrupt) {
                    EventOps.insertEvent(db, companyId, EventType.BANKRUPTCY, currentTime,
                            Map.of("reason", "funds_negative_after_payroll"),
                            "bankruptcy:" + currentTime);
                    result.bankrupt = true;
                }
                // 总是在发薪日停止，以便代理重新获得控制权
                break;
            }

            if ("event".equals(actionType)) {
                Map<String, Object> eventResult = dispatchEvent(db, nextEvent, currentTime, companyId);
                EventOps.consumeEvent(db, nextEvent);
                result.eventsProcessed++;
                result.wakeEvents.add(eventResult);

                if (nextEvent.eventType == EventType.HORIZON_END) {
                    result.horizonReached = true;
                    break;
                }
                if (nextEvent.eventType == EventType.BANKRUPTCY) {
                    result.bankrupt = true;
                    break;
                }
                Object bk = eventResult.get("bankrupt");
                if (bk instanceof Boolean && (Boolean) bk) {
                    result.bankrupt = true;
                    break;
                }
            }
        }

        SimStateDao.updateSimTime(db, companyId, currentTime);

        Company endCo = CompanyDao.findById(db, companyId);
        result.balanceDelta = endCo.fundsCents - startingFunds;
        result.newSimTime = currentTime.toString();
        return result;
    }

    /** 抑制未使用导入警告：保留以与未来重构保持一致。 */
    @SuppressWarnings("unused")
    private static final Comparator<OffsetDateTime> EARLIEST_FIRST = Comparator.naturalOrder();
}
