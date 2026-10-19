package com.hdfc.cern.workfloworch.definition;

import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Data;

/**
 * Java shape of one workflow-definition YAML file. Parsed at startup and passed as-is into the
 * workflow as input, so the workflow never touches YAML or the filesystem.
 */
@Data
public class WorkflowDefinition {

	private String name;
	private String description;
	/** Optional campaign window; without it nothing limits how long the workflow may run. */
	private Campaign campaign;
	/**
	 * Optional. The channels this definition knows, each with its settings: {@code CHAT_BOT: {flip: WHATSAPP}}.
	 * Left out, the built-in channels apply (see {@link Channels#ALL}). A customer whose database channel is not
	 * listed cannot start this definition.
	 */
	private Map<String, ChannelSpec> channels;
	/**
	 * Optional. Default channel of each scheduled contact, by the customer's database channel:
	 * {@code VOICE: {contact-day1: DB, contact-day4: VOICE}}. Used by {@code channel: AUTO} contacts.
	 */
	private Map<String, Map<String, String>> channelDefaults;
	/** Optional. How feedback changes the channel of the next AUTO contact. */
	private ChannelRules channelRules;
	/** Optional. A DB flag checked before each contact; when it is true the contact is replaced by a thank-you step. */
	private ContactGuard contactGuard;
	private String start;
	private List<StepDefinition> steps;

	/** The channel codes this definition accepts: the {@code channels} block, else the built-ins. */
	public Set<String> knownChannels() {
		return channels == null ? Channels.ALL : channels.keySet();
	}

	/** The channel FLIP switches to from {@code channel} (null if none): its {@code channels} entry, else the built-in pair. */
	public String flipOf(String channel) {
		if (channels == null) {
			return ChannelActions.BUILT_IN_FLIP.get(channel);
		}
		ChannelSpec spec = channels.get(channel);
		return spec == null ? null : spec.getFlip();
	}
}
