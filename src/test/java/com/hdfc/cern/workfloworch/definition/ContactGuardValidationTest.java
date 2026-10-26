package com.hdfc.cern.workfloworch.definition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hdfc.cern.workfloworch.temporal.activity.OrchActivities;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The optional contactGuard block, using the shipped 20-day file as the valid base. */
class ContactGuardValidationTest {

	@TempDir
	Path dir;

	@Test
	void shippedTwentyDayFileHasAGuardThatSkipsTheFirstContactAndTheNormalThankYou() {
		DefinitionLoader loader = new DefinitionLoader("workflow-definitions");
		loader.load();
		ContactGuard guard = loader.find("OUTREACH_20_DAY").orElseThrow().getContactGuard();

		assertThat(guard.getColumn()).isEqualTo("balance_maintained");
		assertThat(guard.getOnMet()).isEqualTo("thank-you-balance");
		assertThat(guard.appliesTo("contact-day4")).isTrue();
		assertThat(guard.appliesTo("remind-day4")).isTrue();
		assertThat(guard.appliesTo("final-notice-sms")).isTrue();
		assertThat(guard.appliesTo("contact-day1")).isFalse();
		assertThat(guard.appliesTo("thank-you")).isFalse();
		assertThat(guard.appliesTo("thank-you-balance")).isFalse(); // the target is never checked, so no loop
	}

	@Test
	void theBlockIsOptional() throws IOException {
		String yaml = valid().replaceAll("(?s)contactGuard:.*?\\n\\n", "")
				.replaceAll("(?s)  # --- The balance is maintained.*?next: end\\n\\n", "");
		Files.writeString(dir.resolve("ok.yaml"), yaml);
		new DefinitionLoader(dir.toString()).load();
	}

	@Test
	void theColumnMustBeOneTheServiceSupports() throws IOException {
		assertInvalid(valid().replace("column: balance_maintained", "column: wa_id"), "column 'wa_id' is not supported");
		assertInvalid(valid().replace("  column: balance_maintained\n", ""), "'column' is required");
	}

	@Test
	void theShippedDefinitionUsesASupportedColumn() {
		DefinitionLoader loader = new DefinitionLoader("workflow-definitions");
		loader.load();
		WorkflowDefinition def = loader.find("OUTREACH_20_DAY").orElseThrow();

		assertThat(ContactGuard.SUPPORTED_COLUMNS).contains(def.getContactGuard().getColumn());
		assertThat(DefinitionValidator.validate(def, OrchActivities.NAMES)).isEmpty();
	}

	@Test
	void onMetMustBeAnExistingContactStep() throws IOException {
		assertInvalid(valid().replace("  onMet: thank-you-balance", "  onMet: nowhere"), "onMet 'nowhere' is not a contact step");
		assertInvalid(valid().replace("  onMet: thank-you-balance", "  onMet: wait-day-4"), "onMet 'wait-day-4' is not a contact step");
		assertInvalid(valid().replace("  onMet: thank-you-balance\n", ""), "'onMet' is required");
	}

	@Test
	void exceptMustNameExistingContactSteps() throws IOException {
		assertInvalid(valid().replace("[contact-day1, thank-you]", "[contact-day1, nowhere]"), "except 'nowhere' is not a contact step");
		assertInvalid(valid().replace("[contact-day1, thank-you]", "[wait-day-1]"), "except 'wait-day-1' is not a contact step");
	}

	@Test
	void theOnMetStepCountsAsReachableButAnUnusedOneDoesNot() throws IOException {
		String withoutGuard = valid().replaceAll("(?s)contactGuard:.*?\\n\\n", "");
		assertInvalid(withoutGuard, "step 'thank-you-balance' is not reachable");
	}

	private void assertInvalid(String yaml, String expectedMessagePart) throws IOException {
		Path bad = Files.createTempDirectory(dir, "bad");
		Files.writeString(bad.resolve("bad.yaml"), yaml);
		assertThatThrownBy(() -> new DefinitionLoader(bad.toString()).load())
				.isInstanceOf(IllegalStateException.class).hasMessageContaining(expectedMessagePart);
	}

	private static String valid() throws IOException {
		return Files.readString(Path.of("workflow-definitions", "outreach-20-day.yaml")).replace("\r\n", "\n");
	}
}
