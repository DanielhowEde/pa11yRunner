package com.automation.pa11y.report;

import com.automation.pa11y.Issue;
import com.automation.pa11y.IssueType;
import com.automation.pa11y.Pa11yException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads and writes the per-page JSON files that the combined report is built from.
 *
 * <p>Every file carries a {@code generator} marker. That is what lets {@code clean} delete
 * this tool's output without touching anything else that happens to be a {@code .json} in
 * the same directory -- a reports folder is exactly the sort of place someone also drops a
 * config file or a fixture.
 */
public final class ReportStore {

	/** Written into every file, and required to read one back. */
	public static final String GENERATOR = "pa11y-runner";

	/** Bumped only if the shape changes in a way older readers cannot cope with. */
	public static final int SCHEMA_VERSION = 1;

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final Path directory;

	/**
	 * @param directory where the per-page files live
	 */
	public ReportStore(Path directory) {
		this.directory = directory;
	}

	/**
	 * @return the directory being used
	 */
	public Path directory() {
		return directory;
	}

	/**
	 * Saves one page, overwriting any previous scan of the same name.
	 *
	 * @param report the page to save
	 * @return the file it was written to
	 */
	public Path write(PageReport report) {
		Path file = directory.resolve(fileName(report.name()));
		try {
			Files.createDirectories(directory);
			Files.writeString(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(toJson(report)),
					StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new Pa11yException("Could not write the report to " + file + ".", e);
		}
		return file;
	}

	/**
	 * Reads every page report in the directory, oldest scan first.
	 *
	 * @return the reports, or an empty list if the directory does not exist
	 */
	public List<PageReport> readAll() {
		List<PageReport> reports = new ArrayList<>();
		for (Path file : jsonFiles()) {
			JsonNode root = readIfReport(file);
			if (root != null) {
				reports.add(fromJson(root, file));
			}
		}
		reports.sort(Comparator.comparing(PageReport::scannedAt).thenComparing(PageReport::name));
		return reports;
	}

	/**
	 * Deletes saved reports.
	 *
	 * @param everything {@code true} to delete every {@code .json} in the directory,
	 *                   {@code false} to delete only files this tool wrote
	 * @return what was deleted and what was left alone
	 */
	public CleanResult clean(boolean everything) {
		List<Path> deleted = new ArrayList<>();
		List<Path> kept = new ArrayList<>();

		for (Path file : jsonFiles()) {
			if (!everything && readIfReport(file) == null) {
				kept.add(file);
				continue;
			}
			try {
				Files.delete(file);
				deleted.add(file);
			} catch (IOException e) {
				throw new Pa11yException("Could not delete " + file + ".", e);
			}
		}
		return new CleanResult(List.copyOf(deleted), List.copyOf(kept));
	}

	/**
	 * @return every {@code .json} file in the directory, sorted by name
	 */
	private List<Path> jsonFiles() {
		if (!Files.isDirectory(directory)) {
			return List.of();
		}
		List<Path> files = new ArrayList<>();
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "*.json")) {
			for (Path entry : entries) {
				if (Files.isRegularFile(entry)) {
					files.add(entry);
				}
			}
		} catch (IOException e) {
			throw new Pa11yException("Could not list " + directory + ".", e);
		}
		files.sort(Comparator.comparing(path -> path.getFileName().toString()));
		return files;
	}

	/**
	 * @param file a candidate file
	 * @return its parsed contents, or {@code null} if it is not one of ours
	 */
	private static JsonNode readIfReport(Path file) {
		try {
			JsonNode root = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
			JsonNode generator = root.get("generator");
			return generator != null && GENERATOR.equals(generator.asText()) ? root : null;
		} catch (IOException e) {
			// Unreadable or not JSON at all: not ours, so leave it alone rather than failing
			// a report run over a stray file.
			return null;
		}
	}

	/**
	 * Turns a name into a safe file name. A name arrives from the command line and ends up
	 * as a path, so anything that could climb out of the directory is stripped rather than
	 * escaped.
	 *
	 * @param name the name the page was scanned under
	 * @return the file name to use
	 */
	public static String fileName(String name) {
		return slug(name) + ".json";
	}

	/**
	 * @param value any text
	 * @return a lowercase, hyphenated form safe to use as a file name
	 */
	public static String slug(String value) {
		String slug = value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
				.replaceAll("[^a-z0-9]+", "-")
				.replaceAll("(^-+)|(-+$)", "");
		return slug.isEmpty() ? "page" : slug;
	}

	/**
	 * @param report the page to serialise
	 * @return it as JSON
	 */
	private static ObjectNode toJson(PageReport report) {
		ObjectNode root = MAPPER.createObjectNode();
		root.put("generator", GENERATOR);
		root.put("schema", SCHEMA_VERSION);
		root.put("name", report.name());
		root.put("requestedUrl", report.requestedUrl());
		root.put("pageUrl", report.pageUrl());
		root.put("documentTitle", report.documentTitle());
		root.put("scannedAt", report.scannedAt().toString());
		root.put("durationMillis", report.duration().toMillis());
		root.put("standard", report.standard());

		ArrayNode engines = root.putArray("engines");
		report.engines().forEach(engines::add);

		root.put("errorCount", report.count(IssueType.ERROR));
		root.put("warningCount", report.count(IssueType.WARNING));
		root.put("noticeCount", report.count(IssueType.NOTICE));

		ArrayNode issues = root.putArray("issues");
		for (Issue issue : report.issues()) {
			ObjectNode entry = issues.addObject();
			entry.put("code", issue.code());
			entry.put("type", issue.type().wireName());
			entry.put("typeCode", issue.typeCode());
			entry.put("message", issue.message());
			entry.put("selector", issue.selector());
			entry.put("context", issue.context());
			entry.put("engine", issue.engine());
		}
		return root;
	}

	/**
	 * @param root the parsed file
	 * @param file where it came from, for the error message
	 * @return the page report
	 */
	private static PageReport fromJson(JsonNode root, Path file) {
		List<Issue> issues = new ArrayList<>();
		JsonNode issueNodes = root.get("issues");
		if (issueNodes != null && issueNodes.isArray()) {
			for (JsonNode node : issueNodes) {
				issues.add(new Issue(
						text(node, "code"),
						IssueType.fromWireName(text(node, "type")),
						node.path("typeCode").asInt(-1),
						text(node, "message"),
						text(node, "context"),
						text(node, "selector"),
						text(node, "engine"),
						Map.of()));
			}
		}

		List<String> engines = new ArrayList<>();
		JsonNode engineNodes = root.get("engines");
		if (engineNodes != null && engineNodes.isArray()) {
			engineNodes.forEach(node -> engines.add(node.asText()));
		}

		String fallbackName = file.getFileName().toString().replaceFirst("\\.json$", "");
		return new PageReport(
				root.hasNonNull("name") ? root.get("name").asText() : fallbackName,
				text(root, "requestedUrl"),
				text(root, "pageUrl"),
				text(root, "documentTitle"),
				instant(text(root, "scannedAt")),
				Duration.ofMillis(root.path("durationMillis").asLong(0)),
				text(root, "standard"),
				engines,
				issues);
	}

	/**
	 * @param node  the object to read from
	 * @param field the field name
	 * @return the field's text, or an empty string
	 */
	private static String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value == null || value.isNull() ? "" : value.asText();
	}

	/**
	 * @param value an ISO-8601 timestamp, possibly missing or malformed
	 * @return the instant, or the epoch if it cannot be read
	 */
	private static Instant instant(String value) {
		try {
			return value.isBlank() ? Instant.EPOCH : Instant.parse(value);
		} catch (DateTimeParseException e) {
			return Instant.EPOCH;
		}
	}

	/**
	 * What a clean did.
	 *
	 * @param deleted the files that were removed
	 * @param kept    the {@code .json} files left alone because this tool did not write them
	 */
	public record CleanResult(List<Path> deleted, List<Path> kept) {
	}
}
