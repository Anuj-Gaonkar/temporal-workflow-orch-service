package com.hdfc.cern.workfloworch.definition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Validation of the optional channelDefaults / channelRules blocks, using the shipped 20-day file as the valid base. */
class ChannelRulesValidationTest {

	@TempDir
	Path dir;

	@Test
	void shippedTwentyDayFileHasBothBlocks() {
		DefinitionLoader loader = new DefinitionLoader("workflow-definitions");
		loader.load();
		WorkflowDefinition def = loader.find("OUTREACH_20_DAY").orElseThrow();
		assertThat(def.getChannelDefaults()).containsKeys("VOICE", "WHATSAPP");
		assertThat(def.getChannelRules().getOnFeedback()).containsEntry("NO_RESPONSE", "DEFAULT");
	}

	@Test
	void theBlocksAreOptional() throws IOException {
		String yaml = withoutChannelBlocks(valid()).replace("channel: AUTO ", "channel: SMS  ");
		Files.writeString(dir.resolve("ok.yaml"), yaml);
		new DefinitionLoader(dir.toString()).load();
	}

	@Test
	void autoWithoutAnyBlockIsAllowed() throws IOException {
		String yaml = withoutChannelBlocks(valid());
		Files.writeString(dir.resolve("ok.yaml"), yaml);
		new DefinitionLoader(dir.toString()).load();
	}

	@Test
	void blocksWithoutAnyAutoContactAreRejected() throws IOException {
		assertInvalid(valid().replace("channel: AUTO ", "channel: SMS  "), "no contact step uses 'channel: AUTO'");
	}

	@Test
	void defaultsMustPointAtAutoContactSteps() throws IOException {
		assertInvalid(valid().replace("contact-day10: SMS", "nowhere: SMS"), "'nowhere' is not a contact step");
		assertInvalid(valid().replace("contact-day10: SMS", "wait-day-10: SMS"), "'wait-day-10' is not a contact step");
		assertInvalid(valid().replace("contact-day10: SMS", "final-notice-sms: SMS"), "must use 'channel: AUTO'");
	}

	@Test
	void defaultsNeedRealChannels() throws IOException {
		assertInvalid(valid().replace("contact-day10: SMS", "contact-day10: PIGEON"), "bad channel 'PIGEON'");
		assertInvalid(valid().replace("  VOICE:\n    contact-day1", "  CARRIER:\n    contact-day1"), "'CARRIER' is not a channel");
	}

	@Test
	void rulesNeedKnownFeedbackTypesAndActions() throws IOException {
		assertInvalid(valid().replace("NO_RESPONSE:  DEFAULT", "MAYBE:  DEFAULT"), "'MAYBE': not a feedback type");
		assertInvalid(valid().replace("NO_RESPONSE:  DEFAULT", "NO_RESPONSE:  ROTATE"), "bad action 'ROTATE'");
	}

	@Test
	void defaultIsNotAllowedForRemindLaterAndRequestedOnlyForRemindLater() throws IOException {
		assertInvalid(valid().replace("REMIND_LATER: REQUESTED", "REMIND_LATER: DEFAULT"), "DEFAULT is not allowed");
		assertInvalid(valid().replace("INCONCLUSIVE: SAME", "INCONCLUSIVE: REQUESTED"), "REQUESTED only makes sense for REMIND_LATER");
	}

	@Test
	void flipOverridesNeedChannelsAndMustChangeTheChannel() throws IOException {
		String withFlip = valid().replace("channelRules:\n", "channelRules:\n  flip:\n    VOICE: SMS\n");
		Files.writeString(dir.resolve("ok.yaml"), withFlip);
		new DefinitionLoader(dir.toString()).load();
		assertInvalid(valid().replace("channelRules:\n", "channelRules:\n  flip:\n    VOICE: VOICE\n"), "must not flip to itself");
		assertInvalid(valid().replace("channelRules:\n", "channelRules:\n  flip:\n    VOICE: PIGEON\n"), "must map a channel to a channel");
	}

