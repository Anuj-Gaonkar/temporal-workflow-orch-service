package com.hdfc.cern.workfloworch.definition;

import java.util.Map;
import lombok.Data;

/**
 * Optional {@code channelRules} block: how the customer's feedback decides the channel of the next
 * {@code channel: AUTO} contact. A feedback type with no rule behaves as {@link ChannelActions#SAME}.
 */
@Data
public class ChannelRules {

	/** Feedback type to action: DEFAULT, SAME, FLIP, REQUESTED or a literal channel (see {@link ChannelActions}). */
	private Map<String, String> onFeedback;
	/** Optional overrides for FLIP (last channel to the channel to switch to); anything not listed uses {@link ChannelActions#BUILT_IN_FLIP}. */
	private Map<String, String> flip;
}
