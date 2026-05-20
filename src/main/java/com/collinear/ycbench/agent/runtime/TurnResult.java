package com.collinear.ycbench.agent.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 一轮运行的结果。镜像 {@code RuntimeTurnResult}。 */
public final class TurnResult {
    public final String finalOutput;
    /** [{command:..., result:...}, ...] 用于转录提取。 */
    public final List<Map<String, Object>> toolCalls;
    public final boolean checkpointAdvanced;
    public final Map<String, Object> resumePayload;
    public final double turnCostUsd;
    public final int promptTokens;
    public final int completionTokens;

    public TurnResult(String finalOutput, List<Map<String, Object>> toolCalls,
                      boolean checkpointAdvanced, Map<String, Object> resumePayload,
                      double turnCostUsd, int promptTokens, int completionTokens) {
        this.finalOutput = finalOutput;
        this.toolCalls = toolCalls == null ? new ArrayList<>() : toolCalls;
        this.checkpointAdvanced = checkpointAdvanced;
        this.resumePayload = resumePayload;
        this.turnCostUsd = turnCostUsd;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
    }
}
