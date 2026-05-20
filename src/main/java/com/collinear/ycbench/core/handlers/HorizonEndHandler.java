package com.collinear.ycbench.core.handlers;

import com.collinear.ycbench.db.model.SimEvent;

import java.sql.Connection;

/** {@code horizon_end} 事件的处理程序。 */
public final class HorizonEndHandler {

    public static final class Result {
        public boolean reached = true;
    }

    public static Result handle(Connection db, SimEvent event) {
        return new Result();
    }

    private HorizonEndHandler() {
    }
}
