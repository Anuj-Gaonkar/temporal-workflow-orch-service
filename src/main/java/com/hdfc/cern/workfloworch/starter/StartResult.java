package com.hdfc.cern.workfloworch.starter;

public record StartResult(String workflowId, String runId, String customerId, String initialChannel) {
}
