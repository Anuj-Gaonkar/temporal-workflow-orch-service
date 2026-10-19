package com.hdfc.cern.workfloworch.definition;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ChannelResolverTest {

	private static WorkflowDefinition definition() {
		WorkflowDefinition def = new WorkflowDefinition();
		def.setChannelDefaults(Map.of(
				"VOICE", Map.of("contact-1", "DB", "contact-2", "VOICE", "contact-3", "WHATSAPP"),
				"WHATSAPP", Map.of("contact-1", "DB", "contact-2", "WHATSAPP", "contact-3", "SMS")));
		ChannelRules rules = new ChannelRules();
		rules.setOnFeedback(Map.of("NO_RESPONSE", "DEFAULT", "INCONCLUSIVE", "SAME", "REMIND_LATER", "REQUESTED"));
		def.setChannelRules(rules);
		return def;
	}

	@Test
	void firstContactUsesTheDbChannelWhenTheTableSaysDb() {
		assertThat(ChannelResolver.resolve(definition(), "contact-1", "VOICE", null, null, null)).isEqualTo("VOICE");
		assertThat(ChannelResolver.resolve(definition(), "contact-1", "WHATSAPP", null, null, null)).isEqualTo("WHATSAPP");
	}

	@Test
	void defaultFollowsTheTableForTheCustomersDbChannel() {
		assertThat(ChannelResolver.resolve(definition(), "contact-3", "VOICE", "VOICE", "DEFAULT", null)).isEqualTo("WHATSAPP");
		assertThat(ChannelResolver.resolve(definition(), "contact-3", "WHATSAPP", "WHATSAPP", "DEFAULT", null)).isEqualTo("SMS");
	}

	@Test
	void defaultWithoutATableEntryBehavesAsSame() {
		assertThat(ChannelResolver.resolve(definition(), "unlisted", "VOICE", "SMS", "DEFAULT", null)).isEqualTo("SMS");
		assertThat(ChannelResolver.resolve(new WorkflowDefinition(), "contact-3", "VOICE", "SMS", "DEFAULT", null)).isEqualTo("SMS");
		assertThat(ChannelResolver.resolve(new WorkflowDefinition(), "contact-1", "VOICE", null, null, null)).isEqualTo("VOICE");
	}

	@Test
	void sameKeepsTheLastChannelEvenWhenTheTableDisagrees() {
		assertThat(ChannelResolver.resolve(definition(), "contact-3", "VOICE", "VOICE", "SAME", null)).isEqualTo("VOICE");
	}

	@Test
	void flipUsesTheBuiltInMapAndTheFlipOverrides() {
		WorkflowDefinition def = definition();
		assertThat(ChannelResolver.resolve(def, "contact-2", "VOICE", "VOICE", "FLIP", null)).isEqualTo("WHATSAPP");
		assertThat(ChannelResolver.resolve(def, "contact-2", "VOICE", "WHATSAPP", "FLIP", null)).isEqualTo("VOICE");
		assertThat(ChannelResolver.resolve(def, "contact-2", "VOICE", "EMAIL", "FLIP", null)).isEqualTo("WHATSAPP");
		def.getChannelRules().setFlip(Map.of("VOICE", "SMS"));
		assertThat(ChannelResolver.resolve(def, "contact-2", "VOICE", "VOICE", "FLIP", null)).isEqualTo("SMS");
		assertThat(ChannelResolver.resolve(def, "contact-2", "VOICE", "WHATSAPP", "FLIP", null)).isEqualTo("VOICE");
	}

	@Test
	void requestedUsesTheCustomersChannelOtherwiseTheLastOne() {
		assertThat(ChannelResolver.resolve(definition(), "r", "VOICE", "VOICE", "REQUESTED", "SMS")).isEqualTo("SMS");
		assertThat(ChannelResolver.resolve(definition(), "r", "VOICE", "VOICE", "REQUESTED", null)).isEqualTo("VOICE");
	}

	@Test
	void aLiteralChannelIsUsedAsWritten() {
		assertThat(ChannelResolver.resolve(definition(), "r", "VOICE", "VOICE", "EMAIL", null)).isEqualTo("EMAIL");
	}

	@Test
	void aChannelWithoutItsOwnBlockUsesTheFallbackBlock() {
		WorkflowDefinition def = definition();
		def.setChannelDefaults(Map.of(
				"VOICE", Map.of("contact-1", "DB", "contact-3", "WHATSAPP"),
				"*", Map.of("contact-1", "DB", "contact-3", "SMS")));
		assertThat(ChannelResolver.resolve(def, "contact-1", "CHAT_BOT", null, null, null)).isEqualTo("CHAT_BOT");
		assertThat(ChannelResolver.resolve(def, "contact-3", "CHAT_BOT", "CHAT_BOT", "DEFAULT", null)).isEqualTo("SMS");
		assertThat(ChannelResolver.resolve(def, "contact-3", "VOICE", "VOICE", "DEFAULT", null)).isEqualTo("WHATSAPP");
	}

	@Test
	void withoutAFallbackBlockAnUnlistedChannelStaysOnItsChannel() {
		assertThat(ChannelResolver.resolve(definition(), "contact-3", "EMAIL", "EMAIL", "DEFAULT", null)).isEqualTo("EMAIL");
	}

	@Test
	void declaredChannelsDecideLiteralActionsAndFlip() {
		WorkflowDefinition def = definition();
		ChannelSpec chatBot = new ChannelSpec();
		chatBot.setFlip("VOICE");
		ChannelSpec voice = new ChannelSpec();
		voice.setFlip("CHAT_BOT");
		def.setChannels(Map.of("CHAT_BOT", chatBot, "VOICE", voice));
		assertThat(ChannelResolver.resolve(def, "r", "VOICE", "VOICE", "CHAT_BOT", null)).isEqualTo("CHAT_BOT");
		assertThat(ChannelResolver.resolve(def, "r", "VOICE", "VOICE", "EMAIL", null)).isEqualTo("VOICE"); // not declared: ignored
		assertThat(ChannelResolver.resolve(def, "r", "VOICE", "CHAT_BOT", "FLIP", null)).isEqualTo("VOICE");
		assertThat(ChannelResolver.resolve(def, "r", "VOICE", "VOICE", "FLIP", null)).isEqualTo("CHAT_BOT");
	}

	@Test
	void anUnlistedFeedbackTypeBehavesAsSame() {
		assertThat(ChannelResolver.actionFor(definition(), "POSITIVE")).isEqualTo("SAME");
		assertThat(ChannelResolver.actionFor(new WorkflowDefinition(), "NO_RESPONSE")).isEqualTo("SAME");
		assertThat(ChannelResolver.actionFor(definition(), "NO_RESPONSE")).isEqualTo("DEFAULT");
	}
}
