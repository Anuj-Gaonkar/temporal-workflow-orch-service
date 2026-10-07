package com.hdfc.cern.workfloworch.api;

/**
 * Body of {@code POST /workflows/{workflowId}/feedback}. {@code type}: POSITIVE, REMIND_LATER,
 * NO_RESPONSE or INCONCLUSIVE. {@code remindAfter} (e.g. 2d) and {@code channel} (WHATSAPP, VOICE, SMS)
 * are optional and only allowed with REMIND_LATER.
 */
public record FeedbackRequest(String type, String remindAfter, String channel) {
}
