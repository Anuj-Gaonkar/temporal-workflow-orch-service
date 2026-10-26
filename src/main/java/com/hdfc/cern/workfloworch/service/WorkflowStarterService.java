package com.hdfc.cern.workfloworch.service;

import static com.hdfc.cern.workfloworch.logging.LogEvents.line;

import com.hdfc.cern.workfloworch.definition.DefinitionLoader;
import com.hdfc.cern.workfloworch.definition.WorkflowDefinition;
import com.hdfc.cern.workfloworch.dto.StartAllResult;
import com.hdfc.cern.workfloworch.dto.StartResult;
import com.hdfc.cern.workfloworch.exception.StartRejectedException;
import com.hdfc.cern.workfloworch.exception.StartRejectedException.Reason;
import com.hdfc.cern.workfloworch.logging.LogEvents;
import com.hdfc.cern.workfloworch.model.Customer;
import com.hdfc.cern.workfloworch.repository.CustomerRepository;
import com.hdfc.cern.workfloworch.temporal.workflow.OrchTaskQueue;
import com.hdfc.cern.workfloworch.temporal.workflow.WorkflowInput;
import com.hdfc.cern.workfloworch.temporal.workflow.YamlWorkflow;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * Starts one workflow for one customer: check the definition, read the customer snapshot, start.
 * Workflow id is {@code <definition>-<customerId>}, so a second start while it runs is refused.
 * Also writes the API_START_* timing log lines.
 */
@Service
@Slf4j
public class WorkflowStarterService {

	private final WorkflowClient workflowClient;
	private final DefinitionLoader definitions;
	private final CustomerRepository customers;

	public WorkflowStarterService(WorkflowClient workflowClient, DefinitionLoader definitions, CustomerRepository customers) {
		this.workflowClient = workflowClient;
		this.definitions = definitions;
		this.customers = customers;
	}

	public StartResult start(String customerId, String definitionName) {
		long startedNanos = System.nanoTime();
		String workflowId = null;
		try {
			if (customerId != null) {
				MDC.put("customerId", customerId);
			}
			log.info(line(LogEvents.API_START_RECEIVED, "customerId", customerId, "definition", definitionName));

			if (isBlank(customerId) || isBlank(definitionName)) {
				throw new StartRejectedException(Reason.BAD_REQUEST, "customerId and definition are required");
			}
			WorkflowDefinition definition = definitions.find(definitionName).orElseThrow(() ->
					new StartRejectedException(Reason.DEFINITION_NOT_FOUND, "No workflow definition named '" + definitionName + "'"));

			long lookupStart = System.nanoTime();
			Customer customer = customers.findById(customerId).orElseThrow(() ->
					new StartRejectedException(Reason.CUSTOMER_NOT_FOUND, "No customer with id '" + customerId + "'"));
			long lookupMs = elapsedMs(lookupStart);
			checkChannel(definition, customer);

			workflowId = definition.getName() + "-" + customerId;
			MDC.put("workflowId", workflowId);
			long startStart = System.nanoTime();
			WorkflowExecution execution = startWorkflow(workflowId, definition, customer);
			long startMs = elapsedMs(startStart);

			log.info(line(LogEvents.API_START_DONE, "workflowId", workflowId, "runId", execution.getRunId(),
					"customerId", customerId, "channel", customer.initialChannel(),
					"lookupMs", lookupMs, "startMs", startMs, "totalMs", elapsedMs(startedNanos)));
			return new StartResult(workflowId, execution.getRunId(), customerId, customer.initialChannel());
		} catch (StartRejectedException e) {
			log.info(line(LogEvents.API_START_REJECTED, "customerId", customerId, "definition", definitionName,
					"reason", e.reason(), "totalMs", elapsedMs(startedNanos)));
			throw e;
		} finally {
			MDC.remove("customerId");
			MDC.remove("workflowId");
		}
	}

