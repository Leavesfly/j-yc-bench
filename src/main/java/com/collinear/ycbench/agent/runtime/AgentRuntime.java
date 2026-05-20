package com.collinear.ycbench.agent.runtime;

/** 运行时 SPI。镜像 {@code agent/runtime/base.py}。 */
public interface AgentRuntime {
    TurnResult runTurn(TurnRequest request) throws Exception;

    void clearSession(String sessionId);

    default void saveSessionMessages(String sessionId, java.nio.file.Path path) {
    }

    default int restoreSessionMessages(String sessionId, java.nio.file.Path path) {
        return 0;
    }
}
