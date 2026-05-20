package com.collinear.ycbench.core.handlers;

import com.collinear.ycbench.db.model.SimEvent;

import java.sql.Connection;

/** {@code bankruptcy} 事件的处理程序。始终返回 true。 */
public final class BankruptcyHandler {

    public static final class Result {
        public boolean bankrupt = true;
    }

    public static Result handle(Connection db, SimEvent event) {
        return new Result();
    }

    private BankruptcyHandler() {
    }
}
