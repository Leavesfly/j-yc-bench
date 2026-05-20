package com.collinear.ycbench.core;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link BusinessTime} 的单元测试。*/
final class BusinessTimeTest {

    private static OffsetDateTime utc(int y, int mo, int d, int h, int mi) {
        return OffsetDateTime.of(y, mo, d, h, mi, 0, 0, ZoneOffset.UTC);
    }

    @Test
    void weekdayDetection() {
        // 2025-01-06 是星期一。
        assertTrue(BusinessTime.isWeekday(utc(2025, 1, 6, 10, 0)));
        assertTrue(BusinessTime.isWeekday(utc(2025, 1, 10, 10, 0)));   // 星期五
        assertFalse(BusinessTime.isWeekday(utc(2025, 1, 11, 10, 0)));  // 星期六
        assertFalse(BusinessTime.isWeekday(utc(2025, 1, 12, 10, 0)));  // 星期日
    }

    @Test
    void businessTimeWindow() {
        // 在工作日 09:00-18:00 范围内。
        assertTrue(BusinessTime.isBusinessTime(utc(2025, 1, 6, 9, 0)));
        assertTrue(BusinessTime.isBusinessTime(utc(2025, 1, 6, 17, 59)));
        assertFalse(BusinessTime.isBusinessTime(utc(2025, 1, 6, 18, 0)));
        assertFalse(BusinessTime.isBusinessTime(utc(2025, 1, 6, 8, 59)));
    }

    @Test
    void addBusinessHoursWithinSingleDay() {
        // 周一 09:00 + 4小时 -> 周一 13:00。
        OffsetDateTime r = BusinessTime.addBusinessHours(utc(2025, 1, 6, 9, 0), 4.0);
        assertEquals(utc(2025, 1, 6, 13, 0), r);
    }

    @Test
    void addBusinessHoursSpansDays() {
        // 周一 09:00 + 9小时填满一个工作日，剩余0 -> 停留在18:00？18:00的下一个工作日 -> 周二 09:00。
        OffsetDateTime r = BusinessTime.addBusinessHours(utc(2025, 1, 6, 9, 0), 9.0);
        // 工作日长度为9小时，所以正好9小时落在当天的18:00。
        assertEquals(utc(2025, 1, 6, 18, 0), r);
        // 10小时溢出1小时到周二。
        OffsetDateTime r2 = BusinessTime.addBusinessHours(utc(2025, 1, 6, 9, 0), 10.0);
        assertEquals(utc(2025, 1, 7, 10, 0), r2);
    }

    @Test
    void addBusinessHoursSkipsWeekend() {
        // 周五 17:00 + 2小时 -> 1小时填满周五，1小时滚动到周一 10:00。
        OffsetDateTime r = BusinessTime.addBusinessHours(utc(2025, 1, 10, 17, 0), 2.0);
        assertEquals(utc(2025, 1, 13, 10, 0), r);
    }

    @Test
    void addBusinessHoursFromWeekend() {
        // 周六 12:00 + 3小时 -> 归一化为周一 09:00 然后 +3小时 = 周一 12:00。
        OffsetDateTime r = BusinessTime.addBusinessHours(utc(2025, 1, 11, 12, 0), 3.0);
        assertEquals(utc(2025, 1, 13, 12, 0), r);
    }

    @Test
    void businessHoursBetweenSameDay() {
        double h = BusinessTime.businessHoursBetween(
                utc(2025, 1, 6, 10, 0), utc(2025, 1, 6, 14, 30));
        assertEquals(4.5, h, 1e-9);
    }

    @Test
    void businessHoursBetweenMatchesPythonReference() {
        // 期望值来自直接运行 Python 的 `business_hours_between`。
        // 我们镜像其逐日累加逻辑（在日期边界处会多计
        // 当一个端点位于午夜时）逐字节一致。
        assertEquals(1.0,  BusinessTime.businessHoursBetween(utc(2025, 1, 6, 9, 0),  utc(2025, 1, 6, 10, 0)), 1e-9);
        assertEquals(9.0,  BusinessTime.businessHoursBetween(utc(2025, 1, 6, 9, 0),  utc(2025, 1, 6, 18, 0)), 1e-9);
        assertEquals(7.0,  BusinessTime.businessHoursBetween(utc(2025, 1, 6, 10, 0), utc(2025, 1, 6, 17, 0)), 1e-9);
        assertEquals(16.0, BusinessTime.businessHoursBetween(utc(2025, 1, 6, 10, 0), utc(2025, 1, 7, 11, 0)), 1e-9);
        assertEquals(32.0, BusinessTime.businessHoursBetween(utc(2025, 1, 6, 10, 0), utc(2025, 1, 8, 12, 0)), 1e-9);
    }

    @Test
    void businessHoursBetweenClampsOutOfHours() {
        // 周一 06:00 → 周一 20:00 应等于单个 09:00-18:00 工作日 = 9小时。
        double h = BusinessTime.businessHoursBetween(
                utc(2025, 1, 6, 6, 0), utc(2025, 1, 6, 20, 0));
        assertEquals(9.0, h, 1e-9);
    }

    @Test
    void monthlyPayrollBoundaries() {
        // 从 2025-01-15 到 2025-04-15，我们期望得到二月/三月/四月的第一个工作日。
        List<OffsetDateTime> bs = BusinessTime.iterMonthlyPayrollBoundaries(
                utc(2025, 1, 15, 9, 0), utc(2025, 4, 15, 9, 0));
        assertEquals(3, bs.size());
        // 2025-02-01 是周六 -> 第一个工作日是周一 2025-02-03 09:00。
        assertEquals(utc(2025, 2, 3, 9, 0), bs.get(0));
        // 2025-03-01 是周六 -> 周一 2025-03-03 09:00。
        assertEquals(utc(2025, 3, 3, 9, 0), bs.get(1));
        // 2025-04-01 是周二 -> 2025-04-01 09:00。
        assertEquals(utc(2025, 4, 1, 9, 0), bs.get(2));
    }

    @Test
    void firstBusinessOfMonthSkipsWeekend() {
        // 2025-02-01 周六 -> 第一个工作日是 2025-02-03（周一）。
        OffsetDateTime r = BusinessTime.firstBusinessOfMonth(utc(2025, 2, 15, 13, 0));
        assertEquals(utc(2025, 2, 3, 9, 0), r);
        assertNotNull(r);
    }
}
