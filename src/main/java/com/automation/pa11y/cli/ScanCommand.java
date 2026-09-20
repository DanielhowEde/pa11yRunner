package com.automation.pa11y.cli;

import com.automation.pa11y.Pa11yCli;
import com.automation.pa11y.Pa11yException;
import com.automation.pa11y.Pa11yRunner;
import com.automation.pa11y.ScanEngine;
import com.automation.pa11y.ScanRequest;
import com.automation.pa11y.ScanResult;
import com.automation.pa11y.Standard;
import com.automation.pa11y.report.PageReport;
import com.automation.pa11y.report.ReportStore;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scans one page and saves it, for a suite that has already navigated there.
 *
 * <p>By default nothing is loaded: the tab Chrome already has open is scanned where it is, so
 * the page is measured in the state the test left it and no URL has to be threaded through.
 * Passing {@code --url} switches to loading that address in a new tab instead.
 */
public final class ScanCommand {

	private static final Set<String> SWITCHES = Set.of(
			"--include-warnings", "--include-notices", "--debug", "--fail-on-error");

	private static final Set<String> OPTIONS = Set.of(
			"--chrome", "--name", "--url", "--reports-dir", "--scanner-dir", "--standard", "--engine",
			"--timeout", "--wait", "--viewport", "--root-element", "--hide-elements", "--ignore",
			"--header", "--action", "--screenshot");

	private ScanCommand() {
	}

	/**
	 * @param args the whole command line
	 * @param out  where the saved file's path is written
	 * @param err  where progress and errors go
	 * @return the exit code
	 */
	public static int run(String[] args, PrintStream out, PrintStream err) {
		CommandLine command;
		String name;
		try {
			command = CommandLine.parse(args, 1, SWITCHES, OPTIONS);
			name = command.require("--name");
		} catch (IllegalArgumentException e) {
			err.println(e.getMessage());
			err.println();
			err.println(usage());
			return Pa11yCli.EXIT_USAGE;
		}

		ScanRequest request;
		Pa11yRunner runner;
		try {
			request = buildRequest(command);
			Pa11yRunner.Builder builder = Pa11yRunner.builder().debug(command.has("--debug"));
			if (command.has("--chrome")) {
				builder.chromeDebuggerUrl(command.value("--chrome", null));
			}
			if (command.has("--scanner-dir")) {
				builder.scannerDirectory(Path.of(command.value("--scanner-dir", null)));
			}
			runner = builder.build();
		} catch (IllegalArgumentException e) {
			err.println(e.getMessage());
			err.println();
			err.println(usage());
			return Pa11yCli.EXIT_USAGE;
		} catch (Pa11yException e) {
			err.println(e.getMessage());
			return Pa11yCli.EXIT_SCAN_FAILED;
		}

		ScanResult result;
		Path saved;
		try {
			result = runner.scan(request);
			ReportStore store = new ReportStore(Pa11yCli.reportsDirectory(command));
			saved = store.write(PageReport.of(name, request, result));
		} catch (Pa11yException e) {
			err.println(e.getMessage());
			return Pa11yCli.EXIT_SCAN_FAILED;
		}

		out.println(saved.toAbsolutePath());
		err.printf("%s: %d error(s), %d warning(s), %d notice(s) on %s in %dms%n",
				name,
				result.errors().size(),
				result.warnings().size(),
				result.notices().size(),
				result.pageUrl(),
				result.duration().toMillis());

		return command.has("--fail-on-error") && result.hasErrors()
				? Pa11yCli.EXIT_ERRORS_FOUND
				: Pa11yCli.EXIT_CLEAN;
	}

