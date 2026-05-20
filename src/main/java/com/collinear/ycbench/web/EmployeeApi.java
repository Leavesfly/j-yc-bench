package com.collinear.ycbench.web;

import com.collinear.ycbench.db.dao.EmployeeDao;
import com.collinear.ycbench.db.dao.SimStateDao;
import com.collinear.ycbench.db.dao.TaskDao;
import com.collinear.ycbench.db.model.Employee;
import com.collinear.ycbench.db.model.EmployeeSkillRate;
import com.collinear.ycbench.db.model.SimState;
import com.collinear.ycbench.db.model.Task;
import com.collinear.ycbench.db.model.TaskAssignment;
import com.collinear.ycbench.db.model.TaskStatus;
import io.javalin.Javalin;

import java.sql.Connection;
import java.util.*;
import java.util.stream.Collectors;

public final class EmployeeApi {

    private EmployeeApi() {}

    public static void register(Javalin app) {
        app.get("/api/employees", ctx -> {
            try (Connection db = WebServer.openDb()) {
                SimState state = SimStateDao.findFirst(db);
                if (state == null) {
                    ctx.status(404).json(Map.of("error", "No simulation"));
                    return;
                }
                List<Employee> employees = EmployeeDao.listByCompany(db, state.companyId);
                List<UUID> empIds = employees.stream().map(e -> e.id).collect(Collectors.toList());
                List<EmployeeSkillRate> allSkills = EmployeeDao.listSkillsForEmployees(db, empIds);

                // 按员工分组技能
                Map<UUID, List<EmployeeSkillRate>> skillMap = allSkills.stream()
                        .collect(Collectors.groupingBy(s -> s.employeeId));

                List<Map<String, Object>> result = new ArrayList<>();
                for (Employee emp : employees) {
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", emp.id.toString());
                    item.put("name", emp.name);
                    item.put("tier", emp.tier);
                    item.put("workHoursPerDay", emp.workHoursPerDay);
                    item.put("salaryCents", emp.salaryCents);
                    item.put("salaryFormatted", "$" + String.format("%,d", emp.salaryCents / 100) + "/mo");

                    // 技能
                    Map<String, Double> skills = new HashMap<>();
                    List<EmployeeSkillRate> empSkills = skillMap.getOrDefault(emp.id, List.of());
                    for (EmployeeSkillRate s : empSkills) {
                        skills.put(s.domain.value, s.rateDomainPerHour);
                    }
                    item.put("skills", skills);

                    // 只统计当前活跃任务的分配
                    List<TaskAssignment> assignments = TaskDao.listAssignmentsForEmployee(db, emp.id);
                    long activeAssignments = assignments.stream()
                            .filter(a -> {
                                try {
                                    Task t = TaskDao.findById(db, a.taskId);
                                    return t != null && t.status == TaskStatus.ACTIVE;
                                } catch (Exception ex) { return false; }
                            }).count();
                    item.put("assignedTasks", activeAssignments);

                    result.add(item);
                }
                ctx.json(result);
            }
        });
    }
}
