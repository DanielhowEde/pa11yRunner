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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

	/**
	 * Bumped only if the shape changes in a way older readers cannot cope with.
	 *
	 * <p>2 added {@code status}, and {@code failureKind} and {@code failureMessage} with it.
	 * A version 1 file reads back as a completed scan, which is what it is: failures were not
	 * written at all before this.
	 */
	public static final int SCHEMA_VERSION = 2;

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
	 * Creates the reports directory if it is not there yet.
	 *
	 * <p>Idempotent, and a directory that is already there is not an error. Writing a report
	 * and cleaning both call this rather than expecting the directory to have been made by
	 * hand, so a fresh checkout -- or a {@code target} a build has just wiped -- is not a
	 * reason for a run to fail. Parents are created too, so a {@code reportsDir} of
	 * {@code target/pa11yReports} works when {@code target} does not exist either.
	 *
	 * <p>Reading does not call it: {@link #readAll()} already treats a directory that is not
	 * there as no reports, and a read has no business leaving a directory behind.
	 *
	 * @return the directory
	 * @throws Pa11yException if it cannot be created, or something that is not a directory is
	 *                        already in its place
	 */
	public Path ensureDirectory() {
		if (Files.isDirectory(directory)) {
			return directory;
		}
		try {
			Files.createDirectories(directory);
		} catch (FileAlreadyExistsException e) {
			// A file sitting where the reports directory should be is a misconfiguration, and
			// saying so once beats every later write failing a page at a time.
			throw new Pa11yException("Cannot use " + directory.toAbsolutePath()
					+ " for reports: something that is not a directory is already there. Point"
					+ " --reports-dir, PA11Y_REPORTS_DIR or the reportsDir in the config file"
					+ " somewhere else.", e);
		} catch (IOException e) {
			throw new Pa11yException("Could not create the reports directory "
					+ directory.toAbsolutePath() + ".", e);
		}
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
		ensureDirectory();
		try {
			writeAtomically(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(toJson(report)));
		} catch (IOException e) {
			throw new Pa11yException("Could not write the report to " + file + ".", e);
		}
		return file;
	}

	/**
	 * Writes to a temporary file in the same directory and renames it over the target.
	 *
	 * <p>A reader of this directory is either the report step or a person, and both can turn
	 * up while a suite is still scanning. Writing in place leaves a window where the file is
	 * half a JSON document, which reads as a corrupt report rather than as a scan in progress.
	 * The rename is the only part anyone else observes.
	 *
	 * @param file     the file to end up with
	 * @param contents what to put in it
	 * @throws IOException if the write or the rename fails
	 */
	static void writeAtomically(Path file, String contents) throws IOException {
		Path partial = file.resolveSibling(file.getFileName() + ".partial");
		try {
			Files.writeString(partial, contents, StandardCharsets.UTF_8);
			try {
				Files.move(partial, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException e) {
				// Some network and container filesystems cannot promise it. A plain replace is
				// still far better than writing the target in place.
				Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(partial);
		}
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
	 * <p>The directory is created if it is not there yet, so this is safe as the first step of
	 * every run, on a machine where nothing has written a report before.
	 *
	 * @param everything {@code true} to delete every {@code .json} in the directory,
	 *                   {@code false} to delete only reports this tool wrote. Matching HTML
	 *                   reports are removed with their JSON records.
	 * @return what was deleted and what was left alone
	 */
	public CleanResult clean(boolean everything) {
		refuseDangerousDirectory();
		// Created rather than merely tolerated: clean is the first step of a run, so leaving
		// the directory behind means the scans that follow, and anyone watching the folder,
		// find it where it is meant to be. Nothing to clean is not a failure.
		ensureDirectory();

		List<Path> deleted = new ArrayList<>();
		List<Path> kept = new ArrayList<>();

		for (Path file : jsonFiles()) {
			JsonNode report = readIfReport(file);
			if (!everything && report == null) {
				kept.add(file);
				continue;
			}
			try {
				Files.delete(file);
				deleted.add(file);
				if (report != null) {
					Path html = directory.resolve(file.getFileName().toString().replaceFirst("\\.json$", ".html"));
					try {
						if (Files.deleteIfExists(html)) {
							deleted.add(html);
						}
					} catch (IOException e) {
						throw new Pa11yException("Could not delete " + html + ".", e);
					}
				}
			} catch (IOException e) {
				throw new Pa11yException("Could not delete " + file + ".", e);
			}
		}
		return new CleanResult(List.copyOf(deleted), List.copyOf(kept));
	}

	private List<Path> jsonFiles() {
		if (!Files.isDirectory(directory)) {
			return List.of();
		}
		List<Path> files = new ArrayList<>();
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "*.json")) {
			for (Path entry : entries) {
				// NOFOLLOW_LINKS: a symlink in here points at a file somewhere else, and
				// deleting what it aims at would be deleting outside this directory. The link
				// is not a report this tool wrote, so it is simply not a candidate.
				if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
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
	 * Refuses a directory that would be catastrophic to empty.
	 *
	 * <p>{@code clean} only ever deletes {@code *.json} and their matching {@code *.html} in
	 * one directory, never recursively, so the blast radius is small by construction. This is
	 * the second belt: the directory arrives from a command line, an environment variable or a
	 * config file, and any of those can be empty or half-substituted. A filesystem root or a
	 * home directory is never where reports go, and emptying one is not recoverable.
	 *
	 * @throws Pa11yException if the directory is a filesystem root or the user's home directory
	 */
	void refuseDangerousDirectory() {
		Path absolute = directory.toAbsolutePath().normalize();
		if (absolute.getParent() == null) {
			throw new Pa11yException("Refusing to clean " + absolute
					+ ", which is a filesystem root. Point --reports-dir, PA11Y_REPORTS_DIR or the"
					+ " reportsDir in the config file at a directory of its own.");
		}
		Path home = Path.of(System.getProperty("user.home", "")).toAbsolutePath().normalize();
		if (absolute.equals(home)) {
			throw new Pa11yException("Refusing to clean " + absolute
					+ ", which is your home directory. Point --reports-dir, PA11Y_REPORTS_DIR or the"
					+ " reportsDir in the config file at a directory of its own.");
		}
	}

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
	 * @param name the name the page was scanned under
	 * @return the HTML report file name
	 */
	public static String htmlFileName(String name) {
		return slug(name) + ".html";
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
		root.put("status", report.status().wireName());
		if (report.isFailed()) {
			root.put("failureKind", report.failureKind());
			root.put("failureMessage", report.failureMessage());
		}

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
		// A file with no status was written before statuses existed, and only completed scans
		// were ever written then, so the absence is not ambiguous.
		ScanStatus status = ScanStatus.fromWireName(root.hasNonNull("status")
				? root.get("status").asText()
				: null);
		return new PageReport(
				root.hasNonNull("name") ? root.get("name").asText() : fallbackName,
				text(root, "requestedUrl"),
				text(root, "pageUrl"),
				text(root, "documentTitle"),
				instant(text(root, "scannedAt")),
				Duration.ofMillis(root.path("durationMillis").asLong(0)),
				text(root, "standard"),
				engines,
				issues,
				status,
				status == ScanStatus.FAILED ? text(root, "failureKind") : null,
				status == ScanStatus.FAILED ? text(root, "failureMessage") : null);
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value == null || value.isNull() ? "" : value.asText();
	}

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
