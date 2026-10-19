package com.hdfc.cern.workfloworch.definition;

import java.util.Map;
import java.util.Set;

/** The values a {@code channelRules.onFeedback} entry may take (besides a literal channel). */
public final class ChannelActions {

	/** Follow {@code channelDefaults} for the next scheduled contact; without an entry it behaves as SAME. */
	public static final String DEFAULT = "DEFAULT";
	/** Keep the channel used for the last contact. */
	public static final String SAME = "SAME";
	/** Switch to the other channel (see {@link #BUILT_IN_FLIP} and {@code channelRules.flip}). */
	public static final String FLIP = "FLIP";
	/** Use the channel the customer asked for in the feedback, else the channel used last. */
	public static final String REQUESTED = "REQUESTED";
	public static final Set<String> ALL = Set.of(DEFAULT, SAME, FLIP, REQUESTED);

	public static final Map<String, String> BUILT_IN_FLIP = Map.of(
			Channels.VOICE, Channels.WHATSAPP,
			Channels.WHATSAPP, Channels.VOICE,
			Channels.SMS, Channels.WHATSAPP,
			Channels.EMAIL, Channels.WHATSAPP);

	private ChannelActions() {
	}
}