	/**
	 * @param command the parsed command line
	 * @return the scan to run
	 */
	private static ScanRequest buildRequest(CommandLine command) {
		// No --url means the page is already on screen, which is the normal case when a suite
		// has navigated and now wants it checked.
		ScanRequest.Builder builder = command.has("--url")
				? ScanRequest.forUrl(command.value("--url", null))
				: ScanRequest.currentPage();

		builder.standard(standard(command.value("--standard", "WCAG2AA")))
				.includeWarnings(command.has("--include-warnings"))
				.includeNotices(command.has("--include-notices"))
				.timeout(Duration.ofSeconds(command.number("--timeout", 60)))
				.waitAfterLoad(Duration.ofMillis(command.number("--wait", 0)))
				.rootElement(command.value("--root-element", null))
				.hideElements(command.value("--hide-elements", null))
				.ignore(command.all("--ignore").toArray(String[]::new))
				.actions(command.all("--action").toArray(String[]::new));

		List<String> engines = command.all("--engine");
		if (!engines.isEmpty()) {
			builder.engines(engines.stream().map(ScanCommand::engine).toList());
		}
		if (command.has("--viewport")) {
			int[] size = viewport(command.value("--viewport", null));
			builder.viewport(size[0], size[1]);
		}
		if (command.has("--screenshot")) {
			builder.screenCapture(Path.of(command.value("--screenshot", null)));
		}
		for (Map.Entry<String, String> header : headers(command).entrySet()) {
			builder.header(header.getKey(), header.getValue());
		}
		return builder.build();
	}

	/**
	 * @param command the parsed command line
	 * @return the headers to send
	 */
	private static Map<String, String> headers(CommandLine command) {
		Map<String, String> headers = new java.util.LinkedHashMap<>();
		for (String specification : command.all("--header")) {
			int separator = specification.indexOf(':');
			if (separator <= 0) {
				throw new IllegalArgumentException(
						"--header expects NAME:VALUE, e.g. \"Authorization: Bearer abc\"");
			}
			headers.put(specification.substring(0, separator).trim(), specification.substring(separator + 1).trim());
		}
		return headers;
	}

	/**
	 * @param specification a {@code WxH} pair
	 * @return the width and height
	 */
	private static int[] viewport(String specification) {
		String[] parts = specification.toLowerCase().split("x", 2);
		if (parts.length != 2) {
			throw new IllegalArgumentException("--viewport expects WIDTHxHEIGHT, e.g. 1280x1024");
		}
		try {
			return new int[] { Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()) };
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("--viewport expects WIDTHxHEIGHT, e.g. 1280x1024");
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

	/**
	 * @return the help text for this subcommand
	 */
	public static String usage() {
		return """
				pa11y-runner scan -- scan one page and save it

				Usage:
				  pa11y-runner scan --name <name> [--chrome <endpoint>] [options]

				Scans the page Chrome already has open, which is what you want once your suite has
				navigated there: nothing is reloaded, so the page is measured as the test left it.

				Required:
				  --name <name>            What to call this page. Becomes <name>.json in the reports
				                           directory, and labels the page in the combined report.

				Connection:
				  --chrome <endpoint>      Chrome's debugger on the Grid node: grid-node-3:9222,
				                           http://grid-node-3:9222, or a ws:// URL. Defaults to the
				                           PA11Y_CHROME_URL environment variable.
				  --reports-dir <dir>      Where to save. Default 'pa11y-reports', or PA11Y_REPORTS_DIR.
				  --scanner-dir <dir>      Directory holding the scanner's node_modules.

				What to scan:
				  --url <url>              Load this URL in a new tab instead of scanning the open one.
				                           Your tab is left alone, but any state built up is lost.
				  --root-element <css>     Scan only this part of the page.
				  --hide-elements <css>    Exclude these elements.

				Scan options:
				  --standard <name>        WCAG2A, WCAG2AA (default) or WCAG2AAA.
				  --engine <name>          htmlcs (default) or axe. Repeatable.
				  --include-warnings       Report warnings as well as errors.
				  --include-notices        Report notices as well as errors.
				  --timeout <seconds>      Scan timeout. Default 60.
				  --wait <millis>          Wait this long before scanning. Default 0.
				  --viewport <WxH>         Only used with --url; the open tab keeps its own size.
				  --ignore <code>          Drop issues with this code or type. Repeatable.
				  --header <name:value>    Extra request header, only used with --url. Repeatable.
				  --action <action>        A Pa11y action to run first. Repeatable.
				  --screenshot <path>      Save a PNG of the page as scanned.

				Other:
				  --fail-on-error          Exit 1 when the page has errors. Off by default, because
				                           at scan time the question is whether the scan worked; what
				                           the pages contain is what the report is for.
				  --debug                  Log what the scanner is doing.""";
	}
}
