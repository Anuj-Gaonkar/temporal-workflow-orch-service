package com.hdfc.cern.workfloworch.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.hdfc.cern.workfloworch.customer.Customer;
import com.hdfc.cern.workfloworch.definition.DefinitionLoader;
import com.hdfc.cern.workfloworch.definition.WorkflowDefinition;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Runs workflow-definitions/outreach-20-day.yaml through the real workflow in Temporal's time-skipping test environment. */
class Outreach20DayYamlTest {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
	private static final Customer CUSTOMER = new Customer("C1002", "Test Two", "+910000000002", "910000000001", "WHATSAPP");
	private static final String WORKFLOW_ID = "OUTREACH_20_DAY-C1002";

	private TestWorkflowEnvironment env;
	private RecordingActivities activities;
	private WorkflowDefinition definition;
	private long triggeredAt;

	@BeforeEach
	void setUp() {
		DefinitionLoader loader = new DefinitionLoader("workflow-definitions");
		loader.load();
		definition = loader.find("OUTREACH_20_DAY").orElseThrow();

		env = TestWorkflowEnvironment.newInstance();
		activities = new RecordingActivities(env::currentTimeMillis);
		Worker worker = env.newWorker(OrchTaskQueue.NAME);
		worker.registerWorkflowImplementationTypes(YamlWorkflowImpl.class);
		worker.registerActivitiesImplementations(activities);
		env.start();
	}

	@AfterEach
	void tearDown() {
		env.close();
	}

	@Test
	void aSilentCustomerGetsEveryContactOnItsDayAndTheCampaignEndsOnDayTwenty() {
		start();
		finish();

		assertThat(activities.calls).extracting(RecordingActivities.Call::channel)
				.containsExactly("WHATSAPP", "WHATSAPP", "EMAIL", "SMS", "VOICE", "WHATSAPP", "SMS");
		assertThat(activities.calls).extracting(c -> dayNumber(c.atMillis()))
				.containsExactly(1L, 4L, 7L, 10L, 15L, 20L, 20L);
		assertThat(activities.calls).extracting(c -> hourOf(c.atMillis())).containsOnly(10);
		assertThat(activities.calls.get(4).activity()).isEqualTo("executeVoiceCall");
		// the WhatsApp contacts carry the answer Flow
		assertThat(activities.flows).hasSize(3).containsOnly("AMB_REMINDER_RADIO->910000000001");
	}

	@Test
	void positiveAnswerAfterTheDayFourReminderSendsThankYouAndStops() {
		YamlWorkflow wf = start();
		awaitContacts(2);
		wf.feedback(new FeedbackSignal("POSITIVE", null, null));
		finish();

		assertThat(activities.calls).extracting(RecordingActivities.Call::channel).containsExactly("WHATSAPP", "WHATSAPP", "SMS");
	}

	@Test
	void remindLaterAfterTheEmailSendsOneReminderOnTheChosenChannelThenContinuesWithDayTen() {
		YamlWorkflow wf = start();
		awaitContacts(3); // Day 1, Day 4, Day 7 email
		wf.feedback(new FeedbackSignal("REMIND_LATER", "1d", "SMS"));
		awaitContacts(4);
		RecordingActivities.Call reminder = activities.calls.get(3);
		assertThat(reminder.channel()).isEqualTo("SMS");
		assertThat(dayNumber(reminder.atMillis())).isEqualTo(8L);

		finish(); // the reminder goes unanswered, so the ladder carries on
		assertThat(activities.calls).extracting(RecordingActivities.Call::channel)
				.containsExactly("WHATSAPP", "WHATSAPP", "EMAIL", "SMS", "SMS", "VOICE", "WHATSAPP", "SMS");
	}

	@Test
	void inconclusiveAnswerJustContinuesWithTheNextScheduledContact() {
		YamlWorkflow wf = start();
		awaitContacts(1);
		wf.feedback(new FeedbackSignal("INCONCLUSIVE", null, null));
		awaitContacts(2);
		assertThat(dayNumber(activities.calls.get(1).atMillis())).isEqualTo(4L);
		finish();
		assertThat(activities.calls).hasSize(7);
	}

	private YamlWorkflow start() {
		triggeredAt = env.currentTimeMillis();
		YamlWorkflow workflow = env.getWorkflowClient().newWorkflowStub(YamlWorkflow.class,
				WorkflowOptions.newBuilder().setWorkflowId(WORKFLOW_ID).setTaskQueue(OrchTaskQueue.NAME).build());
		WorkflowClient.start(workflow::run, definition, new WorkflowInput(CUSTOMER));
		return workflow;
	}

	/** Moves test time forward in small steps until the n-th contact has happened, then lets the workflow reach its feedback wait. */
	private void awaitContacts(int n) {
		for (int i = 0; i < 40 * 24 * 6 && activities.calls.size() < n; i++) {
			env.sleep(Duration.ofMinutes(10));
		}
		assertThat(activities.calls).hasSizeGreaterThanOrEqualTo(n);
		env.sleep(Duration.ofSeconds(1));
	}

	private void finish() {
		env.getWorkflowClient().newUntypedWorkflowStub(WORKFLOW_ID).getResult(Void.class);
	}

	private long dayNumber(long millis) {
		return java.time.temporal.ChronoUnit.DAYS.between(
				Instant.ofEpochMilli(triggeredAt).atZone(IST).toLocalDate(), Instant.ofEpochMilli(millis).atZone(IST).toLocalDate());
	}

	private static int hourOf(long millis) {
		return Instant.ofEpochMilli(millis).atZone(IST).getHour();
	}
}
