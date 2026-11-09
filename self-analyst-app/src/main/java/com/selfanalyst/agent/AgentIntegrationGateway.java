package com.selfanalyst.agent;

import io.agentscope.core.tool.AgentTool;
import java.util.List;
import java.util.Map;

/** Server-owned per-turn integration surface. No model-supplied field grants permission. */
public interface AgentIntegrationGateway {
    record TurnContext(String sessionId, String turnId, boolean managedDesktop, String modelTarget) {}
    List<AgentTool> snapshot(TurnContext turn);
    default void requestModelConsent(TurnContext turn) {
        throw new com.selfanalyst.integrations.policy.MailSessionPolicy.ModelConsentRequiredException();
    }
    void approveNative(TurnContext turn, String toolName, Map<String, Object> arguments);
    default void endTurn(TurnContext turn) {}
}
