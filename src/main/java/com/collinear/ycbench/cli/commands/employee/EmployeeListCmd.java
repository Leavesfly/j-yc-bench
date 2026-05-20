package com.collinear.ycbench.cli.commands.employee;

import com.collinear.ycbench.cli.CliContext;
import com.collinear.ycbench.cli.JsonOutput;
import com.collinear.ycbench.db.dao.EmployeeDao;
import com.collinear.ycbench.db.dao.TaskDao;
import com.collinear.ycbench.db.model.Employee;
import com.collinear.ycbench.db.model.EmployeeSkillRate;
import com.collinear.ycbench.db.model.SimState;
import com.collinear.ycbench.db.model.Task;
import com.collinear.ycbench.db.model.TaskAssignment;
import com.collinear.ycbench.db.model.TaskStatus;
import picocli.CommandLine;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/** {@code yc-bench employee list} */
@CommandLine.Command(name = "list", description = "列出员工及其技能和当前分配。")
public final class EmployeeListCmd implements Callable<Integer> {

    @Override
    public Integer call() {
        try (Connection db = CliContext.openDb()) {
            SimState st = CliContext.requireSimState(db);
            List<Employee> emps = EmployeeDao.listByCompany(db, st.companyId);

            List<Map<String, Object>> rows = new ArrayList<>(emps.size());
            for (Employee e : emps) {
                // 该员工的活跃任务标题
                List<String> activeTitles = new ArrayList<>();
                for (TaskAssignment a : TaskDao.listAssignmentsForEmployee(db, e.id)) {
                    Task t = TaskDao.findById(db, a.taskId);
                    if (t != null && t.status == TaskStatus.ACTIVE) activeTitles.add(t.title);
                }
                Map<String, Double> rates = new LinkedHashMap<>();
                for (EmployeeSkillRate s : EmployeeDao.listSkills(db, e.id)) {
                    rates.put(s.domain.value, Math.round(s.rateDomainPerHour * 100.0) / 100.0);
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("employee_id", e.id.toString());
                row.put("name", e.name);
                row.put("tier", e.tier);
                row.put("salary_cents", e.salaryCents);
                row.put("skill_rates", rates);
                row.put("active_tasks", activeTitles);
                rows.add(row);
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("count", rows.size());
            out.put("employees", rows);
            return JsonOutput.ok(out);
        } catch (CliContext.CliException e) {
            return JsonOutput.err(e.getMessage());
        } catch (Exception ex) {
            return JsonOutput.err("employee list failed: " + ex.getMessage());
        }
    }
}
