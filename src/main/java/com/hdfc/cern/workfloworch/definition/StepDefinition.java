package com.hdfc.cern.workfloworch.definition;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import lombok.Data;

/**
 * One step of a {@link WorkflowDefinition}. Which fields apply depends on {@code type}:
 * <ul>
 *   <li>switch: on, cases, default</li>
 *   <li>activity: activity, params, next</li>
 *   <li>delay: one of duration, fromVar (+ defaultDuration) or untilDay; next</li>
 *   <li>contact: channel, defaultChannel, text, flow (optional), next</li>
 *   <li>await-feedback: timeout, next</li>
 *   <li>count: counter, then either reset (+ next) or limit, whenReached, next</li>
 *   <li>end: nothing</li>
 * </ul>
 */
@Data
public class StepDefinition {

	public static final String SWITCH = "switch";
	public static final String ACTIVITY = "activity";
	public static final String DELAY = "delay";
	public static final String CONTACT = "contact";
	public static final String AWAIT_FEEDBACK = "await-feedback";
	public static final String COUNT = "count";
	public static final String END = "end";

	private String id;
	/** Kept as text so an unknown type is reported by the validator, not by the YAML parser. */
	private String type;

	// switch
	private String on;
	private Map<String, String> cases;
	@JsonProperty("default")
	private String defaultNext;

	// activity
	private String activity;
	private Map<String, String> params;

	// delay
	private String duration;
	/** Read the duration from a run variable (reminder.after, feedback.remindAfter). */
	private String fromVar;
	/** Used when {@code fromVar} has no value. */
	private String defaultDuration;
	/** Wait until Day N of the campaign at the send hour (needs a campaign block). */
	private Integer untilDay;

	// contact: a literal channel (VOICE, WHATSAPP, SMS) or a path such as reminder.channel
	private String channel;
	/** Used when {@code channel} is a path with no value. */
	private String defaultChannel;
	/** Script (VOICE) or message text (WHATSAPP, SMS). For a WhatsApp contact with a flow, the text is the Flow message body. */
	private String text;
	/** Optional flow key (e.g. AMB_REMINDER_RADIO): on a WHATSAPP contact the customer gets that WhatsApp Flow instead of plain text. */
	private String flow;

	// await-feedback: how long to wait for the customer's feedback before it counts as NO_RESPONSE
	private String timeout;

	// count: a named counter kept for the run. reset: true sets it to 0; otherwise it is increased by 1 and,
	// when it has reached `limit`, the workflow goes to `whenReached` instead of `next`.
	private String counter;
	private Boolean reset;
	private Integer limit;
	private String whenReached;

	// activity, delay, contact, await-feedback and count
	private String next;
}
