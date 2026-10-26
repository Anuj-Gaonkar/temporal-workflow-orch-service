package com.hdfc.cern.workfloworch.dto;

public record StartResult(String workflowId, String runId, String customerId, String initialChannel) {
}
