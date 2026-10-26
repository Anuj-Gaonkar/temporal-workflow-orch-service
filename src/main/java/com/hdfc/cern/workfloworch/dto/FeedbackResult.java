package com.hdfc.cern.workfloworch.dto;

public record FeedbackResult(String workflowId, String type, String remindAfter, String channel) {
}
