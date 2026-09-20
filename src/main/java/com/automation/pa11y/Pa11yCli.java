package com.automation.pa11y;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Command line entry point, for harnesses that would rather run a process than take the jar
 * on their classpath.
 *
 * <p>The exit code carries the outcome, so a shell or a build step can act on it without
 * parsing anything:
 *
 * <ul>
 * <li>{@code 0} -- the scan ran and found no errors</li>
 * <li>{@code 1} -- the scan ran and found errors</li>
 * <li>{@code 2} -- the arguments were wrong</li>
 * <li>{@code 3} -- the scan could not be run at all</li>
 * </ul>
 *
 * <p>That last distinction is the point of having separate codes: a broken scanner must not
 * look like a clean page.
 */
public final class Pa11yCli {

	/** The scan ran and found no errors. */
	public static final int EXIT_CLEAN = 0;

	/** The scan ran and found errors. */
	public static final int EXIT_ERRORS_FOUND = 1;

	/** The arguments were wrong. */
	public static final int EXIT_USAGE = 2;

	/** The scan could not be run. */
	public static final int EXIT_SCAN_FAILED = 3;

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private Pa11yCli() {
	}

	/**
	 * @param args the command line
	 */
	public static void main(String[] args) {
		System.exit(run(args, System.out, System.err));
	}

	/**
	 * The CLI without the {@code System.exit}, so it can be tested.
	 *
	 * @param args   the command line
	 * @param out    where the JSON result goes
	 * @param err    where progress and errors go
	 * @return the exit code
	 */
	public static int run(String[] args, PrintStream out, PrintStream err) {
		Arguments arguments;
		try {
			arguments = Arguments.parse(args);
		} catch (IllegalArgumentException e) {
			err.println(e.getMessage());
			err.println();
			err.println(usage());
			return EXIT_USAGE;
		}

		if (arguments.help) {
			out.println(usage());
			return EXIT_CLEAN;
		}

		Pa11yRunner runner;
		try {
			Pa11yRunner.Builder builder = Pa11yRunner.builder().debug(arguments.debug);
			if (arguments.chromeUrl != null) {
				builder.chromeDebuggerUrl(arguments.chromeUrl);
			}
			if (arguments.scannerDir != null) {
				builder.scannerDirectory(arguments.scannerDir);
			}
			runner = builder.build();
		} catch (Pa11yException e) {
			err.println(e.getMessage());
			return EXIT_SCAN_FAILED;
		}

		ScanResult result;
		try {
			result = runner.scan(arguments.toRequest());
		} catch (Pa11yException e) {
			err.println(e.getMessage());
			return EXIT_SCAN_FAILED;
		}

		String json = toJson(result);
		if (arguments.outputFile != null) {
			try {
				Files.writeString(arguments.outputFile, json, StandardCharsets.UTF_8);
				err.println("Wrote " + arguments.outputFile.toAbsolutePath());
			} catch (IOException e) {
				err.println("Could not write " + arguments.outputFile + ": " + e.getMessage());
				return EXIT_SCAN_FAILED;
			}
		} else {
			out.println(json);
		}

		err.printf("%s -- %d error(s), %d warning(s), %d notice(s) in %dms%n",
				result.pageUrl(),
				result.errors().size(),
				result.warnings().size(),
				result.notices().size(),
				result.duration().toMillis());

		return result.hasErrors() ? EXIT_ERRORS_FOUND : EXIT_CLEAN;
	}

	/**
	 * Renders the result as JSON. Built by hand rather than by reflecting over the record,
	 * so that the on-disk shape stays put even if the record is refactored.
	 *
	 * @param result what was found
	 * @return the JSON
	 */
	private static String toJson(ScanResult result) {
		Map<String, Object> document = new LinkedHashMap<>();
		document.put("requestedUrl", result.requestedUrl());
		document.put("pageUrl", result.pageUrl());
		document.put("documentTitle", result.documentTitle());
		document.put("durationMillis", result.duration().toMillis());
		document.put("errorCount", result.errors().size());
		document.put("warningCount", result.warnings().size());
		document.put("noticeCount", result.notices().size());

		List<Map<String, Object>> issues = new ArrayList<>();
		for (Issue issue : result.issues()) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("code", issue.code());
			entry.put("type", issue.type().wireName());
			entry.put("message", issue.message());
			entry.put("selector", issue.selector());
			entry.put("context", issue.context());
			entry.put("engine", issue.engine());
			issues.add(entry);
		}
		document.put("issues", issues);

