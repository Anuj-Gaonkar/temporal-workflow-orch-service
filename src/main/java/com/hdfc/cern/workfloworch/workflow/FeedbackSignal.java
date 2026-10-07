package com.hdfc.cern.workfloworch.workflow;

/**
 * Customer feedback sent to a running workflow. {@code type} is one of POSITIVE, REMIND_LATER,
 * NO_RESPONSE, INCONCLUSIVE; {@code remindAfter} (e.g. 2d) and {@code channel} (WHATSAPP, VOICE, SMS)
 * are optional and only used for REMIND_LATER. All text so that a malformed signal can never break
 * deserialization inside the workflow; the workflow checks the values and ignores bad ones.
 */
public record FeedbackSignal(String type, String remindAfter, String channel) {
}
