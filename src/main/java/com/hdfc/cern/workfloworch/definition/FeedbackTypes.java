package com.hdfc.cern.workfloworch.definition;

import java.util.Set;

/** The four kinds of customer feedback a workflow routes on (value of {@code feedback.type}). */
public final class FeedbackTypes {

	/** Customer will complete the process. */
	public static final String POSITIVE = "POSITIVE";
	/** Customer asked to be reminded later (optionally with a duration and a channel). */
	public static final String REMIND_LATER = "REMIND_LATER";
	/** Customer did not answer. Also what a feedback wait that times out becomes. */
	public static final String NO_RESPONSE = "NO_RESPONSE";
	/** Customer answered but nothing concrete came out of it. */
	public static final String INCONCLUSIVE = "INCONCLUSIVE";
	public static final Set<String> ALL = Set.of(POSITIVE, REMIND_LATER, NO_RESPONSE, INCONCLUSIVE);

	private FeedbackTypes() {
	}
}
