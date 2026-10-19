package com.hdfc.cern.workfloworch.definition;

import lombok.Data;

/** Settings of one channel under the optional {@code channels:} block, for example {@code CHAT_BOT: {flip: WHATSAPP}}. */
@Data
public class ChannelSpec {

	/** The channel FLIP switches to from this one; an override in {@code channelRules.flip} wins over it. */
	private String flip;
}