	/**
	 * Starts the definition for every customer in the table, one after another. A customer that is
	 * already running or that fails to start is reported and skipped; it never stops the others.
	 */
	public StartAllResult startAll(String definitionName) {
		long startedNanos = System.nanoTime();
		try {
			log.info(line(LogEvents.API_START_ALL_RECEIVED, "definition", definitionName));
			if (isBlank(definitionName)) {
				throw new StartRejectedException(Reason.BAD_REQUEST, "definition is required");
			}
			WorkflowDefinition definition = definitions.find(definitionName).orElseThrow(() ->
					new StartRejectedException(Reason.DEFINITION_NOT_FOUND, "No workflow definition named '" + definitionName + "'"));

			long lookupStart = System.nanoTime();
			List<Customer> all = customers.findAll();
			long lookupMs = elapsedMs(lookupStart);

			List<StartAllResult.Item> items = new ArrayList<>();
			int started = 0;
			int alreadyRunning = 0;
			int failed = 0;
			for (Customer customer : all) {
				String workflowId = definition.getName() + "-" + customer.customerId();
				long oneStart = System.nanoTime();
				MDC.put("customerId", customer.customerId());
				MDC.put("workflowId", workflowId);
				try {
					checkChannel(definition, customer);
					WorkflowExecution execution = startWorkflow(workflowId, definition, customer);
					long startMs = elapsedMs(oneStart);
					log.info(line(LogEvents.API_START_DONE, "workflowId", workflowId, "runId", execution.getRunId(),
							"customerId", customer.customerId(), "channel", customer.initialChannel(),
							"startMs", startMs, "totalMs", startMs));
					items.add(new StartAllResult.Item(customer.customerId(), customer.initialChannel(), "STARTED",
							workflowId, execution.getRunId(), null));
					started++;
				} catch (StartRejectedException e) {
					log.info(line(LogEvents.API_START_REJECTED, "customerId", customer.customerId(),
							"definition", definitionName, "reason", e.reason(), "totalMs", elapsedMs(oneStart)));
					items.add(new StartAllResult.Item(customer.customerId(), customer.initialChannel(), e.reason().name(),
							workflowId, null, e.getMessage()));
					if (e.reason() == Reason.ALREADY_RUNNING) {
						alreadyRunning++;
					} else {
						failed++;
					}
				} catch (RuntimeException e) {
					log.error(line(LogEvents.API_START_REJECTED, "customerId", customer.customerId(),
							"definition", definitionName, "reason", "START_FAILED", "totalMs", elapsedMs(oneStart),
							"error", e.toString()));
					items.add(new StartAllResult.Item(customer.customerId(), customer.initialChannel(), "FAILED",
							workflowId, null, e.getMessage()));
					failed++;
				} finally {
					MDC.remove("customerId");
					MDC.remove("workflowId");
				}
			}

			log.info(line(LogEvents.API_START_ALL_DONE, "definition", definitionName, "total", all.size(),
					"started", started, "alreadyRunning", alreadyRunning, "failed", failed,
					"lookupMs", lookupMs, "totalMs", elapsedMs(startedNanos)));
			return new StartAllResult(definitionName, all.size(), started, alreadyRunning, failed, items);
		} catch (StartRejectedException e) {
			log.info(line(LogEvents.API_START_REJECTED, "definition", definitionName, "reason", e.reason(),
					"totalMs", elapsedMs(startedNanos)));
			throw e;
		}
	}

	/** The customer's database channel must be one the definition declares, so a mismatch fails here and not on Day 1. */
	private static void checkChannel(WorkflowDefinition definition, Customer customer) {
		String channel = customer.initialChannel();
		if (channel != null && !definition.knownChannels().contains(channel)) {
			throw new StartRejectedException(Reason.CHANNEL_NOT_SUPPORTED, "Customer '" + customer.customerId() + "' has channel '"
					+ channel + "', which definition '" + definition.getName() + "' does not declare (it declares "
					+ new java.util.TreeSet<>(definition.knownChannels()) + ")");
		}
	}

	private WorkflowExecution startWorkflow(String workflowId, WorkflowDefinition definition, Customer customer) {
		YamlWorkflow workflow = workflowClient.newWorkflowStub(YamlWorkflow.class,
				WorkflowOptions.newBuilder().setWorkflowId(workflowId).setTaskQueue(OrchTaskQueue.NAME).build());
		try {
			return WorkflowClient.start(workflow::run, definition, new WorkflowInput(customer));
		} catch (WorkflowExecutionAlreadyStarted e) {
			throw new StartRejectedException(Reason.ALREADY_RUNNING, "A workflow '" + workflowId + "' is already running");
		}
	}

	private static boolean isBlank(String s) {
		return s == null || s.isBlank();
	}

	private static long elapsedMs(long startedNanos) {
		return (System.nanoTime() - startedNanos) / 1_000_000;
	}
}
