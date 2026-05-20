package com.collinear.ycbench.cli.commands.company;

import com.collinear.ycbench.cli.CliContext;
import com.collinear.ycbench.cli.JsonOutput;
import com.collinear.ycbench.core.BusinessTime;
import com.collinear.ycbench.db.dao.CompanyDao;
import com.collinear.ycbench.db.dao.EmployeeDao;
import com.collinear.ycbench.db.dao.TaskDao;
import com.collinear.ycbench.db.model.Company;
import com.collinear.ycbench.db.model.CompanyPrestige;
import com.collinear.ycbench.db.model.SimState;
import com.collinear.ycbench.db.model.TaskStatus;
import picocli.CommandLine;

import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/** {@code yc-bench company status} */
@CommandLine.Command(name = "status", description = "公司状态、资金、声望、任务、风险。")
public final class CompanyStatusCmd implements Callable<Integer> {

    @Override
    public Integer call() {
        try (Connection db = CliContext.openDb()) {
            SimState st = CliContext.requireSimState(db);
            Company co = CompanyDao.findById(db, st.companyId);
            if (co == null) return JsonOutput.err("Company not found.");

            Map<String, Double> prestige = new LinkedHashMap<>();
            for (CompanyPrestige p : CompanyDao.listPrestige(db, co.id)) {
                prestige.put(p.domain.value, p.prestigeLevel);
            }

            int activeCount = TaskDao.countByCompanyAndStatus(db, co.id, TaskStatus.ACTIVE);
            int plannedCount = TaskDao.countByCompanyAndStatus(db, co.id, TaskStatus.PLANNED);
            int success = TaskDao.countByCompanyAndStatus(db, co.id, TaskStatus.COMPLETED_SUCCESS);
            int fail = TaskDao.countByCompanyAndStatus(db, co.id, TaskStatus.COMPLETED_FAIL);
            int cancelled = TaskDao.countByCompanyAndStatus(db, co.id, TaskStatus.CANCELLED);
            int completed = success + fail;

            int employeeCount = EmployeeDao.countByCompany(db, co.id);
            long totalSalary = EmployeeDao.sumSalary(db, co.id);

            OffsetDateTime nextPayroll = nextPayrollDate(st.simTime);
            Double monthsRunway = totalSalary > 0
                    ? Math.round((double) co.fundsCents / totalSalary * 100.0) / 100.0
                    : null;

            Map<String, Object> tasks = new LinkedHashMap<>();
            tasks.put("active", activeCount);
            tasks.put("planned", plannedCount);
            tasks.put("completed", completed);
            tasks.put("cancelled", cancelled);

            Map<String, Object> risk = new LinkedHashMap<>();
            risk.put("months_runway", monthsRunway);
            risk.put("bankrupt", co.fundsCents < 0);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("company_id", co.id.toString());
            out.put("company_name", co.name);
            out.put("funds_cents", co.fundsCents);
            out.put("prestige", prestige);
            out.put("sim_time", st.simTime.toString());
            out.put("horizon_end", st.horizonEnd.toString());
            out.put("tasks", tasks);
            out.put("employees", employeeCount);
            out.put("next_payroll", nextPayroll.toString());
            out.put("monthly_payroll_cents", totalSalary);
            out.put("risk", risk);
            return JsonOutput.ok(out);
        } catch (CliContext.CliException e) {
            return JsonOutput.err(e.getMessage());
        } catch (Exception ex) {
            return JsonOutput.err("company status failed: " + ex.getMessage());
        }
    }

    private static OffsetDateTime nextPayrollDate(OffsetDateTime simTime) {
        OffsetDateTime nextMonth = simTime.getMonthValue() == 12
                ? simTime.withYear(simTime.getYear() + 1).withMonth(1).withDayOfMonth(1)
                : simTime.withMonth(simTime.getMonthValue() + 1).withDayOfMonth(1);
        return BusinessTime.firstBusinessOfMonth(nextMonth);
    }
}
