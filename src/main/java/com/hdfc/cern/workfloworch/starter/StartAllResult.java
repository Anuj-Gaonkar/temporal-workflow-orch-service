package com.hdfc.cern.workfloworch.starter;

import java.util.List;

/** Outcome of starting a definition for every customer. Item status: STARTED, ALREADY_RUNNING or FAILED. */
public record StartAllResult(String definition, int total, int started, int alreadyRunning, int failed, List<Item> results) {

	public record Item(String customerId, String initialChannel, String status, String workflowId, String runId, String reason) {
	}
}
