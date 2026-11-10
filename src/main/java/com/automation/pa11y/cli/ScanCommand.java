package com.automation.pa11y.cli;

import com.automation.pa11y.FailureKind;
import com.automation.pa11y.Pa11yCli;
import com.automation.pa11y.Pa11yConfig;
import com.automation.pa11y.Pa11yException;
import com.automation.pa11y.Pa11yRunner;
import com.automation.pa11y.ScanFailedException;
import com.automation.pa11y.ScanRequest;
import com.automation.pa11y.ScanResult;
import com.automation.pa11y.Standard;
import com.automation.pa11y.report.CombinedReport;
import com.automation.pa11y.report.HtmlReport;
import com.automation.pa11y.report.PageReport;
import com.automation.pa11y.report.ReportStore;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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
			"--chrome", "--name", "--url", "--reports-dir", "--scanner-dir", "--standard",
			"--timeout", "--wait", "--root-element", "--hide-elements", "--ignore", "--action",
			"--config");

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
			return usageError(e, err);
		}

		Pa11yConfig config;
		try {
			config = Pa11yCli.configFor(command);
		} catch (Pa11yException e) {
			// A broken config file is a wrong input rather than a scan that could not run, so
			// it exits 2 like any other argument problem. No usage dump: the message already
			// names the file, the key and what was expected.
			err.println(e.getMessage());
			return Pa11yCli.EXIT_USAGE;
		}

		ScanRequest request;
		Pa11yRunner runner;
		try {
			request = buildRequest(command, config);
			Pa11yRunner.Builder builder = Pa11yRunner.builder(config);
			if (command.has("--debug")) {
				builder.debug(true);
			}
			if (command.has("--chrome")) {
				builder.chromeDebuggerUrl(command.value("--chrome", null));
			}
			if (command.has("--scanner-dir")) {
				builder.scannerDirectory(Path.of(command.value("--scanner-dir", null)));
			}
			runner = builder.build();
		} catch (IllegalArgumentException e) {
			return usageError(e, err);
		} catch (Pa11yException e) {
			err.println(e.getMessage());
			return Pa11yCli.EXIT_SCAN_FAILED;
		}

		Path directory = Pa11yCli.reportsDirectory(command, config);

		ScanResult result;
		try {
			result = runner.scan(request);
		} catch (Pa11yException e) {
			// Recorded as a failed page before giving up, so the report step shows this page as
			// unmeasured rather than leaving a gap that reads like a page with nothing wrong.
			err.println(e.getMessage());
			recordFailure(directory, name, request, e, err);
			return Pa11yCli.EXIT_SCAN_FAILED;
		}

		Path saved;
		try {
			PageReport page = PageReport.of(name, request, result);
			new ReportStore(directory).write(page);
			saved = HtmlReport.write(
					CombinedReport.from(List.of(page)),
					page.displayTitle() + " accessibility report",
					directory.resolve(ReportStore.htmlFileName(name)));
		} catch (Pa11yException e) {
			err.println(e.getMessage());
			return Pa11yCli.EXIT_SCAN_FAILED;
		}

		out.println(saved.toAbsolutePath());
		err.printf("%s: %d error(s), %d warning(s), %d notice(s) on %s in %dms%n",
				name, result.errors().size(), result.warnings().size(), result.notices().size(),
				result.pageUrl(), result.duration().toMillis());

		return command.has("--fail-on-error") && result.hasErrors()
				? Pa11yCli.EXIT_ERRORS_FOUND
				: Pa11yCli.EXIT_CLEAN;
	}

	/**
	 * Starts from the config file and lets the command line override it.
	 *
	 * <p>Each option is applied only when it was actually given, which is the whole point:
	 * setting it unconditionally would mean the flag's own default silently replacing what
	 * the project wrote down, and {@code --standard} would be the only standard anyone could
	 * ever get.
	 *
	 * <p>{@code --ignore} and {@code --action} add to the config's lists instead of replacing
	 * them, and run after them. A page that needs one extra click still needs the cookie
	 * banner clicked first.
	 */
	private static ScanRequest buildRequest(CommandLine command, Pa11yConfig config) {
		// No --url means the page is already on screen, which is the normal case when a suite
		// has navigated and now wants it checked.
		ScanRequest.Builder builder = command.has("--url")
				? config.forUrl(command.value("--url", null))
				: config.currentPage();

		if (command.has("--standard")) {
			builder.standard(Standard.fromWireName(command.value("--standard", null)));
		}
		if (command.has("--include-warnings")) {
			builder.includeWarnings(true);
		}
		if (command.has("--include-notices")) {
			builder.includeNotices(true);
		}
		if (command.has("--timeout")) {
			builder.timeout(Duration.ofSeconds(command.number("--timeout", 60)));
		}
		if (command.has("--wait")) {
			builder.waitAfterLoad(Duration.ofMillis(command.number("--wait", 0)));
		}
		if (command.has("--root-element")) {
			builder.rootElement(command.value("--root-element", null));
		}
		if (command.has("--hide-elements")) {
			builder.hideElements(command.value("--hide-elements", null));
		}
		return builder
				.ignore(command.all("--ignore").toArray(String[]::new))
				.actions(command.all("--action").toArray(String[]::new))
				.build();
	}

	/**
	 * Saves the fact that this page was not measured.
	 *
	 * <p>Failing to save it is not worth failing the command over: the scan has already
	 * failed, the exit code already says so, and a second error message about the record of
	 * the first one would only bury it.
	 */
	private static void recordFailure(Path directory, String name, ScanRequest request,
			Pa11yException failure, PrintStream err) {
		FailureKind kind = failure instanceof ScanFailedException scan
				? scan.kind()
				: FailureKind.SCAN_ERROR;
		try {
			new ReportStore(directory).write(
					PageReport.failed(name, request, kind, failure.getMessage()));
		} catch (Pa11yException e) {
			err.println("The failure could not be recorded in " + directory + ": " + e.getMessage());
		}
	}

	private static int usageError(IllegalArgumentException failure, PrintStream err) {
		err.println(failure.getMessage());
		err.println();
		err.println(usage());
		return Pa11yCli.EXIT_USAGE;
	}

	/**
	 * @return the help text for this subcommand
	 */
	public static String usage() {
		return """
				pa11y-runner scan -- scan one page and save its HTML report

				Usage:
				  pa11y-runner scan --name <name> [--chrome <endpoint>] [options]

				Scans the page Chrome already has open, which is what you want once your suite has
				navigated there: nothing is reloaded, so the page is measured as the test left it.

				Required:
				  --name <name>            Page name. Becomes <name>.html in the reports directory.

				Connection:
				  --chrome <endpoint>      Selenium Grid session CDP WebSocket, for example
				                           ws://grid:4444/session/${SESSION_ID}/se/cdp. Defaults to
				                           PA11Y_CHROME_URL.
				  --reports-dir <dir>      Where to save. Default ~/target/pa11yReports, or
				                           PA11Y_REPORTS_DIR.
				  --scanner-dir <dir>      Directory holding the scanner's node_modules.
				  --config <file>          Read defaults from this file, instead of looking for a
				                           pa11yConfig.json in the working directory and above.

				What to scan:
				  --url <url>              Load this URL in a new tab instead of scanning the open one.
				                           Your tab is left alone, but any state built up is lost.
				  --root-element <css>     Scan only this part of the page.
				  --hide-elements <css>    Exclude these elements.

				Scan options:
				  --standard <name>        WCAG2A, WCAG2AA (default) or WCAG2AAA.
				  --include-warnings       Report warnings as well as errors.
				  --include-notices        Report notices as well as errors.
				  --timeout <seconds>      Scan timeout. Default 60.
				  --wait <millis>          Wait this long before scanning. Default 0.
				  --ignore <code>          Drop issues with this code or type. Repeatable.
				  --action <action>        A Pa11y action to run first. Repeatable.

				Other:
				  --fail-on-error          Exit 1 when the page has errors. Off by default, because
				                           at scan time the question is whether the scan worked; what
				                           the pages contain is what the report is for.
				  --debug                  Log what the scanner is doing.

				A pa11yConfig.json in the base directory supplies defaults for the scan options, so
				every page in a run is judged by the same policy. Anything given here overrides it;
				--ignore and --action add to its lists rather than replacing them. --chrome is not a
				config setting, because the Grid CDP address is different for every session.""";
	}
}
