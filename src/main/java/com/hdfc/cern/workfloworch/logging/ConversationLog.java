package com.hdfc.cern.workfloworch.logging;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * The human-readable "conversation" lines: what we sent to a customer, what the customer replied
 * and how a campaign ended. They are written through the logger named {@link #LOGGER}, which goes to
 * the main log and also to {@code logs/conversation.log}. Pure string building (the caller passes the
 * time), so the workflow may use it.
 */
public final class ConversationLog {

	/** Name of the logger that logback routes to logs/conversation.log (see logback-spring.xml). */
	public static final String LOGGER = "conversation";

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'IST'")
			.withZone(ZoneId.of("Asia/Kolkata"));

	private ConversationLog() {
	}

	/** {@code Sending WHATSAPP to C1001 (+91..) [step] at 2026-10-01 12:10:59 IST >> text}; a VOICE contact says Calling. */
	public static String contact(String channel, String customerId, String phone, String stepId, long atMillis, String text) {
		String verb = "VOICE".equals(channel) ? "Calling" : "Sending";
		return verb + " " + channel + " to " + customerId + " (" + phone + ") [" + stepId + "] at " + time(atMillis) + " >> " + text;
	}

	/** {@code Customer C1001 replied at ... >> REMIND_LATER (in 2d on WHATSAPP)}. */
	public static String feedback(String customerId, long atMillis, String type, String remindAfter, String channel, boolean timedOut) {
		StringBuilder detail = new StringBuilder(type);
		if (timedOut) {
			detail.append(" (no feedback within the wait)");
		} else if (remindAfter != null || channel != null) {
			detail.append(" (");
			if (remindAfter != null) {
				detail.append("in ").append(remindAfter);
			}
			if (channel != null) {
				detail.append(remindAfter != null ? " " : "").append("on ").append(channel);
			}
			detail.append(')');
		}
		return "Customer " + customerId + " replied at " + time(atMillis) + " >> " + detail;
	}

	/** {@code Campaign for C1001 ended at ... >> COMPLETED after 16m, 3 contacts (VOICE, WHATSAPP, SMS)}. */
	public static String ended(String customerId, long atMillis, String outcome, long elapsedMillis, String channels, int contacts) {
		return "Campaign for " + customerId + " ended at " + time(atMillis) + " >> " + outcome + " after " + elapsed(elapsedMillis)
				+ ", " + contacts + (contacts == 1 ? " contact" : " contacts") + (contacts > 0 ? " (" + channels + ")" : "");
	}

	static String time(long millis) {
		return TIME.format(Instant.ofEpochMilli(millis));
	}

	static String elapsed(long millis) {
		Duration d = Duration.ofMillis(millis);
		if (d.toDays() > 0) {
			return d.toDays() + "d " + d.toHoursPart() + "h";
		}
		if (d.toHours() > 0) {
			return d.toHours() + "h " + d.toMinutesPart() + "m";
		}
		if (d.toMinutes() > 0) {
			return d.toMinutes() + "m";
		}
		return d.toSeconds() + "s";
	}
}
