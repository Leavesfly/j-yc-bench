package com.collinear.ycbench.agent.runtime;

/** 不可变的运行时设置。镜像 {@code agent/runtime/schemas.py} 中的 {@code RuntimeSettings}。 */
public final class RuntimeSettings {
    public final String model;
    public final String baseUrl;
    public final double temperature;
    public final double topP;
    public final double requestTimeoutSeconds;
    public final int retryMaxAttempts;
    public final double retryBackoffSeconds;
    public final int historyKeepRounds;
    public final String systemPromptOverride;

    public RuntimeSettings(String model, String baseUrl, double temperature, double topP,
                           double requestTimeoutSeconds, int retryMaxAttempts,
                           double retryBackoffSeconds, int historyKeepRounds,
                           String systemPromptOverride) {
        this.model = model;
        this.baseUrl = baseUrl;
        this.temperature = temperature;
        this.topP = topP;
        this.requestTimeoutSeconds = requestTimeoutSeconds;
        this.retryMaxAttempts = retryMaxAttempts;
        this.retryBackoffSeconds = retryBackoffSeconds;
        this.historyKeepRounds = historyKeepRounds;
        this.systemPromptOverride = systemPromptOverride;
    }
}
