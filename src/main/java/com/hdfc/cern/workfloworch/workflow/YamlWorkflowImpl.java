package com.hdfc.cern.workfloworch.workflow;

import static com.hdfc.cern.workfloworch.logging.LogEvents.line;

import com.hdfc.cern.workfloworch.activity.ActivityInput;
import com.hdfc.cern.workfloworch.activity.OrchActivities;
import com.hdfc.cern.workfloworch.customer.Customer;
import com.hdfc.cern.workfloworch.definition.CampaignClock;
import com.hdfc.cern.workfloworch.definition.ChannelResolver;
import com.hdfc.cern.workfloworch.definition.Channels;
import com.hdfc.cern.workfloworch.definition.ContactGuard;
import com.hdfc.cern.workfloworch.definition.DurationParser;
import com.hdfc.cern.workfloworch.definition.FeedbackTypes;
import com.hdfc.cern.workfloworch.definition.RunPaths;
import com.hdfc.cern.workfloworch.definition.StepDefinition;
import com.hdfc.cern.workfloworch.definition.WorkflowDefinition;
import com.hdfc.cern.workfloworch.logging.ConversationLog;
import com.hdfc.cern.workfloworch.logging.LogEvents;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;

/**
 * Interprets a {@link WorkflowDefinition} with a step pointer: start at the first step, each step
 * decides the next step id, stop at an {@code end} step (the workflow completes normally).
 *
 * <p>Campaign window: when the definition has a {@code campaign} block, Day 0 is the day the run
 * started and nothing may be sent or waited for beyond the end of Day {@code maxDays}. A delay or
 * contact that would cross that line ends the workflow normally (WF_WINDOW_EXPIRED) instead.
 *
 * <p>Deterministic: no clock, random, IO or database. Time comes from {@link Workflow#currentTimeMillis()},
 * log lines are written only when not replaying.
 */
@WorkflowImpl(taskQueues = OrchTaskQueue.NAME)
public class YamlWorkflowImpl implements YamlWorkflow {

	private static final int MAX_TRANSITIONS = 500;
	/** What {@link #execute} returns when the campaign window closed; not a step id. */
	private static final String WINDOW_EXPIRED = "\u0000window-expired";

	private final Logger log = Workflow.getLogger(YamlWorkflowImpl.class);

	private final OrchActivities activities = Workflow.newActivityStub(
			OrchActivities.class,
			ActivityOptions.newBuilder()
					.setStartToCloseTimeout(Duration.ofSeconds(30))
					.setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
					.build());

	/**
	 * The contactGuard read. A database that is briefly down must not end campaigns or let a reminder through
	 * unchecked, so this keeps retrying (with growing pauses, at most a minute apart) until the read works.
	 */
	private final OrchActivities guardActivities = Workflow.newActivityStub(
			OrchActivities.class,
			ActivityOptions.newBuilder()
					.setStartToCloseTimeout(Duration.ofSeconds(30))
					.setRetryOptions(RetryOptions.newBuilder()
							.setInitialInterval(Duration.ofSeconds(2))
							.setMaximumInterval(Duration.ofMinutes(1))
							.setDoNotRetry("BadGuardColumn")
							.build())
					.build());

	/** Feedback signals that arrived and have not been consumed by an await-feedback step yet. */
	private final ArrayDeque<FeedbackSignal> pending = new ArrayDeque<>();
	/** Run variables the YAML can read (see {@link RunPaths}); only ever holds non-null values. */
	private final Map<String, String> variables = new HashMap<>();
	private CampaignClock clock; // null when the definition has no campaign window
	private final Logger conversation = Workflow.getLogger(ConversationLog.LOGGER);
	/** Channels of the contacts made so far, for the one-line campaign summary. */
	private final java.util.List<String> contactChannels = new java.util.ArrayList<>();
	private boolean windowExpired;
	private WorkflowDefinition definition;
	/** What the channel rules said to do after the latest feedback (see {@link ChannelResolver}); null before any feedback. */
	private String channelAction;
	/** Named counters of the run (see the count step); a counter that was never touched is 0. */
	private final Map<String, Integer> counters = new HashMap<>();

