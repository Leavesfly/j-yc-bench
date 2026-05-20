package com.collinear.ycbench.cli;

import com.collinear.ycbench.db.Database;
import com.collinear.ycbench.db.dao.SimStateDao;
import com.collinear.ycbench.db.model.SimState;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 每个 CLI 子命令共享的辅助工具。
 *
 * <p>打开一个新的 JDBC 连接（启用外键和 WAL pragma）并运行 schema 初始化。
 * 镜像 Python CLI 中的 {@code get_db()} 上下文管理器。
 */
public final class CliContext {

    private CliContext() {
    }

    /** 打开连接，确保 schema 就位，启用自动提交（每个 DAO 写入立即提交）。*/
    public static Connection openDb() throws SQLException, IOException {
        Connection c = Database.open();
        c.setAutoCommit(true);
        Database.initSchema(c);
        return c;
    }

    public static SimState requireSimState(Connection c) throws SQLException {
        SimState s = SimStateDao.findFirst(c);
        if (s == null) {
            throw new CliException("No simulation found. Run `yc-bench sim init` first.");
        }
        return s;
    }

    /** 将 {@code yyyy-MM-dd} 或遗留格式 {@code MM/dd/yyyy} 解析为 UTC {@code 09:00} 日期时间。*/
    public static OffsetDateTime parseMmDdYyyy09Utc(String s) {
        DateTimeFormatter fmt = s.contains("-")
                ? DateTimeFormatter.ofPattern("yyyy-MM-dd")
                : DateTimeFormatter.ofPattern("MM/dd/yyyy");
        try {
            LocalDate d = LocalDate.parse(s, fmt);
            return d.atTime(9, 0).atOffset(ZoneOffset.UTC);
        } catch (Exception ex) {
            throw new CliException("Invalid date format: " + s + ". Use YYYY-MM-DD or MM/DD/YYYY.");
        }
    }

    /** 将 {@code MM/dd/yyyy} 解析为 UTC 午夜时间（用于 ledger 过滤器）。*/
    public static OffsetDateTime parseMmDdYyyyMidnightUtc(String s) {
        try {
            LocalDate d = LocalDate.parse(s, DateTimeFormatter.ofPattern("MM/dd/yyyy"));
            return d.atStartOfDay().atOffset(ZoneOffset.UTC);
        } catch (Exception ex) {
            throw new CliException("Invalid date format: " + s + ". Use MM/DD/YYYY.");
        }
    }

    /** 将 {@code yyyy-MM} 解析为该月的第一天。*/
    public static LocalDate parseMonth(String s) {
        try {
            LocalDateTime t = LocalDateTime.parse(s + "-01T00:00");
            return t.toLocalDate();
        } catch (Exception ex) {
            throw new CliException("Invalid month format: " + s + ". Use YYYY-MM.");
        }
    }

    /** 向 {@code start} 添加 {@code years}，镜像 Python 的 {@code .replace(year=...)}。*/
    public static OffsetDateTime addYears(OffsetDateTime start, int years) {
        return start.plusYears(years);
    }

    /** 非检查异常，用于将验证失败转换为 JSON 错误并以退出码 1 退出。*/
    public static final class CliException extends RuntimeException {
        public CliException(String message) {
            super(message);
        }
    }
}
