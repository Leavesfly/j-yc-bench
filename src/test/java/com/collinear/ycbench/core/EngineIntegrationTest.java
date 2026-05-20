package com.collinear.ycbench.core;

import com.collinear.ycbench.db.Database;
import com.collinear.ycbench.db.dao.CompanyDao;
import com.collinear.ycbench.db.dao.EmployeeDao;
import com.collinear.ycbench.db.dao.LedgerDao;
import com.collinear.ycbench.db.dao.SimStateDao;
import com.collinear.ycbench.db.model.Company;
import com.collinear.ycbench.db.model.Employee;
import com.collinear.ycbench.db.model.LedgerCategory;
import com.collinear.ycbench.db.model.LedgerEntry;
import com.collinear.ycbench.db.model.SimState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link Engine#applyPayroll} 的集成测试。*/
final class EngineIntegrationTest {

    private Connection db;
    private UUID companyId;

    @BeforeEach
    void setUp() throws Exception {
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/test-db"));
        String url = "jdbc:sqlite:target/test-db/engine-" + System.nanoTime() + ".db";
        System.setProperty("DATABASE_URL", url);
        db = Database.open(url);
        db.setAutoCommit(true);
        Database.initSchema(db);

        Company co = new Company();
        co.id = UUID.randomUUID();
        co.name = "EngineTestCo";
        co.fundsCents = 10_000_00L;        // 10,000 美元
        CompanyDao.insert(db, co);
        companyId = co.id;

        SimState st = new SimState();
        st.companyId = co.id;
        st.simTime = OffsetDateTime.of(2025, 1, 1, 9, 0, 0, 0, ZoneOffset.UTC);
        st.runSeed = 1;
        st.horizonEnd = OffsetDateTime.of(2026, 1, 1, 9, 0, 0, 0, ZoneOffset.UTC);
        st.replenishCounter = 0;
        SimStateDao.insert(db, st);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (db != null) db.close();
        System.clearProperty("DATABASE_URL");
    }

    private void hireEmployee(String name, long salaryCents) throws Exception {
        Employee e = new Employee();
        e.id = UUID.randomUUID();
        e.companyId = companyId;
        e.name = name;
        e.tier = "junior";
        e.workHoursPerDay = 9.0;
        e.salaryCents = salaryCents;
        EmployeeDao.insert(db, e);
    }

    @Test
    void payrollDeductsAllSalariesAndWritesLedgerEntries() throws Exception {
        hireEmployee("A", 200_00L);   // 200 美元
        hireEmployee("B", 300_00L);   // 300 美元

        OffsetDateTime t = OffsetDateTime.of(2025, 2, 3, 9, 0, 0, 0, ZoneOffset.UTC);
        boolean bankrupt = Engine.applyPayroll(db, companyId, t);
        assertFalse(bankrupt);

        Company co = CompanyDao.findById(db, companyId);
        // 10,000 - (200 + 300) = 9,500。
        assertEquals(9_500_00L, co.fundsCents);

        List<LedgerEntry> entries = LedgerDao.listByCompany(db, companyId, null, null, null);
        assertEquals(2, entries.size());
        for (LedgerEntry le : entries) {
            assertEquals(LedgerCategory.MONTHLY_PAYROLL, le.category);
            assertTrue(le.amountCents < 0, "Payroll entries must be negative.");
            assertEquals("employee", le.refType);
        }
        long sum = entries.stream().mapToLong(le -> le.amountCents).sum();
        assertEquals(-500_00L, sum);
    }

    @Test
    void payrollMarksCompanyBankruptWhenFundsGoNegative() throws Exception {
        hireEmployee("Big", 11_000_00L);    // 超过资金

        OffsetDateTime t = OffsetDateTime.of(2025, 2, 3, 9, 0, 0, 0, ZoneOffset.UTC);
        boolean bankrupt = Engine.applyPayroll(db, companyId, t);
        assertTrue(bankrupt);

        Company co = CompanyDao.findById(db, companyId);
        assertEquals(-1_000_00L, co.fundsCents);
    }

    @Test
    void payrollWithZeroEmployeesIsNoop() throws Exception {
        OffsetDateTime t = OffsetDateTime.of(2025, 2, 3, 9, 0, 0, 0, ZoneOffset.UTC);
        boolean bankrupt = Engine.applyPayroll(db, companyId, t);
        assertFalse(bankrupt);
        Company co = CompanyDao.findById(db, companyId);
        assertEquals(10_000_00L, co.fundsCents);
        assertEquals(0, LedgerDao.listByCompany(db, companyId, null, null, null).size());
    }
}
