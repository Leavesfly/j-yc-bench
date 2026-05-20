package com.collinear.ycbench.config;

/** 镜像 {@code config/schema.py} 中的 {@code AgentConfig}。*/
public final class AgentConfig {
    public String model = "openrouter/z-ai/glm-5";
    public double temperature = 0.0;
    public double topP = 1.0;
    public double requestTimeoutSeconds = 300.0;
    public int retryMaxAttempts = 3;
    public double retryBackoffSeconds = 1.0;
    public int historyKeepRounds = 20;
    public String systemPrompt;            // null = 使用 SystemPrompt.DEFAULT 的默认值
}
