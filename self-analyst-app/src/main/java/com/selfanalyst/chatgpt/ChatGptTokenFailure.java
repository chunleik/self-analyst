package com.selfanalyst.chatgpt;

/** Maps only local, fixed session failure codes; never forwards exception text or causes. */
final class ChatGptTokenFailure {
    enum Kind { AUTHENTICATION, ACCESS_DENIED, TIMEOUT, TRANSPORT, CANCELLED }

    private ChatGptTokenFailure() {}

    static Kind classify(RuntimeException failure) {
        String code = failure.getMessage();
        if (code == null) return Kind.TRANSPORT;
        return switch (code) {
            case "chatgpt_sign_in_required", "chatgpt_unknown_account" -> Kind.AUTHENTICATION;
            case "chatgpt_plan_permission_required" -> Kind.ACCESS_DENIED;
            case "chatgpt_request_timeout" -> Kind.TIMEOUT;
            case "chatgpt_request_interrupted", "chatgpt_service_closed" -> Kind.CANCELLED;
            // Refresh/network failures do not prove that a retained credential is invalid.
            // Keep them retryable, including the server's earliest_refresh_at delay.
            case "chatgpt_network_error", "chatgpt_refresh_failed", "chatgpt_refresh_not_yet_allowed",
                    "chatgpt_discovery_failed" -> Kind.TRANSPORT;
            default -> Kind.TRANSPORT;
        };
    }
}
