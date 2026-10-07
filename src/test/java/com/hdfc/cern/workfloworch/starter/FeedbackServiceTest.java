package com.hdfc.cern.workfloworch.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hdfc.cern.workfloworch.activity.OrchActivitiesImpl;
import com.hdfc.cern.workfloworch.api.FeedbackRequest;
import com.hdfc.cern.workfloworch.definition.ShippedYaml;
import com.hdfc.cern.workfloworch.starter.StartRejectedException.Reason;
import com.hdfc.cern.workfloworch.workflow.OrchTaskQueue;
import com.hdfc.cern.workfloworch.workflow.WorkflowInput;
import com.hdfc.cern.workfloworch.workflow.YamlWorkflow;
import com.hdfc.cern.workfloworch.workflow.YamlWorkflowImpl;
import com.hdfc.cern.workfloworch.customer.Customer;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FeedbackServiceTest {

	private TestWorkflowEnvironment env;
	private FeedbackService feedback;

	@BeforeEach
	void setUp() {
		env = TestWorkflowEnvironment.newInstance();
		Worker worker = env.newWorker(OrchTaskQueue.NAME);
		worker.registerWorkflowImplementationTypes(YamlWorkflowImpl.class);
		worker.registerActivitiesImplementations(new OrchActivitiesImpl(new com.hdfc.cern.workfloworch.activity.NoOpWhatsAppGateway()));
		env.start();
		feedback = new FeedbackService(env.getWorkflowClient());

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
	void acceptsAValidFeedbackAndNormalisesIt() {
		FeedbackResult result = feedback.send("OUTREACH_DEFAULT-C1001", new FeedbackRequest("remind_later", " 2d ", "whatsapp"));

		assertThat(result.type()).isEqualTo("REMIND_LATER");
		assertThat(result.remindAfter()).isEqualTo("2d");
		assertThat(result.channel()).isEqualTo("WHATSAPP");
	}

	@Test
	void unknownWorkflowIs404() {
		assertRejected("OUTREACH_DEFAULT-C0000", new FeedbackRequest("POSITIVE", null, null), Reason.WORKFLOW_NOT_FOUND);
	}

	@Test
	void badRequestsAre400() {
		assertRejected("OUTREACH_DEFAULT-C1001", null, Reason.BAD_REQUEST);
		assertRejected("OUTREACH_DEFAULT-C1001", new FeedbackRequest(" ", null, null), Reason.BAD_REQUEST);
		assertRejected("OUTREACH_DEFAULT-C1001", new FeedbackRequest("MAYBE", null, null), Reason.BAD_REQUEST);
		assertRejected("OUTREACH_DEFAULT-C1001", new FeedbackRequest("REMIND_LATER", "soon", null), Reason.BAD_REQUEST);
		assertRejected("OUTREACH_DEFAULT-C1001", new FeedbackRequest("REMIND_LATER", "2d", "PIGEON"), Reason.BAD_REQUEST);
		assertRejected("OUTREACH_DEFAULT-C1001", new FeedbackRequest("POSITIVE", "2d", null), Reason.BAD_REQUEST);
	}

	private void assertRejected(String workflowId, FeedbackRequest request, Reason reason) {
		assertThatThrownBy(() -> feedback.send(workflowId, request))
				.isInstanceOfSatisfying(StartRejectedException.class, e -> assertThat(e.reason()).isEqualTo(reason));
	}
}
