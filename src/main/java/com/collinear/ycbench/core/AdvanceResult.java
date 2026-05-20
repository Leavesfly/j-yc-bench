package com.collinear.ycbench.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** {@link Engine#advanceTime} 的结果。镜像 {@code core/engine.py:AdvanceResult}。 */
public final class AdvanceResult {
    public String oldSimTime;
    public String newSimTime;
    public int eventsProcessed;
    public int payrollsApplied;
    public long balanceDelta;
    public boolean bankrupt;
    public boolean horizonReached;
    public List<Map<String, Object>> wakeEvents = new ArrayList<>();
}