	@Test
	void shippedFileDeclaresItsChannelsAndAFallbackBlock() {
		DefinitionLoader loader = new DefinitionLoader("workflow-definitions");
		loader.load();
		WorkflowDefinition def = loader.find("OUTREACH_20_DAY").orElseThrow();
		assertThat(def.knownChannels()).containsExactlyInAnyOrder("VOICE", "WHATSAPP", "SMS", "EMAIL");
		assertThat(def.getChannelDefaults()).containsKey("*");
		assertThat(def.flipOf("EMAIL")).isEqualTo("WHATSAPP");
	}

	@Test
	void aNewChannelCanBeDeclaredAndUsed() throws IOException {
		String yaml = valid().replace("  EMAIL:    { flip: WHATSAPP }\n", "  EMAIL:    { flip: WHATSAPP }\n  CHAT_BOT: { flip: WHATSAPP }\n")
				.replace("  \"*\":\n", "  CHAT_BOT:\n    contact-day1:  DB\n    contact-day4:  CHAT_BOT\n  \"*\":\n");
		Files.writeString(dir.resolve("ok.yaml"), yaml);
		DefinitionLoader loader = new DefinitionLoader(dir.toString());
		loader.load();
		assertThat(loader.knownChannels()).contains("CHAT_BOT");
	}

	@Test
	void channelsBlockIsChecked() throws IOException {
		assertInvalid(valid().replace("  EMAIL:    { flip: WHATSAPP }", "  EMAIL:    { flip: PIGEON }"), "flip 'PIGEON' is not a declared channel");
		assertInvalid(valid().replace("  EMAIL:    { flip: WHATSAPP }", "  EMAIL:    { flip: EMAIL }"), "must not flip to itself");
		assertInvalid(valid().replace("  EMAIL:    { flip: WHATSAPP }", "  chat-bot: { flip: WHATSAPP }"), "a channel code is upper-case");
		assertInvalid(valid().replace("  EMAIL:    { flip: WHATSAPP }", "  AUTO: { flip: WHATSAPP }"), "is reserved");
	}

	@Test
	void everythingMustUseADeclaredChannel() throws IOException {
		String withoutEmail = valid().replace("  EMAIL:    { flip: WHATSAPP }\n", "");
		assertInvalid(withoutEmail.replace("contact-day10: SMS", "contact-day10: EMAIL"), "bad channel 'EMAIL'");
		assertInvalid(withoutEmail.replace("REMIND_LATER: REQUESTED", "REMIND_LATER: REQUESTED\n    INCONCLUSIVE: EMAIL").replace("    INCONCLUSIVE: SAME\n", ""),
				"bad action 'EMAIL'");
		assertInvalid(valid().replace("channel: SMS\n    text: \"Thank you", "channel: PIGEON\n    text: \"Thank you"), "bad 'channel' 'PIGEON'");
	}

	@Test
	void flipNeedsATargetForEveryDeclaredChannel() throws IOException {
		String flipRule = valid().replace("INCONCLUSIVE: SAME", "INCONCLUSIVE: FLIP");
		Files.writeString(dir.resolve("ok.yaml"), flipRule);
		new DefinitionLoader(dir.toString()).load();
		assertInvalid(flipRule.replace("  EMAIL:    { flip: WHATSAPP }", "  EMAIL:    {}"), "channel 'EMAIL' has no flip target");
	}

	/** Removes channelDefaults and channelRules but keeps the contactGuard block that follows them. */
	private static String withoutChannelBlocks(String yaml) {
		return yaml.replaceAll("(?s)channelDefaults:.*?\\n(?=# Optional\\. Before every contact)", "");
	}

	private void assertInvalid(String yaml, String expectedMessagePart) throws IOException {
		Path bad = Files.createTempDirectory(dir, "bad");
		Files.writeString(bad.resolve("bad.yaml"), yaml);
		DefinitionLoader loader = new DefinitionLoader(bad.toString());
		assertThatThrownBy(loader::load).isInstanceOf(IllegalStateException.class).hasMessageContaining(expectedMessagePart);
	}

	private static String valid() throws IOException {
		return Files.readString(Path.of("workflow-definitions", "outreach-20-day.yaml")).replace("\r\n", "\n");
	}
}
