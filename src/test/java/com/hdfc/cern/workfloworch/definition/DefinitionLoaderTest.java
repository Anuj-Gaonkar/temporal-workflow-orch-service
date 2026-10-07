package com.hdfc.cern.workfloworch.definition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefinitionLoaderTest {

	@TempDir
	Path dir;

	@Test
	void shippedYamlLoads() {
		DefinitionLoader loader = new DefinitionLoader("workflow-definitions");
		loader.load(); // the working copy itself must be valid, even if shortened for a demo

		WorkflowDefinition def = loader.find("OUTREACH_DEFAULT").orElseThrow();
		assertThat(def.getStart()).isEqualTo("wait-day-1");
		assertThat(def.getCampaign().getMaxDays()).isEqualTo(15);
		assertThat(def.getSteps()).extracting(StepDefinition::getId).contains(
				"wait-day-1", "first-contact", "await-first", "route-first", "thank-you", "wait-reminder",
				"count-unanswered-contact", "count-unanswered-reminder", "final-reminder", "end");
	}

	@Test
	void duplicateStepIdFails() throws IOException {
		assertInvalid(valid().replace("id: thank-you", "id: first-contact"), "duplicate step id 'first-contact'");
	}

	@Test
	void unknownStepTypeFails() throws IOException {
		assertInvalid(valid().replace("type: await-feedback\n    timeout: 2h\n    next: route-first",
				"type: sleep\n    next: route-first"), "unknown step type 'sleep'");
	}

	@Test
	void unknownActivityFails() throws IOException {
		assertInvalid(valid().replace("  - id: end\n    type: end",
				"  - id: odd\n    type: activity\n    activity: fax\n    next: end\n\n  - id: end\n    type: end"),
				"unknown activity 'fax'");
	}

	@Test
	void badDurationFails() throws IOException {
		assertInvalid(valid().replace("duration: 1d\n    next: retry-contact", "duration: 1w\n    next: retry-contact"), "bad duration");
	}

	@Test
	void badTimeoutFails() throws IOException {
		assertInvalid(valid().replaceFirst("timeout: 2h", "timeout: soon"), "bad timeout");
	}

	@Test
	void danglingNextFails() throws IOException {
		assertInvalid(valid().replace("next: retry-contact", "next: nowhere"), "points to unknown step 'nowhere'");
	}

	@Test
	void danglingCaseTargetFails() throws IOException {
		assertInvalid(valid().replaceFirst("POSITIVE: thank-you", "POSITIVE: nowhere"),
				"case 'POSITIVE' points to unknown step 'nowhere'");
	}

	@Test
	void caseThatIsNotAFeedbackTypeFails() throws IOException {
		assertInvalid(valid().replaceFirst("INCONCLUSIVE:", "MAYBE:"), "case 'MAYBE' is not a feedback type");
	}

	@Test
	void countNeedsAResetOrALimitWithATarget() throws IOException {
		assertInvalid(valid().replace("    limit: 2\n", ""), "count needs 'reset: true' or a 'limit' of at least 1");
		assertInvalid(valid().replace("    whenReached: wait-final-reminder\n", ""), "needs 'whenReached'");
		assertInvalid(valid().replace("whenReached: wait-final-reminder", "whenReached: nowhere"), "whenReached points to unknown step 'nowhere'");
		assertInvalid(valid().replace("    counter: unanswered\n    limit: 2", "    limit: 2"), "count needs 'counter'");
	}

	@Test
	void resetCountMustNotHaveALimit() throws IOException {
		assertInvalid(valid().replace("    reset: true\n    next: wait-reminder", "    reset: true\n    limit: 3\n    next: wait-reminder"),
				"must not have 'limit' or 'whenReached'");
	}

	@Test
	void missingStartStepFails() throws IOException {
		assertInvalid(valid().replace("start: wait-day-1", "start: nowhere"), "start step 'nowhere' does not exist");
	}

	@Test
	void unreachableStepFails() throws IOException {
		assertInvalid(valid() + "  - id: orphan\n    type: end\n", "step 'orphan' is not reachable");
	}

	@Test
	void contactWithoutNextFails() throws IOException {
		assertInvalid(valid().replace("    next: await-first\n\n  # Feedback arrives", "\n  # Feedback arrives"),
				"step 'first-contact': 'next' is required");
	}

	@Test
	void unsupportedSwitchPathFails() throws IOException {
		assertInvalid(valid().replaceFirst("on: feedback.type", "on: voice-call.outcome"), "unsupported 'on' path");
	}

	@Test
	void badContactChannelFails() throws IOException {
		assertInvalid(valid().replace("channel: SMS", "channel: PIGEON"), "bad 'channel' 'PIGEON'");
	}

	@Test
	void delayNeedsExactlyOneKindOfWait() throws IOException {
		assertInvalid(valid().replace("untilDay: 1", "untilDay: 1\n    duration: 1d"), "exactly one of 'duration', 'fromVar' or 'untilDay'");
	}

	@Test
	void fromVarNeedsADefaultDuration() throws IOException {
		assertInvalid(valid().replace("    defaultDuration: 1d\n", ""), "'fromVar' needs a 'defaultDuration'");
	}

	@Test
	void untilDayNeedsACampaignBlock() throws IOException {
		assertInvalid(valid().replaceAll("(?s)campaign:.*?\\n\\nsteps:", "steps:"), "'untilDay' needs a 'campaign' block");
	}

	@Test
	void untilDayBeyondTheWindowFails() throws IOException {
		assertInvalid(valid().replace("untilDay: 1", "untilDay: 16"), "is after the campaign window");
	}

	@Test
	void badCampaignFails() throws IOException {
		assertInvalid(valid().replace("maxDays: 15", "maxDays: 0").replace("sendHour: 10", "sendHour: 25")
				.replace("timezone: Asia/Kolkata", "timezone: Mars/Base"), "'maxDays' must be at least 1");
	}

	@Test
	void errorNamesTheFile() throws IOException {
		assertInvalid(valid().replace("start: wait-day-1", "start: nowhere"), "Invalid workflow definition bad.yaml");
	}

	@Test
	void reloadPicksUpNewAndEditedFilesAndKeepsTheOldVersionWhenAnEditIsBroken() throws IOException {
		Files.writeString(dir.resolve("a.yaml"), valid());
		DefinitionLoader loader = new DefinitionLoader(dir.toString());
		loader.load();
		assertThat(loader.find("OUTREACH_DEFAULT").orElseThrow().getCampaign().getMaxDays()).isEqualTo(15);

		// valid edit goes live
		Files.writeString(dir.resolve("a.yaml"), valid().replace("maxDays: 15", "maxDays: 10"));
		DefinitionLoader.ReloadResult edited = loader.reload();
		assertThat(edited.changed()).containsExactly("OUTREACH_DEFAULT");
		assertThat(loader.find("OUTREACH_DEFAULT").orElseThrow().getCampaign().getMaxDays()).isEqualTo(10);

		// broken edit is reported, the previous version stays
		Files.writeString(dir.resolve("a.yaml"), valid().replace("start: wait-day-1", "start: nowhere"));
		DefinitionLoader.ReloadResult broken = loader.reload();
		assertThat(broken.errors()).singleElement().asString().contains("a.yaml").contains("start step 'nowhere'");
		assertThat(loader.find("OUTREACH_DEFAULT").orElseThrow().getCampaign().getMaxDays()).isEqualTo(10);

		// a new valid file appears
		Files.writeString(dir.resolve("a.yaml"), valid());
		Files.writeString(dir.resolve("b.yaml"), valid().replace("name: OUTREACH_DEFAULT", "name: SECOND"));
		assertThat(loader.reload().changed()).containsExactlyInAnyOrder("OUTREACH_DEFAULT", "SECOND");
		assertThat(loader.all()).extracting(WorkflowDefinition::getName).containsExactlyInAnyOrder("OUTREACH_DEFAULT", "SECOND");

		// an unchanged folder reports nothing
		DefinitionLoader.ReloadResult unchanged = loader.reload();
		assertThat(unchanged.changed()).isEmpty();
		assertThat(unchanged.errors()).isEmpty();
	}

	private void assertInvalid(String yaml, String expectedMessagePart) throws IOException {
		Files.writeString(dir.resolve("bad.yaml"), yaml);
		DefinitionLoader loader = new DefinitionLoader(dir.toString());
		assertThatThrownBy(loader::load)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining(expectedMessagePart);
	}

	private static String valid() throws IOException {
		return ShippedYaml.text();
	}
}
