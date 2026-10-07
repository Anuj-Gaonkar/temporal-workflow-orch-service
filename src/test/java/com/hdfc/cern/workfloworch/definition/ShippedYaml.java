package com.hdfc.cern.workfloworch.definition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The shipped {@code workflow-definitions/outreach-default.yaml} as the tests see it: always in real
 * calendar-day mode with the 2h feedback wait, even if someone shortened the working copy for a demo
 * (a {@code dayLength} line and short {@code timeout:} values). Test sources only.
 */
public final class ShippedYaml {

	private static final Path FILE = Path.of("workflow-definitions/outreach-default.yaml");

	private ShippedYaml() {
	}

	/** The YAML text with demo shortcuts removed. */
	public static String text() {
		try {
			return Files.readString(FILE)
					.replaceAll("(?m)^\\s*dayLength:.*\\R", "")
					.replaceAll("(?m)^(\\s*timeout:) \\S+", "$1 2h");
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	/** The normalised definition, loaded through the real loader. */
	public static WorkflowDefinition definition() {
		try {
			Path dir = Files.createTempDirectory("shipped-yaml");
			Files.writeString(dir.resolve("outreach-default.yaml"), text());
			DefinitionLoader loader = new DefinitionLoader(dir.toString());
			loader.load();
			return loader.find("OUTREACH_DEFAULT").orElseThrow();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}
}
