package com.hdfc.cern.workfloworch.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hdfc.cern.workfloworch.activity.OrchActivitiesImpl;
import com.hdfc.cern.workfloworch.customer.Customer;
import com.hdfc.cern.workfloworch.definition.DefinitionLoader;
import com.hdfc.cern.workfloworch.definition.ShippedYaml;
import com.hdfc.cern.workfloworch.starter.StartRejectedException.Reason;
import com.hdfc.cern.workfloworch.workflow.OrchTaskQueue;
import com.hdfc.cern.workfloworch.workflow.WorkflowInput;
import com.hdfc.cern.workfloworch.workflow.YamlWorkflow;
import com.hdfc.cern.workfloworch.workflow.YamlWorkflowImpl;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TerminationServiceTest {

	private TestWorkflowEnvironment env;
	private TerminationService termination;

	@BeforeEach
	void setUp() {
		env = TestWorkflowEnvironment.newInstance();
		Worker worker = env.newWorker(OrchTaskQueue.NAME);
		worker.registerWorkflowImplementationTypes(YamlWorkflowImpl.class);
		worker.registerActivitiesImplementations(new OrchActivitiesImpl(new com.hdfc.cern.workfloworch.activity.NoOpWhatsAppGateway()));
		env.start();
		DefinitionLoader loader = new DefinitionLoader("workflow-definitions");
		loader.load();
		termination = new TerminationService(env.getWorkflowClient(), loader);

		YamlWorkflow workflow = env.getWorkflowClient().newWorkflowStub(YamlWorkflow.class,
				WorkflowOptions.newBuilder().setWorkflowId("OUTREACH_DEFAULT-C1001").setTaskQueue(OrchTaskQueue.NAME).build());
		WorkflowClient.start(workflow::run, ShippedYaml.definition(),
				new WorkflowInput(new Customer("C1001", "Test One", "+910000000001", "910000000001", "VOICE")));
	}

	@AfterEach
	void tearDown() {
		env.close();
	}

	@Test
	void terminateStopsTheWorkflowAndKeepsItsRecordWithTheReason() {
		TerminateResult result = termination.terminate("OUTREACH_DEFAULT-C1001", " demo reset ");

		assertThat(result.status()).isEqualTo("TERMINATED");
		assertThat(result.reason()).isEqualTo("demo reset");
		var info = env.getWorkflowClient().getWorkflowServiceStubs().blockingStub().describeWorkflowExecution(
				io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest.newBuilder()
						.setNamespace(env.getNamespace())
						.setExecution(io.temporal.api.common.v1.WorkflowExecution.newBuilder().setWorkflowId("OUTREACH_DEFAULT-C1001"))
						.build()).getWorkflowExecutionInfo();
		assertThat(info.getStatus()).isEqualTo(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TERMINATED);
	}

	@Test
	void blankReasonGetsTheDefault() {
		assertThat(termination.terminate("OUTREACH_DEFAULT-C1001", " ").reason()).isEqualTo(TerminationService.DEFAULT_REASON);
	}

	@Test
	void unknownOrAlreadyTerminatedWorkflowIs404() {
		assertNotRunning("OUTREACH_DEFAULT-C0000");
		termination.terminate("OUTREACH_DEFAULT-C1001", null);
		assertNotRunning("OUTREACH_DEFAULT-C1001");
	}

	@Test
	void terminateAllWithAnUnknownDefinitionIs404() {
		assertThatThrownBy(() -> termination.terminateAll("NOPE", null))
				.isInstanceOfSatisfying(StartRejectedException.class, e -> assertThat(e.reason()).isEqualTo(Reason.DEFINITION_NOT_FOUND));
	}

	private void assertNotRunning(String workflowId) {
		assertThatThrownBy(() -> termination.terminate(workflowId, null))
				.isInstanceOfSatisfying(StartRejectedException.class, e -> assertThat(e.reason()).isEqualTo(Reason.WORKFLOW_NOT_FOUND));
	}
}