	@Override
	public void feedback(FeedbackSignal signal) {
		String type = signal == null || signal.type() == null ? "" : signal.type().trim().toUpperCase();
		if (!FeedbackTypes.ALL.contains(type)) {
			info(line(LogEvents.WF_FEEDBACK_DROPPED, "workflowId", Workflow.getInfo().getWorkflowId(),
					"reason", "UNKNOWN_TYPE", "type", signal == null ? null : signal.type()));
			return;
		}
		String remindAfter = signal.remindAfter() == null || signal.remindAfter().isBlank() ? null : signal.remindAfter().trim();
		if (remindAfter != null) {
			try {
				DurationParser.parse(remindAfter);
			} catch (IllegalArgumentException e) {
				info(line(LogEvents.WF_FEEDBACK_DROPPED, "workflowId", Workflow.getInfo().getWorkflowId(),
						"reason", "BAD_REMIND_AFTER_IGNORED", "remindAfter", remindAfter));
				remindAfter = null;
			}
		}
		String channel = signal.channel() == null || signal.channel().isBlank() ? null : signal.channel().trim().toUpperCase();
		if (channel != null && !(definition == null ? Channels.ALL : definition.knownChannels()).contains(channel)) {
			info(line(LogEvents.WF_FEEDBACK_DROPPED, "workflowId", Workflow.getInfo().getWorkflowId(),
					"reason", "BAD_CHANNEL_IGNORED", "channel", channel));
			channel = null;
		}
		pending.add(new FeedbackSignal(type, remindAfter, channel));
	}

	@Override
	public void run(WorkflowDefinition definition, WorkflowInput input) {
		this.definition = definition;
		String workflowId = Workflow.getInfo().getWorkflowId();
		Customer customer = input.customer();
		long startedAt = Workflow.currentTimeMillis();
		long startLagMs = startedAt - Workflow.getInfo().getRunStartedTimestampMillis();
		if (definition.getCampaign() != null) {
			clock = new CampaignClock(definition.getCampaign(), Workflow.getInfo().getRunStartedTimestampMillis());
		}
		info(line(LogEvents.WF_STARTED, "workflowId", workflowId, "customerId", customer.customerId(),
				"channel", customer.initialChannel(), "definition", definition.getName(), "startLagMs", startLagMs));

		try {
			interpret(definition, customer, workflowId);
		} catch (RuntimeException e) {
			if (!Workflow.isReplaying()) {
				log.error(line(LogEvents.WF_FAILED, "workflowId", workflowId, "customerId", customer.customerId(),
						"totalMs", Workflow.currentTimeMillis() - startedAt, "error", rootMessage(e)));
			}
			throw e;
		}
		long endedAt = Workflow.currentTimeMillis();
		if (!Workflow.isReplaying()) {
			conversation.info(ConversationLog.ended(customer.customerId(), endedAt, windowExpired ? "WINDOW_EXPIRED" : "COMPLETED",
					endedAt - startedAt, String.join(", ", contactChannels), contactChannels.size()));
		}
		info(line(LogEvents.WF_COMPLETED, "workflowId", workflowId, "customerId", customer.customerId(),
				"totalMs", endedAt - startedAt));
	}

	private void interpret(WorkflowDefinition definition, Customer customer, String workflowId) {
		Map<String, StepDefinition> steps = new HashMap<>();
		definition.getSteps().forEach(step -> steps.put(step.getId(), step));

		String current = definition.getStart();
		int transitions = 0;
		while (true) {
			if (++transitions > MAX_TRANSITIONS) {
				throw fail("More than " + MAX_TRANSITIONS + " transitions - check the definition for a loop", "TooManyTransitions");
			}
			StepDefinition step = steps.get(current);
			if (step == null) {
				throw fail("Unknown step id '" + current + "'", "UnknownStep");
			}

			long enteredAt = Workflow.currentTimeMillis();
			info(line(LogEvents.WF_STEP_ENTER, "workflowId", workflowId, "stepId", step.getId(), "stepType", step.getType()));
			String next = execute(step, customer);
			if (WINDOW_EXPIRED.equals(next)) {
				windowExpired = true;
				info(line(LogEvents.WF_WINDOW_EXPIRED, "workflowId", workflowId, "stepId", step.getId(),
						"deadline", java.time.Instant.ofEpochMilli(clock.deadline())));
				return;
			}
			info(line(LogEvents.WF_STEP_EXIT, "workflowId", workflowId, "stepId", step.getId(), "stepType", step.getType(),
					"stepMs", Workflow.currentTimeMillis() - enteredAt));
			if (next == null) {
				return;
			}
			current = next;
		}
	}

