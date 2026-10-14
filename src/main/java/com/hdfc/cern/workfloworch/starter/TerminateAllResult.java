package com.hdfc.cern.workfloworch.starter;

import java.util.List;

/** Outcome of terminating every running workflow (of one definition, or of all definitions). Item status: TERMINATED or FAILED. */
public record TerminateAllResult(String definition, int total, int terminated, int failed, List<Item> results) {

	public record Item(String workflowId, String runId, String status, String reason) {
	}
}
