package com.collinear.ycbench.core;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 业务日历工具类。镜像 {@code core/business_time.py}。
 *
 * <p>默认日历为周一至周五 09:00-18:00，无节假日。我们将算术运算保持在 {@code double} 小时级别
 * （Python 使用 {@link java.math.BigDecimal} 进行精确的费率除法，但我们的进度刷新在提升到
 * 浮点数需求时已经失去精度，因此统一的 {@code double} 管道匹配有效行为）。
 */
public final class BusinessTime {

    public static final LocalTime WORKDAY_START = LocalTime.of(9, 0);
    public static final LocalTime WORKDAY_END = LocalTime.of(18, 0);

    private BusinessTime() {
    }

    public static boolean isWeekday(OffsetDateTime ts) {
        DayOfWeek d = ts.getDayOfWeek();
        return d != DayOfWeek.SATURDAY && d != DayOfWeek.SUNDAY;
    }

    public static boolean isBusinessTime(OffsetDateTime ts) {
        if (!isWeekday(ts)) return false;
        LocalTime t = ts.toLocalTime();
        return !t.isBefore(WORKDAY_START) && t.isBefore(WORKDAY_END);
    }

    private static OffsetDateTime dayStart(OffsetDateTime ts) {
        return ts.withHour(WORKDAY_START.getHour())
                .withMinute(WORKDAY_START.getMinute())
                .withSecond(0).withNano(0);
    }

    private static OffsetDateTime dayEnd(OffsetDateTime ts) {
        return ts.withHour(WORKDAY_END.getHour())
                .withMinute(WORKDAY_END.getMinute())
                .withSecond(0).withNano(0);
    }

    private static OffsetDateTime nextWeekdayStart(OffsetDateTime ts) {
        OffsetDateTime cur = dayStart(ts);
        while (!isWeekday(cur)) {
            cur = dayStart(cur.plusDays(1));
        }
        return cur;
    }

    public static OffsetDateTime nextBusinessTime(OffsetDateTime ts) {
        if (isBusinessTime(ts)) return ts;
        if (!isWeekday(ts)) return nextWeekdayStart(ts);
        OffsetDateTime ds = dayStart(ts);
        OffsetDateTime de = dayEnd(ts);
        if (ts.isBefore(ds)) return ds;
        if (!ts.isBefore(de)) return nextWeekdayStart(ts.plusDays(1));
        throw new IllegalStateException("No valid business time found after " + ts);
    }

    public static OffsetDateTime addBusinessHours(OffsetDateTime ts, double hours) {
        if (hours < 0) throw new IllegalArgumentException("Negative business hours: " + hours);
        if (hours == 0) return nextBusinessTime(ts);

        OffsetDateTime cur = nextBusinessTime(ts);
        double remaining = hours;
        while (remaining > 0) {
            OffsetDateTime de = dayEnd(cur);
            double available = Duration.between(cur, de).toMillis() / 3_600_000.0;
            if (remaining <= available) {
                long millisToAdd = (long) Math.round(remaining * 3_600_000.0);
                return cur.plusNanos(millisToAdd * 1_000_000L);
            }
            remaining -= available;
            cur = nextBusinessTime(de);
        }
        return cur;
    }

    private static double businessIntervalSameDay(OffsetDateTime start, OffsetDateTime end) {
        if (!end.isAfter(start)) return 0.0;
        if (!isWeekday(start)) return 0.0;
        OffsetDateTime ds = dayStart(start);
        OffsetDateTime de = dayEnd(end);
        OffsetDateTime lo = start.isAfter(ds) ? start : ds;
        OffsetDateTime hi = end.isBefore(de) ? end : de;
        if (!hi.isAfter(lo)) return 0.0;
        return Duration.between(lo, hi).toMillis() / 3_600_000.0;
    }

    public static double businessHoursBetween(OffsetDateTime t0, OffsetDateTime t1) {
        if (!t1.isAfter(t0)) return 0.0;
        OffsetDateTime cur = t0;
        double total = 0.0;
        while (cur.isBefore(t1)) {
            OffsetDateTime nextMidnight = cur.plusDays(1)
                    .withHour(0).withMinute(0).withSecond(0).withNano(0);
            OffsetDateTime segEnd = nextMidnight.isBefore(t1) ? nextMidnight : t1;
            total += businessIntervalSameDay(cur, segEnd);
            cur = segEnd;
        }
        return total;
    }

    public static OffsetDateTime firstBusinessOfMonth(OffsetDateTime dt) {
        OffsetDateTime first = dt.withDayOfMonth(1)
                .withHour(WORKDAY_START.getHour())
                .withMinute(WORKDAY_START.getMinute())
                .withSecond(0).withNano(0);
        while (!isWeekday(first)) {
            first = first.plusDays(1)
                    .withHour(WORKDAY_START.getHour())
                    .withMinute(WORKDAY_START.getMinute())
                    .withSecond(0).withNano(0);
        }
        return first;
    }

    /** 返回严格位于 {@code (start, end]} 区间内的每月初第一个工作日的 timestamps。 */
    public static List<OffsetDateTime> iterMonthlyPayrollBoundaries(OffsetDateTime start, OffsetDateTime end) {
        List<OffsetDateTime> out = new ArrayList<>();
        if (!end.isAfter(start)) return out;

        OffsetDateTime cursor = start.withDayOfMonth(1)
                .withHour(WORKDAY_START.getHour())
                .withMinute(WORKDAY_START.getMinute())
                .withSecond(0).withNano(0);
        while (cursor.isBefore(end)) {
            OffsetDateTime boundary = firstBusinessOfMonth(cursor);
            if (boundary.isAfter(start) && !boundary.isAfter(end)) {
                out.add(boundary);
            }
            if (cursor.getMonthValue() == 12) {
                cursor = cursor.withYear(cursor.getYear() + 1).withMonth(1).withDayOfMonth(1);
            } else {
                cursor = cursor.withMonth(cursor.getMonthValue() + 1).withDayOfMonth(1);
            }
        }
        return out;
    }
}