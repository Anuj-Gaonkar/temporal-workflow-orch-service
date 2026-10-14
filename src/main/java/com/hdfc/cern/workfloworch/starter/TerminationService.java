package com.hdfc.cern.workfloworch.starter;

import static com.hdfc.cern.workfloworch.logging.LogEvents.line;

import com.hdfc.cern.workfloworch.definition.DefinitionLoader;
import com.hdfc.cern.workfloworch.definition.WorkflowDefinition;
import com.hdfc.cern.workfloworch.logging.LogEvents;
import com.hdfc.cern.workfloworch.starter.StartRejectedException.Reason;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionMetadata;
import io.temporal.client.WorkflowNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * Stops running workflows. A terminated workflow is not deleted: it stays in Temporal with status Terminated and the
 * reason given here, so the run keeps its history for audit. Nothing more is sent to the customer once it is terminated.
 */
@Service
@Slf4j
public class TerminationService {

	static final String DEFAULT_REASON = "terminated via API";

	private final WorkflowClient workflowClient;
	private final DefinitionLoader definitions;

	public TerminationService(WorkflowClient workflowClient, DefinitionLoader definitions) {
		this.workflowClient = workflowClient;
		this.definitions = definitions;
	}

	/** Terminates one running workflow; 404 (WORKFLOW_NOT_FOUND) when it does not exist or has already finished. */
	public TerminateResult terminate(String workflowId, String reason) {
		String why = reasonOrDefault(reason);
		try {
			MDC.put("workflowId", workflowId);
			var stub = workflowClient.newUntypedWorkflowStub(workflowId);
			String runId;
			try {
				stub.terminate(why);
				runId = stub.getExecution().getRunId();
			} catch (WorkflowNotFoundException e) {
				throw notRunning(workflowId);
			} catch (io.grpc.StatusRuntimeException e) {
				if (e.getStatus().getCode() == io.grpc.Status.Code.NOT_FOUND) {
					throw notRunning(workflowId);
				}
				throw e;
			}
			log.info(line(LogEvents.API_TERMINATE_DONE, "workflowId", workflowId, "runId", runId, "reason", why));
			return new TerminateResult(workflowId, runId, "TERMINATED", why);
		} finally {
			MDC.remove("workflowId");
		}
	}

	/**
	 * Terminates every running workflow whose id starts with a known definition name plus "-" (the ids the starter
	 * creates). With {@code definitionName} only that definition's workflows are stopped.
	 */
	public TerminateAllResult terminateAll(String definitionName, String reason) {
		String why = reasonOrDefault(reason);
		List<String> prefixes;
		if (definitionName == null || definitionName.isBlank()) {
			prefixes = definitions.all().stream().map(d -> d.getName() + "-").toList();
		} else {
			WorkflowDefinition definition = definitions.find(definitionName.trim()).orElseThrow(() ->
					new StartRejectedException(Reason.DEFINITION_NOT_FOUND, "No workflow definition named '" + definitionName + "'"));
			prefixes = List.of(definition.getName() + "-");
		}

		List<String> runningIds = new ArrayList<>();
		try (Stream<WorkflowExecutionMetadata> running = workflowClient.listExecutions("ExecutionStatus=\"Running\"")) {
			running.map(m -> m.getExecution().getWorkflowId())
					.filter(id -> prefixes.stream().anyMatch(id::startsWith))
					.forEach(runningIds::add);
		}

		List<TerminateAllResult.Item> items = new ArrayList<>();
		int terminated = 0;
		for (String workflowId : runningIds) {
			try {
				TerminateResult result = terminate(workflowId, why);
				items.add(new TerminateAllResult.Item(workflowId, result.runId(), "TERMINATED", why));
				terminated++;
			} catch (RuntimeException e) {
				items.add(new TerminateAllResult.Item(workflowId, null, "FAILED", e.getMessage()));
			}
		}
		int failed = items.size() - terminated;
		log.info(line(LogEvents.API_TERMINATE_ALL_DONE, "definition", definitionName, "total", items.size(),
				"terminated", terminated, "failed", failed, "reason", why));
		return new TerminateAllResult(definitionName, items.size(), terminated, failed, items);
	}

	private static StartRejectedException notRunning(String workflowId) {
		return new StartRejectedException(Reason.WORKFLOW_NOT_FOUND,
				"No running workflow '" + workflowId + "' (unknown or already finished)");
	}

	private static String reasonOrDefault(String reason) {
		return reason == null || reason.isBlank() ? DEFAULT_REASON : reason.trim();
	}
}
