package com.hdfc.cern.workfloworch.definition;

import java.util.Map;

/**
 * Decides the channel of a {@code channel: AUTO} contact from {@code channelDefaults} and
 * {@code channelRules}. Pure (no clock, IO or state), so it is safe inside the workflow.
 */
public final class ChannelResolver {

	private ChannelResolver() {
	}

	/** What to do with the channel after this feedback type: its rule, or SAME when there is none. */
	public static String actionFor(WorkflowDefinition def, String feedbackType) {
		ChannelRules rules = def.getChannelRules();
		if (rules == null || rules.getOnFeedback() == null) {
			return ChannelActions.SAME;
		}
		String action = rules.getOnFeedback().get(feedbackType);
		return action == null ? ChannelActions.SAME : action;
	}

	/**
	 * @param action      what {@link #actionFor} returned for the latest feedback; null before any feedback (the first contact)
	 * @param lastChannel channel of the last contact, null before the first one
	 * @param requested   channel in the latest feedback, null if none
	 * @return a channel; never null while the customer has an initial channel
	 */
	public static String resolve(WorkflowDefinition def, String stepId, String initialChannel, String lastChannel,
			String action, String requested) {
		String chosen = switch (action == null ? ChannelActions.DEFAULT : action) {
			case ChannelActions.DEFAULT -> defaultFor(def, stepId, initialChannel);
			case ChannelActions.SAME -> lastChannel;
			case ChannelActions.FLIP -> lastChannel == null ? null : flip(def, lastChannel);
			case ChannelActions.REQUESTED -> requested != null ? requested : lastChannel;
			default -> def.knownChannels().contains(action) ? action : null; // a literal channel
		};
		if (chosen != null) {
			return chosen;
		}
		return lastChannel != null ? lastChannel : initialChannel;
	}

	private static String defaultFor(WorkflowDefinition def, String stepId, String initialChannel) {
		if (def.getChannelDefaults() == null || initialChannel == null) {
			return null;
		}
		Map<String, String> forChannel = def.getChannelDefaults().get(initialChannel);
		if (forChannel == null) {
			forChannel = def.getChannelDefaults().get(Channels.FALLBACK_BLOCK);
		}
		String channel = forChannel == null ? null : forChannel.get(stepId);
		return Channels.DB.equals(channel) ? initialChannel : channel;
	}

	private static String flip(WorkflowDefinition def, String channel) {
		ChannelRules rules = def.getChannelRules();
		String custom = rules == null || rules.getFlip() == null ? null : rules.getFlip().get(channel);
		return custom != null ? custom : def.flipOf(channel);
	}
}
