package com.collinear.ycbench.cli.commands.task;

import com.collinear.ycbench.config.WorldConfig;
import com.collinear.ycbench.core.BusinessTime;
import com.collinear.ycbench.db.dao.EmployeeDao;
import com.collinear.ycbench.db.dao.TaskDao;
import com.collinear.ycbench.db.model.Employee;
import com.collinear.ycbench.db.model.Task;
import com.collinear.ycbench.db.model.TaskStatus;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** {@code task} 子命令的共享辅助工具：名称/UUID 解析、截止日期计算。*/
public final class TaskResolver {

    private TaskResolver() {
    }

    /**
     * 通过 UUID 或标题解析任务。如果多个标题匹配（原始 + 替换），
     * 优先顺序为 market &gt; planned &gt; active &gt; 其他。镜像
     * {@code task_commands.py:_resolve_task}。
     */
    public static Task resolveTask(Connection db, String identifier) throws SQLException {
        try {
            UUID id = UUID.fromString(identifier);
            return TaskDao.findById(db, id);
        } catch (IllegalArgumentException ignored) {
            // fall through
        }
        List<Task> matches = TaskDao.findByTitle(db, identifier);
        if (matches.isEmpty()) return null;
        if (matches.size() == 1) return matches.get(0);
        matches.sort((a, b) -> Integer.compare(priority(a.status), priority(b.status)));
        return matches.get(0);
    }

    private static int priority(TaskStatus s) {
        switch (s) {
            case MARKET: return 0;
            case PLANNED: return 1;
            case ACTIVE: return 2;
            default: return 9;
        }
    }

    /** 在 {@code companyId} 内通过 UUID 或 {@code Emp_N} 名称解析员工。*/
    public static Employee resolveEmployee(Connection db, UUID companyId, String identifier) throws SQLException {
        try {
            UUID id = UUID.fromString(identifier);
            Employee e = EmployeeDao.findById(db, id);
            return (e != null && companyId.equals(e.companyId)) ? e : null;
        } catch (IllegalArgumentException ignored) {
            // fall through
        }
        return EmployeeDao.findByName(db, companyId, identifier);
    }

    /** 截止日期 = max(min_biz_days, qty/qty_per_day) 个 {@code work_hours} 工作日。*/
    public static OffsetDateTime computeDeadline(OffsetDateTime acceptedAt, double maxDomainQty, WorldConfig cfg) {
        int workHours = cfg.workdayEndHour - cfg.workdayStartHour;
        int bizDays = Math.max(cfg.deadlineMinBizDays, (int) (maxDomainQty / cfg.deadlineQtyPerDay));
        return BusinessTime.addBusinessHours(acceptedAt, (double) bizDays * workHours);
    }
}
