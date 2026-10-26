package com.hdfc.cern.workfloworch.controller;

import com.hdfc.cern.workfloworch.definition.DefinitionLoader;
import com.hdfc.cern.workfloworch.definition.StepDefinition;
import com.hdfc.cern.workfloworch.dto.FeedbackRequest;
import com.hdfc.cern.workfloworch.dto.FeedbackResult;
import com.hdfc.cern.workfloworch.dto.StartAllRequest;
import com.hdfc.cern.workfloworch.dto.StartAllResult;
import com.hdfc.cern.workfloworch.dto.StartRequest;
import com.hdfc.cern.workfloworch.dto.StartResult;
import com.hdfc.cern.workfloworch.dto.TerminateAllResult;
import com.hdfc.cern.workfloworch.dto.TerminateRequest;
import com.hdfc.cern.workfloworch.dto.TerminateResult;
import com.hdfc.cern.workfloworch.exception.NotFoundException;
import com.hdfc.cern.workfloworch.model.Customer;
import com.hdfc.cern.workfloworch.service.CustomerService;
import com.hdfc.cern.workfloworch.service.FeedbackService;
import com.hdfc.cern.workfloworch.service.TerminationService;
import com.hdfc.cern.workfloworch.service.WorkflowStarterService;
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.client.WorkflowClient;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrchController {

	public record DefinitionSummary(String name, String description, List<String> stepIds) {
	}

	public record WorkflowStatus(String workflowId, String runId, String status, Instant startTime, Instant closeTime) {
	}

	private final WorkflowStarterService starter;
	private final FeedbackService feedback;
	private final TerminationService termination;
	private final DefinitionLoader definitions;
	private final CustomerService customers;
	private final WorkflowClient workflowClient;

	public OrchController(WorkflowStarterService starter, FeedbackService feedback, TerminationService termination, DefinitionLoader definitions,
			CustomerService customers,
			WorkflowClient workflowClient) {
		this.starter = starter;
		this.feedback = feedback;
		this.termination = termination;
		this.definitions = definitions;
		this.customers = customers;
		this.workflowClient = workflowClient;
	}

	@GetMapping("/workflow-definitions")
	public List<DefinitionSummary> listDefinitions() {
		return definitions.all().stream()
				.map(d -> new DefinitionSummary(d.getName(), d.getDescription(),
						d.getSteps().stream().map(StepDefinition::getId).toList()))
				.toList();
	}

	/** Rescans the definitions folder now. Valid files go live, broken ones are listed under errors and keep their old version. */
	@PostMapping("/workflow-definitions/reload")
	public DefinitionLoader.ReloadResult reloadDefinitions() {
		return definitions.reload();
	}

	/** Customer feedback for a running workflow (signal). 202 once delivered; 400 / 404 as for start. */
	@PostMapping("/workflows/{workflowId}/feedback")
	public ResponseEntity<FeedbackResult> feedback(@PathVariable String workflowId, @RequestBody FeedbackRequest request) {
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(feedback.send(workflowId, request));
	}

	@PostMapping("/workflows/start")
	public ResponseEntity<StartResult> start(@RequestBody StartRequest request) {
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(starter.start(request.customerId(), request.definition()));
	}

	@PostMapping("/workflows/start-all")
	public ResponseEntity<StartAllResult> startAll(@RequestBody StartAllRequest request) {
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(starter.startAll(request.definition()));
	}

	/**
	 * Stops one running workflow. It is not deleted: it stays in Temporal as Terminated with the reason (audit record).
	 * Optional body {"reason": "..."}; 404 when the workflow is unknown or already finished.
	 */
	@PostMapping("/workflows/{workflowId}/terminate")
	public TerminateResult terminate(@PathVariable String workflowId, @RequestBody(required = false) TerminateRequest request) {
		return termination.terminate(workflowId, request == null ? null : request.reason());
	}

	/** Stops every running workflow, or only those of one definition ({"definition": "OUTREACH_20_DAY"}). History is kept. */
	@PostMapping("/workflows/terminate-all")
	public TerminateAllResult terminateAll(@RequestBody(required = false) TerminateRequest request) {
		return termination.terminateAll(request == null ? null : request.definition(), request == null ? null : request.reason());
	}

	@GetMapping("/workflows/{workflowId}")
	public WorkflowStatus status(@PathVariable String workflowId) {
		WorkflowExecutionInfo info = workflowClient.getWorkflowServiceStubs().blockingStub()
				.describeWorkflowExecution(DescribeWorkflowExecutionRequest.newBuilder()
						.setNamespace(workflowClient.getOptions().getNamespace())
						.setExecution(io.temporal.api.common.v1.WorkflowExecution.newBuilder().setWorkflowId(workflowId))
						.build())
				.getWorkflowExecutionInfo();
		String status = info.getStatus().name().replace("WORKFLOW_EXECUTION_STATUS_", "");
		Instant close = info.hasCloseTime() ? instant(info.getCloseTime()) : null;
		return new WorkflowStatus(info.getExecution().getWorkflowId(), info.getExecution().getRunId(), status,
				instant(info.getStartTime()), close);
	}

	@GetMapping("/customers/{customerId}")
	public Customer customer(@PathVariable String customerId) {
		return customers.findById(customerId).orElseThrow(() ->
				new NotFoundException("CUSTOMER_NOT_FOUND", "No customer with id '" + customerId + "'"));
	}

	private static Instant instant(com.google.protobuf.Timestamp t) {
		return Instant.ofEpochSecond(t.getSeconds(), t.getNanos());
	}
}