	/** Runs one step and returns the next step id, or null when the workflow has ended. */
	private String execute(StepDefinition step, Customer customer) {
		switch (step.getType()) {
			case StepDefinition.SWITCH -> {
				String value = RunPaths.resolve(step.getOn(), customer, variables);
				String target = step.getCases() == null ? null : step.getCases().get(value);
				if (target == null) {
					target = step.getDefaultNext();
				}
				if (target == null) {
					throw fail("No case for " + step.getOn() + "=" + value + " in step '" + step.getId() + "'", "NoCase");
				}
				return target;
			}
			case StepDefinition.ACTIVITY -> {
				if (windowClosed()) {
					return WINDOW_EXPIRED;
				}
				ActivityInput in = new ActivityInput(step.getId(), customer, step.getParams());
				switch (step.getActivity()) {
					case OrchActivities.EXECUTE_VOICE_CALL -> activities.executeVoiceCall(in);
					case OrchActivities.SEND_MESSAGE -> activities.sendMessage(in);
					default -> throw fail("Unknown activity '" + step.getActivity() + "' in step '" + step.getId() + "'", "UnknownActivity");
				}
				return step.getNext();
			}
			case StepDefinition.DELAY -> {
				return delay(step);
			}
			case StepDefinition.CONTACT -> {
				return contact(step, customer);
			}
			case StepDefinition.AWAIT_FEEDBACK -> {
				return awaitFeedback(step, customer);
			}
			case StepDefinition.COUNT -> {
				return count(step);
			}
			case StepDefinition.END -> {
				return null;
			}
			default -> throw fail("Unknown step type '" + step.getType() + "' in step '" + step.getId() + "'", "UnknownStepType");
		}
	}

	/** reset: counter back to 0, go to next. Otherwise counter + 1; at the limit go to whenReached, else next. */
	private String count(StepDefinition step) {
		String workflowId = Workflow.getInfo().getWorkflowId();
		if (Boolean.TRUE.equals(step.getReset())) {
			counters.put(step.getCounter(), 0);
			info(line(LogEvents.WF_COUNTER, "workflowId", workflowId, "stepId", step.getId(), "counter", step.getCounter(), "value", 0));
			return step.getNext();
		}
		int value = counters.merge(step.getCounter(), 1, Integer::sum);
		boolean reached = value >= step.getLimit();
		info(line(LogEvents.WF_COUNTER, "workflowId", workflowId, "stepId", step.getId(), "counter", step.getCounter(),
				"value", value, "limit", step.getLimit(), "reached", reached));
		return reached ? step.getWhenReached() : step.getNext();
	}

	private boolean windowClosed() {
		return clock != null && clock.expired(Workflow.currentTimeMillis());
	}

	/** Sleeps until the target time, or ends the run when that time is outside the campaign window. */
	private String delay(StepDefinition step) {
		long now = Workflow.currentTimeMillis();
		long target;
		if (step.getUntilDay() != null) {
			target = Math.max(now, clock.dayStart(step.getUntilDay()));
		} else {
			String text = step.getDuration();
			if (step.getFromVar() != null) {
				String fromVar = variables.get(step.getFromVar());
				text = fromVar != null ? fromVar : step.getDefaultDuration();
			}
			Duration wait = DurationParser.parse(text);
			target = clock == null ? now + wait.toMillis() : clock.target(now, wait);
		}
		if (clock != null && clock.expired(target)) {
			return WINDOW_EXPIRED;
		}
		if (target > now) {
			Workflow.sleep(Duration.ofMillis(target - now));
		}
		return step.getNext();
	}

	/** Contacts the customer on the step's channel: VOICE runs the call activity, WHATSAPP and SMS send a message. */
	private String contact(StepDefinition step, Customer customer) {
		if (windowClosed()) {
			return WINDOW_EXPIRED;
		}
		ContactGuard guard = definition.getContactGuard();
		if (guard != null && guard.appliesTo(step.getId())) {
			String workflowId = Workflow.getInfo().getWorkflowId();
			boolean met = guardActivities.checkCustomerFlag(new ActivityInput(step.getId(), customer, Map.of("column", guard.getColumn())));
			info(line(LogEvents.WF_GUARD_CHECKED, "workflowId", workflowId, "stepId", step.getId(), "column", guard.getColumn(), "met", met));
			if (met) {
				info(line(LogEvents.WF_CONTACT_SKIPPED, "workflowId", workflowId, "stepId", step.getId(),
						"reason", "GUARD_MET:" + guard.getColumn(), "goTo", guard.getOnMet()));
				return guard.getOnMet();
			}
		}
		String channel;
		if (Channels.AUTO.equals(step.getChannel())) {
			channel = ChannelResolver.resolve(definition, step.getId(), customer.initialChannel(),
					variables.get(RunPaths.LAST_CHANNEL), channelAction, variables.get(RunPaths.FEEDBACK_CHANNEL));
			info(line(LogEvents.WF_CHANNEL_CHOSEN, "workflowId", Workflow.getInfo().getWorkflowId(), "stepId", step.getId(),
					"channel", channel, "action", channelAction == null ? "FIRST" : channelAction));
		} else {
			channel = channelOf(step.getChannel(), customer);
			if (channel == null) {
				channel = channelOf(step.getDefaultChannel(), customer);
			}
		}
		if (channel == null || !definition.knownChannels().contains(channel)) {
			throw fail("No usable channel for step '" + step.getId() + "' (" + step.getChannel() + "=" + channel + ")", "NoChannel");
		}
		// Feedback that arrived before this contact belongs to an older contact: drop it.
		if (!pending.isEmpty()) {
			info(line(LogEvents.WF_FEEDBACK_DROPPED, "workflowId", Workflow.getInfo().getWorkflowId(),
					"reason", "STALE_BEFORE_CONTACT", "count", pending.size()));
			pending.clear();
		}
		Map<String, String> params = new HashMap<>();
		params.put("channel", channel);
		params.put("text", step.getText());
		if (step.getFlow() != null && Channels.WHATSAPP.equals(channel)) {
			params.put("flowKey", step.getFlow());
		}
		if (Channels.VOICE.equals(channel)) {
			params.put("script", step.getText());
			activities.executeVoiceCall(new ActivityInput(step.getId(), customer, params));
		} else {
			activities.sendMessage(new ActivityInput(step.getId(), customer, params));
		}
		variables.put(RunPaths.LAST_CHANNEL, channel);
		contactChannels.add(channel);
		return step.getNext();
	}

