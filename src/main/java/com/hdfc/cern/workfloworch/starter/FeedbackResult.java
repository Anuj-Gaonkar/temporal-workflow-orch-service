package com.hdfc.cern.workfloworch.starter;

public record FeedbackResult(String workflowId, String type, String remindAfter, String channel) {
}