		try {
			return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(document);
		} catch (IOException e) {
			throw new Pa11yException("Could not render the result as JSON.", e);
		}
	}

	/**
	 * @return the help text
	 */
	private static String usage() {
		return """
				Runs Pa11y against a page in an already-running Chrome, reached over CDP.

				Usage:
				  pa11y-runner --url <url> [--chrome <endpoint>] [options]

				Required:
				  --url <url>              The page to scan.

				Connection:
				  --chrome <endpoint>      Chrome's debugger: http://grid-node-3:9222, grid-node-3:9222,
				                           or a ws:// URL. Defaults to the pa11y.chrome.url system
				                           property or the PA11Y_CHROME_URL environment variable.
				  --scanner-dir <path>     Directory holding the scanner's node_modules. Defaults to
				                           the PA11Y_SCANNER_DIR environment variable, else a search
				                           for a 'scanner' directory from the working directory up.

				Scan options:
				  --standard <name>        WCAG2A, WCAG2AA (default) or WCAG2AAA.
				  --engine <name>          htmlcs (default) or axe. Repeatable.
				  --include-warnings       Report warnings as well as errors.
				  --include-notices        Report notices as well as errors.
				  --timeout <seconds>      Scan timeout. Default 60.
				  --wait <millis>          Wait this long after load before scanning. Default 0.
				  --viewport <WxH>         Viewport size. Default 1280x1024.
				  --root-element <css>     Scan only this part of the page.
				  --hide-elements <css>    Exclude these elements.
				  --ignore <code>          Drop issues with this code or type. Repeatable.
				  --header <name:value>    Extra request header. Repeatable.
				  --action <action>        A Pa11y action to run first, e.g. "click element #accept".
				                           Repeatable.

				Output:
				  --out <path>             Write the JSON result here instead of to stdout.
				  --debug                  Log what the scanner is doing.
				  --help                   Show this text.

				Exit codes:
				  0  scan ran, no errors      2  bad arguments
				  1  scan ran, errors found   3  scan could not run""";
	}

	/** The parsed command line. */
	private static final class Arguments {

		private String url;
		private String chromeUrl;
		private Path scannerDir;
		private Standard standard = Standard.WCAG2AA;
		private final List<ScanEngine> engines = new ArrayList<>();
		private boolean includeWarnings;
		private boolean includeNotices;
		private Duration timeout = ScanRequest.DEFAULT_TIMEOUT;
		private Duration wait = Duration.ZERO;
		private int viewportWidth = 1280;
		private int viewportHeight = 1024;
		private String rootElement;
		private String hideElements;
		private final List<String> ignore = new ArrayList<>();
		private final Map<String, String> headers = new LinkedHashMap<>();
		private final List<String> actions = new ArrayList<>();
		private Path outputFile;
		private boolean debug;
		private boolean help;

		/**
		 * @param args the command line
		 * @return the parsed arguments
		 */
		static Arguments parse(String[] args) {
			Arguments parsed = new Arguments();
			for (int i = 0; i < args.length; i++) {
				String flag = args[i];
				switch (flag) {
					case "--help", "-h" -> parsed.help = true;
					case "--include-warnings" -> parsed.includeWarnings = true;
					case "--include-notices" -> parsed.includeNotices = true;
					case "--debug" -> parsed.debug = true;
					case "--url" -> parsed.url = value(args, ++i, flag);
					case "--chrome" -> parsed.chromeUrl = value(args, ++i, flag);
					case "--scanner-dir" -> parsed.scannerDir = Path.of(value(args, ++i, flag));
					case "--standard" -> parsed.standard = standard(value(args, ++i, flag));
					case "--engine" -> parsed.engines.add(engine(value(args, ++i, flag)));
					case "--timeout" -> parsed.timeout = Duration.ofSeconds(number(value(args, ++i, flag), flag));
					case "--wait" -> parsed.wait = Duration.ofMillis(number(value(args, ++i, flag), flag));
					case "--viewport" -> parsed.viewport(value(args, ++i, flag));
					case "--root-element" -> parsed.rootElement = value(args, ++i, flag);
					case "--hide-elements" -> parsed.hideElements = value(args, ++i, flag);
					case "--ignore" -> parsed.ignore.add(value(args, ++i, flag));
					case "--header" -> parsed.header(value(args, ++i, flag));
					case "--action" -> parsed.actions.add(value(args, ++i, flag));
					case "--out" -> parsed.outputFile = Path.of(value(args, ++i, flag));
					default -> throw new IllegalArgumentException("Unknown option: " + flag);
				}
			}
			if (!parsed.help && (parsed.url == null || parsed.url.isBlank())) {
				throw new IllegalArgumentException("--url is required.");
			}
			return parsed;
		}

		/**
		 * @return the arguments as a scan request
		 */
		ScanRequest toRequest() {
			ScanRequest.Builder builder = ScanRequest.forUrl(url)
					.standard(standard)
					.includeWarnings(includeWarnings)
					.includeNotices(includeNotices)
					.timeout(timeout)
					.waitAfterLoad(wait)
					.viewport(viewportWidth, viewportHeight)
					.rootElement(rootElement)
					.hideElements(hideElements)
					.headers(headers)
					.actions(actions.toArray(String[]::new))
					.ignore(ignore.toArray(String[]::new));
			if (!engines.isEmpty()) {
				builder.engines(engines);
			}
			return builder.build();
		}

		/**
		 * @param specification a {@code WxH} pair
		 */
		private void viewport(String specification) {
			String[] parts = specification.toLowerCase().split("x", 2);
			if (parts.length != 2) {
				throw new IllegalArgumentException("--viewport expects WIDTHxHEIGHT, e.g. 1280x1024");
			}
			viewportWidth = (int) number(parts[0], "--viewport");
			viewportHeight = (int) number(parts[1], "--viewport");
		}

		/**
		 * @param specification a {@code Name: value} pair
		 */
		private void header(String specification) {
			int separator = specification.indexOf(':');
			if (separator <= 0) {
				throw new IllegalArgumentException("--header expects NAME:VALUE, e.g. \"Authorization: Bearer abc\"");
			}
			headers.put(specification.substring(0, separator).trim(), specification.substring(separator + 1).trim());
		}

		/**
		 * @param args the command line
		 * @param index where the value should be
		 * @param flag the option being read
		 * @return the value
		 */
		private static String value(String[] args, int index, String flag) {
			if (index >= args.length) {
				throw new IllegalArgumentException(flag + " needs a value.");
			}
			return args[index];
		}

		/**
		 * @param value the raw text
		 * @param flag  the option being read
		 * @return it as a number
		 */
		private static long number(String value, String flag) {
			try {
				return Long.parseLong(value.trim());
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException(flag + " expects a number, got '" + value + "'.");
			}
		}

		/**
		 * @param value the raw text
		 * @return the matching standard
		 */
		private static Standard standard(String value) {
			for (Standard candidate : Standard.values()) {
				if (candidate.wireName().equalsIgnoreCase(value)) {
					return candidate;
				}
			}
			throw new IllegalArgumentException("Unknown standard '" + value + "'. Use WCAG2A, WCAG2AA or WCAG2AAA.");
		}

		/**
		 * @param value the raw text
		 * @return the matching engine
		 */
		private static ScanEngine engine(String value) {
			for (ScanEngine candidate : ScanEngine.values()) {
				if (candidate.wireName().equalsIgnoreCase(value)) {
					return candidate;
				}
			}
			throw new IllegalArgumentException("Unknown engine '" + value + "'. Use htmlcs or axe.");
		}
	}
}
