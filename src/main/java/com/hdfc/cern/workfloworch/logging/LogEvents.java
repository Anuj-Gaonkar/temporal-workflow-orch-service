package com.hdfc.cern.workfloworch.logging;

/**
 * Event names and the one line builder every timing log uses: {@code event=NAME key=value ...}.
 * The load-test scripts parse these lines, so names and field names are documented in
 * docs/phase-1/01-coding.md and must not change casually. Pure, so workflow-safe.
 */
public final class LogEvents {

	public static final String API_START_RECEIVED = "API_START_RECEIVED";
	public static final String API_START_DONE = "API_START_DONE";
	public static final String API_START_REJECTED = "API_START_REJECTED";
	public static final String API_START_ALL_RECEIVED = "API_START_ALL_RECEIVED";
	public static final String API_START_ALL_DONE = "API_START_ALL_DONE";
	public static final String WF_STARTED = "WF_STARTED";
	public static final String WF_STEP_ENTER = "WF_STEP_ENTER";
	public static final String WF_STEP_EXIT = "WF_STEP_EXIT";
	public static final String WF_COMPLETED = "WF_COMPLETED";
	public static final String WF_FAILED = "WF_FAILED";
	public static final String API_FEEDBACK_RECEIVED = "API_FEEDBACK_RECEIVED";
	public static final String API_FEEDBACK_DONE = "API_FEEDBACK_DONE";
	public static final String API_FEEDBACK_REJECTED = "API_FEEDBACK_REJECTED";
	public static final String API_TERMINATE_DONE = "API_TERMINATE_DONE";
	public static final String API_TERMINATE_ALL_DONE = "API_TERMINATE_ALL_DONE";
	public static final String WF_FEEDBACK = "WF_FEEDBACK";
	public static final String WF_FEEDBACK_DROPPED = "WF_FEEDBACK_DROPPED";
	public static final String WF_COUNTER = "WF_COUNTER";
	public static final String WF_WINDOW_EXPIRED = "WF_WINDOW_EXPIRED";
	public static final String WF_CHANNEL_CHOSEN = "WF_CHANNEL_CHOSEN";
	public static final String WF_GUARD_CHECKED = "WF_GUARD_CHECKED";
	public static final String WF_CONTACT_SKIPPED = "WF_CONTACT_SKIPPED";
	public static final String ACT_START = "ACT_START";
	public static final String ACT_DONE = "ACT_DONE";
	public static final String ACT_FAILED = "ACT_FAILED";
	public static final String ACT_WHATSAPP_SENT = "ACT_WHATSAPP_SENT";
	public static final String ACT_WHATSAPP_FAILED = "ACT_WHATSAPP_FAILED";
	public static final String DEFINITIONS_LOADED = "DEFINITIONS_LOADED";
	public static final String DEFINITIONS_RELOADED = "DEFINITIONS_RELOADED";
	public static final String DEFINITIONS_RELOAD_FAILED = "DEFINITIONS_RELOAD_FAILED";

	private LogEvents() {
	}

	/** {@code fields} is alternating key, value. A null value is skipped; a value with spaces is quoted. */
	public static String line(String event, Object... fields) {
		if (fields.length % 2 != 0) {
			throw new IllegalArgumentException("fields must be key/value pairs");
		}
		StringBuilder sb = new StringBuilder("event=").append(event);
		for (int i = 0; i < fields.length; i += 2) {
			Object value = fields[i + 1];
			if (value == null) {
				continue;
			}
			sb.append(' ').append(fields[i]).append('=').append(format(String.valueOf(value)));
		}
		return sb.toString();
	}

	private static String format(String value) {
		if (value.isEmpty() || value.chars().anyMatch(c -> Character.isWhitespace(c) || c == '=' || c == '"')) {
			return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
		}
		return value;
	}
}
