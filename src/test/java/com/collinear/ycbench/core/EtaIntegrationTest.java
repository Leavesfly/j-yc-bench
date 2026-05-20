package com.collinear.ycbench.core;

import com.collinear.ycbench.config.ConfigLoader;
import com.collinear.ycbench.config.WorldConfig;
import com.collinear.ycbench.db.Database;
import com.collinear.ycbench.db.dao.CompanyDao;
import com.collinear.ycbench.db.dao.EmployeeDao;
import com.collinear.ycbench.db.dao.TaskDao;
import com.collinear.ycbench.db.model.Company;
import com.collinear.ycbench.db.model.Domain;
import com.collinear.ycbench.db.model.Employee;
import com.collinear.ycbench.db.model.EmployeeSkillRate;
import com.collinear.ycbench.db.model.Task;
import com.collinear.ycbench.db.model.TaskAssignment;
import com.collinear.ycbench.db.model.TaskRequirement;
import com.collinear.ycbench.db.model.TaskStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link Eta} 的集成测试。使用内存中的 SQLite 数据库。*/
final class EtaIntegrationTest {

    private Connection db;
    private UUID companyId;

    @BeforeEach
    void setUp() throws Exception {
        // 将运行时指向 target/ 下的每个测试专用的 sqlite 文件。
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/test-db"));
        String url = "jdbc:sqlite:target/test-db/eta-" + System.nanoTime() + ".db";
        System.setProperty("DATABASE_URL", url);
        db = Database.open(url);
        db.setAutoCommit(true);
        Database.initSchema(db);

        Company co = new Company();
        co.id = UUID.randomUUID();
        co.name = "EtaTestCo";
        co.fundsCents = 100_000_00L;
        CompanyDao.insert(db, co);
        companyId = co.id;
    }

    @AfterEach
    void tearDown() throws Exception {
        if (db != null) db.close();
        System.clearProperty("DATABASE_URL");
    }

    private UUID insertEmployee(double rate, Domain domain) throws Exception {
        Employee e = new Employee();
        e.id = UUID.randomUUID();
        e.companyId = companyId;
        e.name = "Emp-" + e.id.toString().substring(0, 6);
        e.tier = "junior";
        e.workHoursPerDay = 9.0;
        e.salaryCents = 0;
        EmployeeDao.insert(db, e);

        EmployeeSkillRate s = new EmployeeSkillRate();
        s.employeeId = e.id;
        s.domain = domain;
        s.rateDomainPerHour = rate;
        EmployeeDao.insertSkill(db, s);
        return e.id;
    }

    private UUID insertActiveTask(double requiredQty, Domain domain) throws Exception {
        Task t = new Task();
        t.id = UUID.randomUUID();
        t.companyId = companyId;
        t.status = TaskStatus.ACTIVE;
        t.title = "Task-" + t.id.toString().substring(0, 6);
        t.requiredPrestige = 1;
        t.rewardFundsCents = 10000;
        t.rewardPrestigeDelta = 0.1;
        t.skillBoostPct = 0.0;
        t.progressMilestonePct = 0;
        t.requiredTrust = 0;
        t.acceptedAt = OffsetDateTime.of(2025, 1, 6, 9, 0, 0, 0, ZoneOffset.UTC);
        TaskDao.insert(db, t);

        TaskRequirement r = new TaskRequirement();
        r.taskId = t.id;
        r.domain = domain;
        r.requiredQty = requiredQty;
        r.completedQty = 0.0;
        TaskDao.insertRequirement(db, r);
        return t.id;
    }

    private void assignEmployee(UUID taskId, UUID empId) throws Exception {
        TaskAssignment a = new TaskAssignment();
        a.taskId = taskId;
        a.employeeId = empId;
        a.assignedAt = OffsetDateTime.of(2025, 1, 6, 9, 0, 0, 0, ZoneOffset.UTC);
        TaskDao.insertAssignment(db, a);
    }

