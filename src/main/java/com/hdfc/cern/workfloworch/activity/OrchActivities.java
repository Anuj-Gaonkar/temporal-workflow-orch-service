package com.hdfc.cern.workfloworch.activity;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.Set;

/** The fixed activity registry. Phase 1 activities only log; they call nothing external. */
@ActivityInterface
public interface OrchActivities {

	/** Names a YAML {@code activity:} may use. Add a name here when adding a method below. */
	String EXECUTE_VOICE_CALL = "executeVoiceCall";
	String SEND_MESSAGE = "sendMessage";
	Set<String> NAMES = Set.of(EXECUTE_VOICE_CALL, SEND_MESSAGE);
	/** Used by the workflow itself for {@code contactGuard}; not something a YAML {@code activity:} step can call, so not in {@link #NAMES}. */
	String CHECK_CUSTOMER_FLAG = "checkCustomerFlag";

	/** Logs the voice call (params: script). */
	@ActivityMethod
	void executeVoiceCall(ActivityInput input);

	/** Logs the message (params: channel, text). */
	@ActivityMethod
	void sendMessage(ActivityInput input);

	/** Reads a boolean column of the customer's row from the database (params: column). */
	@ActivityMethod
	boolean checkCustomerFlag(ActivityInput input);
}
