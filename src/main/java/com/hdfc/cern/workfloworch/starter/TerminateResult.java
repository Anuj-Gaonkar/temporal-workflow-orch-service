package com.hdfc.cern.workfloworch.starter;

/** Outcome of terminating one workflow. The workflow and its history stay in Temporal as Terminated (the audit record). */
public record TerminateResult(String workflowId, String runId, String status, String reason) {
}
