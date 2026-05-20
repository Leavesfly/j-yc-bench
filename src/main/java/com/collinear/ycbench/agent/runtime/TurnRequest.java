package com.collinear.ycbench.agent.runtime;

/** {@link AgentRuntime#runTurn} 的输入。镜像 {@code RuntimeTurnRequest}。 */
public final class TurnRequest {
    public final String sessionId;
    public final String userInput;
    public final String scratchpad;

    public TurnRequest(String sessionId, String userInput, String scratchpad) {
        this.sessionId = sessionId;
        this.userInput = userInput;
        this.scratchpad = scratchpad;
    }
}
