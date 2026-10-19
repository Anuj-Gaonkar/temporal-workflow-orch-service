package com.hdfc.cern.workfloworch.activity;

import static com.hdfc.cern.workfloworch.logging.LogEvents.line;

import com.hdfc.cern.workfloworch.customer.CustomerFlags;
import com.hdfc.cern.workfloworch.logging.ConversationLog;
import com.hdfc.cern.workfloworch.logging.LogEvents;
import com.hdfc.cern.workfloworch.workflow.OrchTaskQueue;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInfo;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.ActivityImpl;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@ActivityImpl(taskQueues = OrchTaskQueue.NAME)
@Slf4j
public class OrchActivitiesImpl implements OrchActivities {

	/** Readable "what we sent" lines; also written to logs/conversation.log. */
	private final WhatsAppGateway whatsApp;

	/** Test aid (on in the dev profile): start each WhatsApp message with "[customerId | channel | stepId]". Off by default, so customers never see it. */
	@Value("${orch.whatsapp.tag-messages:false}")
	private boolean tagMessages;

	private final CustomerFlags flags;

	public OrchActivitiesImpl(WhatsAppGateway whatsApp, CustomerFlags flags) {
		this.whatsApp = whatsApp;
		this.flags = flags;
	}

	private static final org.slf4j.Logger conversation = org.slf4j.LoggerFactory.getLogger(ConversationLog.LOGGER);

	@Override
	public void executeVoiceCall(ActivityInput input) {
		run(OrchActivities.EXECUTE_VOICE_CALL, input, () -> {
			conversation.info(ConversationLog.contact(
					"VOICE", input.customer().customerId(), input.customer().phone(), input.stepId(),
					System.currentTimeMillis(), param(input, "script")));
			deliverOnWhatsApp(input, "VOICE", param(input, "script"));
		});
	}

	@Override
	public void sendMessage(ActivityInput input) {
		run(OrchActivities.SEND_MESSAGE, input, () -> {
			conversation.info(ConversationLog.contact(
					param(input, "channel"), input.customer().customerId(), input.customer().phone(), input.stepId(),
					System.currentTimeMillis(), param(input, "text")));
			deliverOnWhatsApp(input, param(input, "channel"), param(input, "text"));
		});
	}

	@Override
	public boolean checkCustomerFlag(ActivityInput input) {
		boolean[] result = new boolean[1];
		run(CHECK_CUSTOMER_FLAG, input, () -> {
			try {
				result[0] = flags.isSet(input.customer().customerId(), param(input, "column"));
			} catch (IllegalArgumentException e) {
				// a column that is not allowed will not become allowed by retrying
				throw ApplicationFailure.newNonRetryableFailure(e.getMessage(), "BadGuardColumn");
			}
		});
		return result[0];
	}

	/** Placeholder prefix for the channels simulated on WhatsApp; null for WHATSAPP itself (sent as is). */
	private static String placeholder(String channel) {
		return switch (channel == null ? "" : channel) {
			case "SMS" -> "SMS sent";
			case "VOICE" -> "Call sent";
			case "EMAIL" -> "Email SENT";
			default -> null;
		};
	}

	/**
	 * WhatsApp is the only channel that really leaves this service: SMS, VOICE and EMAIL contacts are simulated by a
	 * WhatsApp text ("SMS sent: ...", "Call sent: ...", "Email SENT: ..."). A delivery failure (for example Meta
	 * refusing a message outside the 24 hour window) is logged and the campaign carries on: no retry.
	 */
	private void deliverOnWhatsApp(ActivityInput input, String channel, String text) {
		String prefix = placeholder(channel);
		if (prefix == null && !"WHATSAPP".equals(channel)) {
			return;
		}
		String message = prefix == null ? text : (text == null || text.isBlank() ? prefix : prefix + ": " + text);
		if (tagMessages) {
			message = "[" + input.customer().customerId() + " | " + channel + " | " + input.stepId() + "] " + message;
		}
		String waId = input.customer().waId();
		String flowKey = prefix == null ? param(input, "flowKey") : null;
		String workflowId = Activity.getExecutionContext().getInfo().getWorkflowId();
		try {
			if (flowKey != null) {
				whatsApp.sendFlow(waId, flowKey, message, input.customer().customerId(), workflowId);
			} else {
				whatsApp.sendText(waId, message);
			}
			log.info(line(LogEvents.ACT_WHATSAPP_SENT, "workflowId", workflowId, "stepId", input.stepId(), "waId", waId,
					"kind", flowKey != null ? "FLOW:" + flowKey : "TEXT"));
		} catch (RuntimeException e) {
			log.warn(line(LogEvents.ACT_WHATSAPP_FAILED, "workflowId", workflowId, "stepId", input.stepId(), "waId", waId,
					"kind", flowKey != null ? "FLOW:" + flowKey : "TEXT", "error", e.toString()));
		}
	}

	private void run(String name, ActivityInput input, Runnable body) {
		ActivityInfo info = Activity.getExecutionContext().getInfo();
		long startedNanos = System.nanoTime();
		MDC.put("workflowId", info.getWorkflowId());
		MDC.put("customerId", input.customer().customerId());
		try {
			long scheduleToStartMs = System.currentTimeMillis() - info.getScheduledTimestamp();
			log.info(line(LogEvents.ACT_START, "workflowId", info.getWorkflowId(), "stepId", input.stepId(),
					"activity", name, "attempt", info.getAttempt(), "scheduleToStartMs", scheduleToStartMs));
			try {
				body.run();
			} catch (RuntimeException e) {
				log.error(line(LogEvents.ACT_FAILED, "workflowId", info.getWorkflowId(), "stepId", input.stepId(),
						"activity", name, "attempt", info.getAttempt(), "execMs", elapsedMs(startedNanos),
						"error", e.toString()));
				throw e;
			}
			log.info(line(LogEvents.ACT_DONE, "workflowId", info.getWorkflowId(), "stepId", input.stepId(),
					"activity", name, "attempt", info.getAttempt(), "execMs", elapsedMs(startedNanos)));
		} finally {
			MDC.remove("workflowId");
			MDC.remove("customerId");
		}
	}

	private static String param(ActivityInput input, String key) {
		Map<String, String> params = input.params();
		return params == null ? null : params.get(key);
	}

	private static long elapsedMs(long startedNanos) {
		return (System.nanoTime() - startedNanos) / 1_000_000;
	}
}
