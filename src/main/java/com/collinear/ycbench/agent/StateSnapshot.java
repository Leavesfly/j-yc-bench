package com.collinear.ycbench.agent;

import com.collinear.ycbench.db.dao.CompanyDao;
import com.collinear.ycbench.db.dao.EmployeeDao;
import com.collinear.ycbench.db.dao.ScratchpadDao;
import com.collinear.ycbench.db.dao.SimStateDao;
import com.collinear.ycbench.db.dao.TaskDao;
import com.collinear.ycbench.db.model.Company;
import com.collinear.ycbench.db.model.Scratchpad;
import com.collinear.ycbench.db.model.SimState;
import com.collinear.ycbench.db.model.TaskStatus;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/** 读取当前模拟状态并转换为供 LLM 使用的 {@link Prompt.Snapshot}。 */
public final class StateSnapshot {

    private StateSnapshot() {
    }

    public static Prompt.Snapshot read(Connection db, UUID companyId) throws SQLException {
        SimState st = SimStateDao.findByCompany(db, companyId);
        Company co = CompanyDao.findById(db, companyId);
        Prompt.Snapshot s = new Prompt.Snapshot();
        s.simTime = st.simTime.toString();
        s.horizonEnd = st.horizonEnd.toString();
        s.fundsCents = co.fundsCents;
        s.activeTasks = TaskDao.countByCompanyAndStatus(db, companyId, TaskStatus.ACTIVE);
        s.plannedTasks = TaskDao.countByCompanyAndStatus(db, companyId, TaskStatus.PLANNED);
        s.employeeCount = EmployeeDao.countByCompany(db, companyId);
        s.monthlyPayrollCents = EmployeeDao.sumSalary(db, companyId);
        s.bankrupt = co.fundsCents < 0;
        Scratchpad sp = ScratchpadDao.find(db, companyId);
        s.scratchpad = (sp == null || sp.content == null || sp.content.isEmpty()) ? null : sp.content;
        return s;
    }
}
