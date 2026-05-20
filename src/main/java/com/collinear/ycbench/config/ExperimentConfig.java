package com.collinear.ycbench.config;

/** 顶层实验配置。镜像 {@code config/schema.py} 中的 {@code ExperimentConfig}。*/
public final class ExperimentConfig {
    public String name = "default";
    public String description = "";
    public AgentConfig agent = new AgentConfig();
    public LoopConfig loop = new LoopConfig();
    public SimConfig sim = new SimConfig();
    public WorldConfig world;
}
