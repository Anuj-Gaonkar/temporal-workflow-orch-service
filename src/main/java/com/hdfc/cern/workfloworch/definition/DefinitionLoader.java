package com.hdfc.cern.workfloworch.definition;

import static com.hdfc.cern.workfloworch.logging.LogEvents.line;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.hdfc.cern.workfloworch.activity.OrchActivities;
import com.hdfc.cern.workfloworch.logging.LogEvents;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reads every {@code *.yaml} in the definitions folder and validates it. At startup a broken file
 * stops the app with a message naming the file and the problems. After that the folder is rescanned
 * (every few seconds and on {@code POST /workflow-definitions/reload}): a valid new or edited file
 * goes live, a broken one is reported and the previous version of that file stays live. Workflows
 * that are already running keep the definition they started with (it is their input).
 */
@Component
@Slf4j
public class DefinitionLoader {

	/** Outcome of one rescan: names that went live or changed, and the problems that kept files out. */
	public record ReloadResult(List<String> changed, List<String> removed, List<String> errors) {
	}

	private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory())
			.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
	private final Path folder;
	private volatile Map<String, WorkflowDefinition> definitions = Map.of();
	/** File name to the definition it last produced; a broken edit falls back to this. */
	private Map<String, WorkflowDefinition> byFile = Map.of();
	private String lastErrors = "";

	@Value("${orch.definitions-hot-reload:true}")
	private boolean hotReload = true;

	/** From the Spring profile (orch.timing.day-length): when set, one campaign "day" lasts this long (dev); empty = real days (prod). */
	@Value("${orch.timing.day-length:}")
	private String dayLengthOverride = "";

	public DefinitionLoader(@Value("${orch.definitions-path:./workflow-definitions}") String definitionsPath) {
		this.folder = Path.of(definitionsPath);
	}

	@PostConstruct
	public void load() {
		long started = System.nanoTime();
		ReloadResult result = scan();
		if (!result.errors().isEmpty()) {
			throw new IllegalStateException(String.join("; ", result.errors()));
		}
		log.info(line(LogEvents.DEFINITIONS_LOADED, "count", definitions.size(), "names", String.join(",", definitions.keySet()),
				"loadMs", (System.nanoTime() - started) / 1_000_000));
	}

	/** Rescans the folder now. Never throws for a bad file; the problems are in the result. */
	public ReloadResult reload() {
		long started = System.nanoTime();
		ReloadResult result = scan();
		String errors = String.join("; ", result.errors());
		if (!result.changed().isEmpty() || !result.removed().isEmpty()) {
			log.info(line(LogEvents.DEFINITIONS_RELOADED, "count", definitions.size(), "changed", String.join(",", result.changed()),
					"removed", String.join(",", result.removed()), "loadMs", (System.nanoTime() - started) / 1_000_000));
		}
		if (!errors.equals(lastErrors)) {
			lastErrors = errors;
			if (!errors.isEmpty()) {
				log.warn(line(LogEvents.DEFINITIONS_RELOAD_FAILED, "error", errors));
			}
		}
		return result;
	}

	@Scheduled(fixedDelayString = "${orch.definitions-reload-seconds:10}", initialDelayString = "${orch.definitions-reload-seconds:10}",
			timeUnit = TimeUnit.SECONDS)
	void scheduledReload() {
		if (hotReload) {
			reload();
		}
	}

	private synchronized ReloadResult scan() {
		if (!Files.isDirectory(folder)) {
			return new ReloadResult(List.of(), List.of(),
					List.of("Workflow definitions folder does not exist: " + folder.toAbsolutePath()));
		}
		List<String> errors = new ArrayList<>();
		Map<String, WorkflowDefinition> nextByFile = new LinkedHashMap<>();
		Map<String, String> fileByName = new LinkedHashMap<>();
		try (Stream<Path> listing = Files.list(folder)) {
			for (Path file : listing.filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted().toList()) {
				String fileName = file.getFileName().toString();
				WorkflowDefinition def;
				try {
					def = parse(file);
				} catch (IllegalStateException e) {
					errors.add(e.getMessage());
					def = byFile.get(fileName); // keep the last good version of this file
					if (def == null) {
						continue;
					}
				}
				String other = fileByName.putIfAbsent(def.getName(), fileName);
				if (other != null) {
					errors.add(broken(file, List.of("definition name '" + def.getName() + "' is already defined by " + other)).getMessage());
					continue;
				}
				nextByFile.put(fileName, def);
			}
		} catch (IOException e) {
			return new ReloadResult(List.of(), List.of(), List.of("Could not read workflow definitions folder "
					+ folder.toAbsolutePath() + ": " + e.getMessage()));
		}

		Map<String, WorkflowDefinition> next = new LinkedHashMap<>();
		nextByFile.values().forEach(d -> next.put(d.getName(), d));
		List<String> changed = new ArrayList<>();
		next.forEach((name, def) -> {
			if (!def.equals(definitions.get(name))) {
				changed.add(name);
			}
		});
		List<String> removed = definitions.keySet().stream().filter(n -> !next.containsKey(n)).toList();
		byFile = nextByFile;
		definitions = Collections.unmodifiableMap(next);
		return new ReloadResult(changed, removed, errors);
	}

	/** Parses and validates one file; throws with the file name and every problem found. */
	WorkflowDefinition parse(Path file) {
		WorkflowDefinition def;
		try {
			def = yaml.readValue(file.toFile(), WorkflowDefinition.class);
		} catch (IOException e) {
			String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
			int newline = message.indexOf('\n');
			throw broken(file, List.of(newline > 0 ? message.substring(0, newline) : message));
		}
		if (def == null) {
			throw broken(file, List.of("file is empty"));
		}
		if (def.getCampaign() != null && dayLengthOverride != null && !dayLengthOverride.isBlank()) {
			def.getCampaign().setDayLength(dayLengthOverride.trim());
		}
		List<String> errors = DefinitionValidator.validate(def, OrchActivities.NAMES);
		if (!errors.isEmpty()) {
			throw broken(file, errors);
		}
		return def;
	}

	public Optional<WorkflowDefinition> find(String name) {
		return Optional.ofNullable(definitions.get(name));
	}

	public List<WorkflowDefinition> all() {
		return new ArrayList<>(definitions.values());
	}

	private static IllegalStateException broken(Path file, List<String> problems) {
		return new IllegalStateException("Invalid workflow definition " + file.getFileName() + ": " + String.join("; ", problems));
	}
}
