package com.collinear.ycbench.core.handlers;

import com.collinear.ycbench.db.dao.TaskDao;
import com.collinear.ycbench.db.model.SimEvent;
import com.collinear.ycbench.db.model.Task;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/** {@code task_half_progress} 事件的处理程序。镜像 {@code handlers/task_half.py}。 */
public final class TaskHalfHandler {

    public static final class Result {
        public UUID taskId;
        public boolean handled;
        public int milestonePct;
    }

    public static Result handle(Connection db, SimEvent event) throws SQLException {
        Result r = new Result();
        r.taskId = UUID.fromString((String) event.payload.get("task_id"));
        Object mp = event.payload.get("milestone_pct");
        r.milestonePct = mp instanceof Number ? ((Number) mp).intValue() : 50;
        Task task = TaskDao.findById(db, r.taskId);
        if (task == null) {
            r.handled = false;
            return r;
        }
        task.progressMilestonePct = Math.max(task.progressMilestonePct, r.milestonePct);
        TaskDao.update(db, task);
        r.handled = true;
        return r;
    }

    private TaskHalfHandler() {
    }
}