	private String channelOf(String literalOrPath, Customer customer) {
		if (literalOrPath == null) {
			return null;
		}
		return definition.knownChannels().contains(literalOrPath) ? literalOrPath : RunPaths.resolve(literalOrPath, customer, variables);
	}

	/**
	 * Waits for the customer's feedback. No feedback within the timeout counts as NO_RESPONSE. The wait
	 * never goes past the end of the campaign window; if the window closes first the run ends.
	 */
	private String awaitFeedback(StepDefinition step, Customer customer) {
		Duration timeout = DurationParser.parse(step.getTimeout());
		boolean cutByWindow = false;
		if (clock != null) {
			timeout = clock.scale(timeout);
			long left = clock.deadline() - Workflow.currentTimeMillis();
			if (left <= 0) {
				return WINDOW_EXPIRED;
			}
			if (left <= timeout.toMillis()) {
				timeout = Duration.ofMillis(left);
				cutByWindow = true;
			}
		}
		FeedbackSignal received;
		boolean timedOut = false;
		if (Workflow.await(timeout, () -> !pending.isEmpty())) {
			received = pending.poll();
		} else if (cutByWindow) {
			return WINDOW_EXPIRED;
		} else {
			received = new FeedbackSignal(FeedbackTypes.NO_RESPONSE, null, null);
			timedOut = true;
		}
		accept(received, step, customer, timedOut);
		return step.getNext();
	}

	/** Makes the feedback readable by the YAML; a reminder request is remembered for later repeats. */
	private void accept(FeedbackSignal feedback, StepDefinition step, Customer customer, boolean timedOut) {
		put(RunPaths.FEEDBACK_TYPE, feedback.type());
		put(RunPaths.FEEDBACK_REMIND_AFTER, feedback.remindAfter());
		put(RunPaths.FEEDBACK_CHANNEL, feedback.channel());
		channelAction = ChannelResolver.actionFor(definition, feedback.type());
		if (FeedbackTypes.REMIND_LATER.equals(feedback.type())) {
			if (feedback.remindAfter() != null) {
				put(RunPaths.REMINDER_AFTER, feedback.remindAfter());
			}
			if (feedback.channel() != null) {
				put(RunPaths.REMINDER_CHANNEL, feedback.channel());
			}
		}
		if (!Workflow.isReplaying()) {
			conversation.info(ConversationLog.feedback(customer.customerId(), Workflow.currentTimeMillis(), feedback.type(),
					feedback.remindAfter(), feedback.channel(), timedOut));
		}
		info(line(LogEvents.WF_FEEDBACK, "workflowId", Workflow.getInfo().getWorkflowId(), "stepId", step.getId(),
				"type", feedback.type(), "remindAfter", feedback.remindAfter(), "channel", feedback.channel()));
	}

	private void put(String key, String value) {
		if (value == null) {
			variables.remove(key);
		} else {
			variables.put(key, value);
		}
	}

	private void info(String message) {
		if (!Workflow.isReplaying()) {
			log.info(message);
		}
	}

	private static ApplicationFailure fail(String message, String type) {
		return ApplicationFailure.newNonRetryableFailure(message, type);
	}

	private static String rootMessage(Throwable t) {
		if (t instanceof ApplicationFailure failure) {
			return failure.getOriginalMessage();
		}
		return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
	}
}
