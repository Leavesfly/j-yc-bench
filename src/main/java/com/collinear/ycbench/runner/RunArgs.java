package com.collinear.ycbench.runner;

/** {@code yc-bench run} 子命令的解析参数。 */
public final class RunArgs {
    public String model;
    public long seed;
    public Integer horizonYears;     // null = 从配置中获取
    public String companyName = "BenchCo";
    public String startDate = "2025-01-01";
    public String configName = "default";
    public boolean noLive = false;
    public int maxEpisodes = 1;
}