    @Test
    void completionTimeIsRequirementOverRate() throws Exception {
        // 模式检查：required_qty ∈ [200, 25000]。1800 单位 @ 200/小时 -> 正好 9 个业务小时。
        UUID emp = insertEmployee(200.0, Domain.RESEARCH);
        UUID task = insertActiveTask(1800.0, Domain.RESEARCH);
        assignEmployee(task, emp);

        OffsetDateTime now = OffsetDateTime.of(2025, 1, 6, 9, 0, 0, 0, ZoneOffset.UTC);
        List<Progress.EffectiveRate> rates = Progress.computeEffectiveRates(db, companyId);
        OffsetDateTime completion = Eta.solveTaskCompletionTime(db, task, now, rates);
        assertNotNull(completion);
        // 从周一 09:00 起 9 小时 → 周一 18:00。
        assertTrue(completion.equals(OffsetDateTime.of(2025, 1, 6, 18, 0, 0, 0, ZoneOffset.UTC)),
                "Expected Mon 18:00 but got " + completion);
    }

    @Test
    void completionUnreachableWhenDomainHasNoRate() throws Exception {
        UUID emp = insertEmployee(10.0, Domain.TRAINING);          // wrong domain
        UUID task = insertActiveTask(500.0, Domain.RESEARCH);      // satisfies CHECK
        assignEmployee(task, emp);

        OffsetDateTime now = OffsetDateTime.of(2025, 1, 6, 9, 0, 0, 0, ZoneOffset.UTC);
        List<Progress.EffectiveRate> rates = Progress.computeEffectiveRates(db, companyId);
        OffsetDateTime completion = Eta.solveTaskCompletionTime(db, task, now, rates);
        assertNull(completion, "Unreachable task should yield null completion time.");
    }

    @Test
    void bottleneckIsSlowestDomain() throws Exception {
        // 双领域任务：研究非常快，培训慢 → 完成时间等于较慢的那个。
        UUID empFast = insertEmployee(2000.0, Domain.RESEARCH);
        UUID empSlow = insertEmployee(200.0, Domain.TRAINING);

        Task t = new Task();
        t.id = UUID.randomUUID();
        t.companyId = companyId;
        t.status = TaskStatus.ACTIVE;
        t.title = "MultiTask";
        t.requiredPrestige = 1;
        t.rewardFundsCents = 0;
        t.rewardPrestigeDelta = 0;
        t.skillBoostPct = 0;
        t.progressMilestonePct = 0;
        t.requiredTrust = 0;
        t.acceptedAt = OffsetDateTime.of(2025, 1, 6, 9, 0, 0, 0, ZoneOffset.UTC);
        TaskDao.insert(db, t);

        for (Domain d : new Domain[]{Domain.RESEARCH, Domain.TRAINING}) {
            TaskRequirement r = new TaskRequirement();
            r.taskId = t.id;
            r.domain = d;
            r.requiredQty = 1800.0;       // 满足 [200, 25000]
            r.completedQty = 0;
            TaskDao.insertRequirement(db, r);
        }
        assignEmployee(t.id, empFast);
        assignEmployee(t.id, empSlow);

        OffsetDateTime now = OffsetDateTime.of(2025, 1, 6, 9, 0, 0, 0, ZoneOffset.UTC);
        List<Progress.EffectiveRate> rates = Progress.computeEffectiveRates(db, companyId);
        OffsetDateTime completion = Eta.solveTaskCompletionTime(db, t.id, now, rates);
        // 慢：1800/200 = 9 小时；快：1800/2000 = 0.9 小时。瓶颈 = 9 小时 → 周一 18:00。
        assertTrue(completion.equals(OffsetDateTime.of(2025, 1, 6, 18, 0, 0, 0, ZoneOffset.UTC)),
                "Bottleneck domain should drive completion: " + completion);
    }
}
