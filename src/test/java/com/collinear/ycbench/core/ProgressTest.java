package com.collinear.ycbench.core;

import com.collinear.ycbench.db.model.Domain;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Progress} 的单元测试。通过反射针对包私有的速率组合器，
 * 以避免启动 SQLite 测试环境。
 */
final class ProgressTest {

    private static double computeRate(UUID taskId, Domain domain,
                                      List<Object> assignments,
                                      Map<UUID, Integer> counts,
                                      Map<Object, Double> baseRates) throws Exception {
        // 通过反射查找私有内部方法。
        Class<?> empDomCls = Class.forName("com.collinear.ycbench.core.Progress$EmpDom");
        Map<Object, Double> nativeBaseRates = new HashMap<>();
        for (Map.Entry<Object, Double> e : baseRates.entrySet()) nativeBaseRates.put(e.getKey(), e.getValue());

        Method m = Progress.class.getDeclaredMethod(
                "effectiveTaskDomainRate",
                UUID.class, Domain.class, List.class, Map.class, Map.class);
        m.setAccessible(true);
        return (double) m.invoke(null, taskId, domain, assignments, counts, nativeBaseRates);
    }

    private static Object empDom(UUID emp, Domain d) throws Exception {
        Class<?> empDomCls = Class.forName("com.collinear.ycbench.core.Progress$EmpDom");
        var ctor = empDomCls.getDeclaredConstructor(UUID.class, Domain.class);
        ctor.setAccessible(true);
        return ctor.newInstance(emp, d);
    }

    private static Object assignment(UUID taskId, UUID empId) throws Exception {
        var a = new com.collinear.ycbench.db.model.TaskAssignment();
        a.taskId = taskId;
        a.employeeId = empId;
        return a;
    }

    @Test
    void brooksLawCapsContributors() throws Exception {
        // 6 名员工在同一任务+领域，速率均为 1.0，k=1（单任务）。
        // 预期：前 4 人完全贡献（4.0），其余为开销（0.0）-> 总计 4.0。
        UUID tid = UUID.randomUUID();
        Map<Object, Double> base = new HashMap<>();
        Map<UUID, Integer> counts = new HashMap<>();
        java.util.List<Object> assignments = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            UUID eid = UUID.randomUUID();
            assignments.add(assignment(tid, eid));
            counts.put(eid, 1);
            base.put(empDom(eid, Domain.RESEARCH), 1.0);
        }
        double rate = computeRate(tid, Domain.RESEARCH, assignments, counts, base);
        assertEquals(4.0, rate, 1e-9, "Brooks's Law caps the contributing team at 4.");
    }

    @Test
    void concurrentAssignmentsSplitsRate() throws Exception {
        // 一名员工在 2 个任务上，基础速率 2.0 -> 每个任务看到 1.0。
        UUID tid1 = UUID.randomUUID();
        UUID tid2 = UUID.randomUUID();
        UUID eid = UUID.randomUUID();
        Map<UUID, Integer> counts = new HashMap<>();
        counts.put(eid, 2);
        Map<Object, Double> base = new HashMap<>();
        base.put(empDom(eid, Domain.INFERENCE), 2.0);

        List<Object> aOnT1 = List.of(assignment(tid1, eid));
        double r1 = computeRate(tid1, Domain.INFERENCE, aOnT1, counts, base);
        assertEquals(1.0, r1, 1e-9);
    }

    @Test
    void differentDomainHasIndependentRate() throws Exception {
        // 员工有 TRAINING=3.0 但没有 RESEARCH 技能 -> RESEARCH 速率为 0。
        UUID tid = UUID.randomUUID();
        UUID eid = UUID.randomUUID();
        Map<UUID, Integer> counts = new HashMap<>();
        counts.put(eid, 1);
        Map<Object, Double> base = new HashMap<>();
        base.put(empDom(eid, Domain.TRAINING), 3.0);

        List<Object> as = List.of(assignment(tid, eid));
        double rRes = computeRate(tid, Domain.RESEARCH, as, counts, base);
        double rTrain = computeRate(tid, Domain.TRAINING, as, counts, base);
        assertEquals(0.0, rRes, 1e-9);
        assertEquals(3.0, rTrain, 1e-9);
    }

    @Test
    void sortingDropsLowestContributorsFirst() throws Exception {
        // 5 名贡献者速率分别为 5,4,3,2,1 -> 前 4 名 = 5+4+3+2 = 14。
        UUID tid = UUID.randomUUID();
        Map<UUID, Integer> counts = new HashMap<>();
        Map<Object, Double> base = new HashMap<>();
        java.util.List<Object> assignments = new java.util.ArrayList<>();
        double[] rates = {5, 4, 3, 2, 1};
        for (double r : rates) {
            UUID eid = UUID.randomUUID();
            assignments.add(assignment(tid, eid));
            counts.put(eid, 1);
            base.put(empDom(eid, Domain.RESEARCH), r);
        }
        double rate = computeRate(tid, Domain.RESEARCH, assignments, counts, base);
        assertEquals(14.0, rate, 1e-9);
    }

    @Test
    void efficientTeamSizeConstant() {
        assertEquals(4, Progress.EFFICIENT_TEAM_SIZE);
        assertTrue(Progress.OVERCROWD_PENALTY == 0.0);
    }
}
