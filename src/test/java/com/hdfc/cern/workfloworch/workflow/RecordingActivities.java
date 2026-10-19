package com.hdfc.cern.workfloworch.workflow;

import com.hdfc.cern.workfloworch.activity.ActivityInput;
import com.hdfc.cern.workfloworch.activity.OrchActivities;
import com.hdfc.cern.workfloworch.activity.OrchActivitiesImpl;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;

/** Test-only wrapper: runs the real log-only activities and records which ran and at what workflow time. */
class RecordingActivities implements OrchActivities {

	record Call(String activity, long atMillis, String channel) {
	}

	private final OrchActivities real = new OrchActivitiesImpl(new com.hdfc.cern.workfloworch.activity.WhatsAppGateway() {
		@Override
		public void sendFlow(String waId, String flowKey, String bodyText, String customerId, String workflowId) {
			flows.add(flowKey + "->" + waId);
		}

		@Override
		public void sendText(String waId, String message) {
			texts.add(waId + ":" + message);
		}
	}, (customerId, column) -> false);
	private final LongSupplier clock;
	final List<Call> calls = new CopyOnWriteArrayList<>();
	final List<String> flows = new CopyOnWriteArrayList<>();
	final List<String> texts = new CopyOnWriteArrayList<>();
	/** What the guard's database read returns; a test flips it to simulate the customer meeting the condition. */
	volatile boolean flagSet;
	/** Step ids whose contact was checked against the flag, in order. */
	final List<String> guardChecks = new CopyOnWriteArrayList<>();

	RecordingActivities(LongSupplier clock) {
		this.clock = clock;
	}

	@Override
	public void executeVoiceCall(ActivityInput input) {
		calls.add(new Call(EXECUTE_VOICE_CALL, clock.getAsLong(), "VOICE"));
		real.executeVoiceCall(input);
	}

	@Override
	public void sendMessage(ActivityInput input) {
		calls.add(new Call(SEND_MESSAGE, clock.getAsLong(), input.params().get("channel")));
		real.sendMessage(input);
	}

	@Override
	public boolean checkCustomerFlag(ActivityInput input) {
		guardChecks.add(input.stepId());
		return flagSet;
	}
}
