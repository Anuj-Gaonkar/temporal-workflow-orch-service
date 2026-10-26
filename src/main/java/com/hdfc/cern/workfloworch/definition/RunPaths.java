package com.hdfc.cern.workfloworch.definition;

import com.hdfc.cern.workfloworch.model.Customer;
import java.util.Map;
import java.util.Set;

/**
 * The only paths a YAML may read (switch {@code on}, delay {@code fromVar}, contact {@code channel}).
 * No expressions. {@code customer.*} come from the customer snapshot; the rest are run variables kept
 * by the workflow: the latest feedback, the reminder the customer asked for, and the channel of the
 * last contact. A variable that was never set resolves to null.
 */
public final class RunPaths {

	public static final String FEEDBACK_TYPE = "feedback.type";
	public static final String FEEDBACK_REMIND_AFTER = "feedback.remindAfter";
	public static final String FEEDBACK_CHANNEL = "feedback.channel";
	public static final String REMINDER_AFTER = "reminder.after";
	public static final String REMINDER_CHANNEL = "reminder.channel";
	public static final String LAST_CHANNEL = "last.channel";

	public static final Set<String> CUSTOMER = Set.of(
			"customer.customerId", "customer.name", "customer.phone", "customer.waId", "customer.initialChannel");
	public static final Set<String> VARIABLES = Set.of(
			FEEDBACK_TYPE, FEEDBACK_REMIND_AFTER, FEEDBACK_CHANNEL, REMINDER_AFTER, REMINDER_CHANNEL, LAST_CHANNEL);
	public static final Set<String> SUPPORTED = union(CUSTOMER, VARIABLES);

	/** Paths whose value is a channel name (what a contact {@code channel} may point at). */
	public static final Set<String> CHANNEL_PATHS = Set.of(
			"customer.initialChannel", FEEDBACK_CHANNEL, REMINDER_CHANNEL, LAST_CHANNEL);
	/** Paths whose value is a duration text (what a delay {@code fromVar} may point at). */
	public static final Set<String> DURATION_PATHS = Set.of(REMINDER_AFTER, FEEDBACK_REMIND_AFTER);

	private RunPaths() {
	}

	public static String resolve(String path, Customer customer, Map<String, String> variables) {
		return switch (path) {
			case "customer.customerId" -> customer.customerId();
			case "customer.name" -> customer.name();
			case "customer.phone" -> customer.phone();
			case "customer.waId" -> customer.waId();
			case "customer.initialChannel" -> customer.initialChannel();
			default -> {
				if (!VARIABLES.contains(path)) {
					throw new IllegalArgumentException("Unsupported path '" + path + "'");
				}
				yield variables.get(path);
			}
		};
	}

	private static Set<String> union(Set<String> a, Set<String> b) {
		java.util.HashSet<String> all = new java.util.HashSet<>(a);
		all.addAll(b);
		return Set.copyOf(all);
	}
}
