package com.automation.pa11y;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Scan defaults for a whole project, read from a {@code pa11yConfig.json} in the base directory.
 *
 * <p>A project's accessibility policy -- the standard, the part of the page that is actually
 * the page, the rules already accepted as known -- is the same for every screen. Repeating it
 * on every scan command is how two pages end up judged by different rules, and how a rule
 * someone agreed to ignore comes back as a failure on the one page that forgot the flag.
 *
 * <p>Everything here is optional. With no config file, every default is exactly what it was
 * before this existed.
 *
 * <h2>Where it is looked for</h2>
 *
 * <p>The working directory, then up to five ancestors -- the same rule the scanner's
 * {@code node_modules} is found by, so "the base directory" means one thing in both places.
 * A file named explicitly, with {@code --config}, the {@code pa11y.config.file} system
 * property or {@code PA11Y_CONFIG_FILE}, wins over searching, and if it is not there that is
 * an error rather than a reason to look elsewhere: silently scanning under someone else's
 * policy is worse than not scanning.
 *
 * <p>Relative paths inside the file are resolved against the file's own directory, not the
 * working directory. A config saying {@code "reportsDir": "target/pa11yReports"} means the
 * same place whichever module the suite was started from.
 *
 * <p>JSON has no comments, so any key beginning with an underscore is ignored and can hold a
 * note. That is how an entry in {@code ignore} gets to say why it is there, which is the thing
 * everyone wants to know about it six months later.
 *
 * <h2>What cannot go in it</h2>
 *
 * <p><strong>Chrome's debugger URL is not a setting.</strong> The Grid CDP address contains
 * the session id, so it is different for every run; it belongs to the scan, not to the
 * project. Pass it per scan with {@code --chrome}, {@code PA11Y_CHROME_URL} or
 * {@link Pa11yRunner.Builder#chromeDebuggerUrl(String)}. A {@code chromeUrl} key in the file
 * is rejected with that explanation rather than quietly ignored.
 *
 * <h2>Precedence</h2>
 *
 * <pre>
 * command line flag &gt; system property &gt; environment variable &gt; this file &gt; built-in default
 * </pre>
 *
 * <p>The three switches -- {@code debug}, {@code includeWarnings}, {@code includeNotices} --
 * are the exception, because a command line switch can only turn something on. Either source
 * saying {@code true} makes it true, and there is no flag that turns one back off.
 *
 * @param source          the file these values came from, or {@code null} if there was none
 * @param reportsDir      where per-page reports go, or {@code null} if unset
 * @param scannerDir      the directory holding the scanner's {@code node_modules}, or {@code null}
 * @param nodeExecutable  the {@code node} binary to run, or {@code null}
 * @param debug           whether the scanner should log what it is doing
 * @param standard        the standard to test against, or {@code null} if unset
 * @param includeWarnings whether warnings are reported as well as errors
 * @param includeNotices  whether notices are reported as well as errors
 * @param timeout         how long a scan may take, or {@code null} if unset
 * @param waitAfterLoad   how long to wait before inspecting, or {@code null} if unset
 * @param rootElement     a CSS selector to limit scans to, or {@code null}
 * @param hideElements    a CSS selector for elements to exclude, or {@code null}
 * @param ignore          rule codes or issue types to drop
 * @param actions         Pa11y actions to run before every scan
 * @param combinedReportName the file name for the combined report, never {@code null}
 */
public record Pa11yConfig(
		Path source,
		Path reportsDir,
		Path scannerDir,
		String nodeExecutable,
		boolean debug,
		Standard standard,
		boolean includeWarnings,
		boolean includeNotices,
		Duration timeout,
		Duration waitAfterLoad,
		String rootElement,
		String hideElements,
		List<String> ignore,
		List<String> actions,
		String combinedReportName) {

	/** The file name looked for in the base directory. */
	public static final String FILE_NAME = "pa11yConfig.json";

	/** What the combined report is called when the config does not say. */
	public static final String DEFAULT_COMBINED_REPORT_NAME = "combined-report.html";

	/** System property naming the config file to read. */
	public static final String CONFIG_FILE_PROPERTY = "pa11y.config.file";

	/** Environment variable naming the config file to read. */
	public static final String CONFIG_FILE_ENV = "PA11Y_CONFIG_FILE";

	/** How far up from the working directory to look, matching the scanner search. */
	private static final int ANCESTORS_SEARCHED = 5;

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final Pa11yConfig EMPTY = new Pa11yConfig(
			null, null, null, null, false, null, false, false, null, null, null, null,
			List.of(), List.of(), null);

	/** Every key the file may contain. Anything else is a mistake worth stopping for. */
	private static final Set<String> KNOWN_KEYS = Set.of(
			"reportsDir", "scannerDir", "nodeExecutable", "debug", "standard",
			"includeWarnings", "includeNotices", "timeout", "wait",
			"rootElement", "hideElements", "ignore", "actions", "combinedReportName");

	private static final String CHROME_ADVICE =
			"cannot be a stored default: the Selenium Grid CDP address contains the session id, "
					+ "so it is different for every run. Pass it per scan with --chrome, the "
					+ "PA11Y_CHROME_URL environment variable, or "
					+ "Pa11yRunner.builder().chromeDebuggerUrl(...).";

	private static final String RUNNER_ADVICE =
			"is not a choice: every scan uses HTML CodeSniffer, so that a rule code means the "
					+ "same thing in every report.";

	/** Keys someone will reasonably try, answered with why they are not settings. */
	private static final Map<String, String> REJECTED_KEYS = Map.of(
			"chromeUrl", CHROME_ADVICE,
			"chrome", CHROME_ADVICE,
			"chromeDebuggerUrl", CHROME_ADVICE,
			"url", "is per scan, not per project. Pass --url, or leave it out to scan the tab "
					+ "Chrome already has open.",
			"name", "is per scan: it is the name one page is saved under. Pass --name.",
			"runner", RUNNER_ADVICE,
			"runners", RUNNER_ADVICE);

	public Pa11yConfig {
		ignore = ignore == null ? List.of() : List.copyOf(ignore);
		actions = actions == null ? List.of() : List.copyOf(actions);
		combinedReportName = combinedReportName == null || combinedReportName.isBlank()
				? DEFAULT_COMBINED_REPORT_NAME
				: combinedReportName;
	}

	/**
	 * @return a config that sets nothing, for a run with no config file
	 */
	public static Pa11yConfig empty() {
		return EMPTY;
	}

	/**
	 * Finds and reads the config file, if there is one.
	 *
	 * @return the config, or {@link #empty()} if no file was found
	 * @throws Pa11yException if a named file is missing, or a file was found and cannot be read
	 */
	public static Pa11yConfig discover() {
		return discover(null);
	}

	/**
	 * Finds and reads the config file, if there is one.
	 *
	 * @param explicit a file named on the command line, or {@code null} to search
	 * @return the config, or {@link #empty()} if no file was found
	 * @throws Pa11yException if a named file is missing, or a file was found and cannot be read
	 */
	public static Pa11yConfig discover(Path explicit) {
		Named named = named(explicit);
		if (named != null) {
			if (!Files.isRegularFile(named.file())) {
				throw new Pa11yException("No config file at "
						+ named.file().toAbsolutePath().normalize()
						+ ", which came from " + named.source() + "."
						+ System.lineSeparator() + System.lineSeparator()
						+ "Remove that setting to look for a " + FILE_NAME
						+ " in the working directory and its ancestors instead.");
			}
			return load(named.file());
		}
		Path found = search();
		return found == null ? EMPTY : load(found);
	}

	/**
	 * Reads one config file.
	 *
	 * @param file the file to read
	 * @return what it says
	 * @throws Pa11yException if it cannot be read, is not an object, or holds a key or value
	 *                        this tool does not understand
	 */
	public static Pa11yConfig load(Path file) {
		Path absolute = file.toAbsolutePath().normalize();
		JsonNode root;
		try {
			root = MAPPER.readTree(Files.readString(absolute, StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new Pa11yException("Could not read the config file " + absolute + ": " + e.getMessage(), e);
		}
		if (root == null || !root.isObject()) {
			throw new Pa11yException("The config file " + absolute + " must hold a JSON object.");
		}

		Parser parser = new Parser(absolute, root);
		parser.checkKeys();
		return new Pa11yConfig(
				absolute,
				parser.path("reportsDir"),
				parser.path("scannerDir"),
				parser.text("nodeExecutable"),
				parser.bool("debug"),
				parser.standard(),
				parser.bool("includeWarnings"),
				parser.bool("includeNotices"),
				parser.duration("timeout", true),
				parser.duration("wait", false),
				parser.text("rootElement"),
				parser.text("hideElements"),
				parser.strings("ignore"),
				parser.strings("actions"),
				parser.fileName("combinedReportName"));
	}

	/**
	 * @return whether these values came from a file
	 */
	public boolean isPresent() {
		return source != null;
	}

	/**
	 * A request for the tab Chrome already has open, carrying this file's defaults.
	 *
	 * @return a builder to override further, or build as it stands
	 */
	public ScanRequest.Builder currentPage() {
		return applyTo(ScanRequest.currentPage());
	}

	/**
	 * A request that loads a URL in a new tab, carrying this file's defaults.
	 *
	 * @param url the page to scan
	 * @return a builder to override further, or build as it stands
	 */
	public ScanRequest.Builder forUrl(String url) {
		return applyTo(ScanRequest.forUrl(url));
	}

	/**
	 * Fills in whatever this config sets, leaving the rest of the builder alone.
	 *
	 * <p>{@code ignore} and {@code actions} are added to rather than replaced, and the
	 * config's own come first. That is what makes a per-page {@code --action} run after the
	 * cookie banner the whole project has to click through.
	 *
	 * @param builder the builder to seed
	 * @return the same builder
	 */
	public ScanRequest.Builder applyTo(ScanRequest.Builder builder) {
		if (standard != null) {
			builder.standard(standard);
		}
		if (includeWarnings) {
			builder.includeWarnings(true);
		}
		if (includeNotices) {
			builder.includeNotices(true);
		}
		if (timeout != null) {
			builder.timeout(timeout);
		}
		if (waitAfterLoad != null) {
			builder.waitAfterLoad(waitAfterLoad);
		}
		if (rootElement != null) {
			builder.rootElement(rootElement);
		}
		if (hideElements != null) {
			builder.hideElements(hideElements);
		}
		return builder
				.ignore(ignore.toArray(String[]::new))
				.actions(actions.toArray(String[]::new));
	}

	private static Named named(Path explicit) {
		if (explicit != null) {
			return new Named("--config", explicit);
		}
		String property = System.getProperty(CONFIG_FILE_PROPERTY);
		if (property != null && !property.isBlank()) {
			return new Named("the " + CONFIG_FILE_PROPERTY + " system property", Path.of(property.trim()));
		}
		String environment = System.getenv(CONFIG_FILE_ENV);
		if (environment != null && !environment.isBlank()) {
			return new Named("the " + CONFIG_FILE_ENV + " environment variable", Path.of(environment.trim()));
		}
		return null;
	}

	private static Path search() {
		Path directory = Path.of("").toAbsolutePath();
		for (int depth = 0; depth <= ANCESTORS_SEARCHED && directory != null; depth++) {
			Path candidate = directory.resolve(FILE_NAME);
			if (Files.isRegularFile(candidate)) {
				return candidate.normalize();
			}
			directory = directory.getParent();
		}
		// Not an error. A config file is a convenience, and a project that passes everything
		// on the command line is a perfectly good way to use this.
		return null;
	}

	/**
	 * Reads one file's values, and refuses anything it does not understand.
	 *
	 * <p>Tolerance is the wrong instinct here. This file is edited by hand and read once, at
	 * the start of a run, so a typo that is ignored means a whole suite scanned under a policy
	 * nobody chose, with nothing in the output to say so.
	 */
	private static final class Parser {

		private final Path file;
		private final JsonNode root;

		private Parser(Path file, JsonNode root) {
			this.file = file;
			this.root = root;
		}

		private void checkKeys() {
			for (Iterator<String> names = root.fieldNames(); names.hasNext(); ) {
				String key = names.next();
				if (key.startsWith("_")) {
					// JSON has no comments, and a policy file whose entries cannot say why
					// they are there is one where the why gets lost. An underscored key is a
					// note. A real typo still fails: 'rootElment' is caught, '_rootElement' is
					// obviously deliberate.
					continue;
				}
				String rejection = REJECTED_KEYS.get(key);
				if (rejection != null) {
					throw fail(key, rejection);
				}
				if (!KNOWN_KEYS.contains(key)) {
					throw new Pa11yException("Unknown key '" + key + "' in " + file + "."
							+ System.lineSeparator() + System.lineSeparator()
							+ "Known keys are: " + String.join(", ", new TreeSet<>(KNOWN_KEYS)) + ".");
				}
			}
		}

		private JsonNode value(String key) {
			JsonNode node = root.get(key);
			return node == null || node.isNull() ? null : node;
		}

		private String text(String key) {
			JsonNode node = value(key);
			if (node == null) {
				return null;
			}
			if (!node.isTextual() || node.asText().isBlank()) {
				throw fail(key, "expects a non-empty string.");
			}
			return node.asText().trim();
		}

		private Path path(String key) {
			String text = text(key);
			if (text == null) {
				return null;
			}
			// Relative to the config file, so the file travels with the project it describes
			// and keeps meaning the same thing.
			Path path = Path.of(text);
			Path parent = file.getParent();
			return (path.isAbsolute() || parent == null ? path : parent.resolve(path)).normalize();
		}

		/**
		 * A plain file name. Refused if it is a path, because this one is resolved inside the
		 * reports directory and a setting that could climb out of it is a setting that will.
		 */
		private String fileName(String key) {
			String text = text(key);
			if (text == null) {
				return null;
			}
			if (text.contains("/") || text.contains("\\") || text.contains("..")
					|| text.contains(":") || text.startsWith(".")) {
				throw fail(key, "expects a plain file name, not a path.");
			}
			return text;
		}

		private boolean bool(String key) {
			JsonNode node = value(key);
			if (node == null) {
				return false;
			}
			if (!node.isBoolean()) {
				throw fail(key, "expects true or false.");
			}
			return node.booleanValue();
		}

		private Standard standard() {
			String text = text("standard");
			if (text == null) {
				return null;
			}
			try {
				return Standard.fromWireName(text);
			} catch (IllegalArgumentException e) {
				throw fail("standard", "expects WCAG2A, WCAG2AA or WCAG2AAA, not '" + text + "'.");
			}
		}

		private Duration duration(String key, boolean seconds) {
			JsonNode node = value(key);
			if (node == null) {
				return null;
			}
			if (!node.isIntegralNumber() || node.asLong() < 0) {
				throw fail(key, "expects a whole number of "
						+ (seconds ? "seconds" : "milliseconds") + ", zero or more.");
			}
			return seconds ? Duration.ofSeconds(node.asLong()) : Duration.ofMillis(node.asLong());
		}

		private List<String> strings(String key) {
			JsonNode node = value(key);
			if (node == null) {
				return List.of();
			}
			if (!node.isArray()) {
				throw fail(key, "expects an array of strings.");
			}
			List<String> values = new ArrayList<>(node.size());
			for (JsonNode element : node) {
				if (!element.isTextual() || element.asText().isBlank()) {
					throw fail(key, "expects an array of non-empty strings.");
				}
				values.add(element.asText().trim());
			}
			return values;
		}

		private Pa11yException fail(String key, String detail) {
			return new Pa11yException("'" + key + "' in " + file + " " + detail);
		}
	}

	/**
	 * A config file named rather than found, remembered with its origin so the error can say
	 * where the path came from.
	 *
	 * @param source where the setting came from
	 * @param file   the file it named
	 */
	private record Named(String source, Path file) {
	}
}
