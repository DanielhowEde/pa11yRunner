package com.automation.pa11y.cli;

import com.automation.pa11y.Pa11yCli;
import com.automation.pa11y.Pa11yException;
import com.automation.pa11y.Pa11yRunner;
import com.automation.pa11y.ScanRequest;
import com.automation.pa11y.ScanResult;
import com.automation.pa11y.Standard;
import com.automation.pa11y.report.PageReport;
import com.automation.pa11y.report.ReportStore;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
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
			"--timeout", "--wait", "--root-element", "--hide-elements", "--ignore", "--action");

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
			return usageError(e, err);
		} catch (Pa11yException e) {
			err.println(e.getMessage());
			return Pa11yCli.EXIT_SCAN_FAILED;
		}

		ScanResult result;
		Path saved;
		try {
			result = runner.scan(request);
			saved = new ReportStore(Pa11yCli.reportsDirectory(command))
					.write(PageReport.of(name, request, result));
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

	private static ScanRequest buildRequest(CommandLine command) {
		// No --url means the page is already on screen, which is the normal case when a suite
		// has navigated and now wants it checked.
		ScanRequest.Builder builder = command.has("--url")
				? ScanRequest.forUrl(command.value("--url", null))
				: ScanRequest.currentPage();

		return builder
				.standard(standard(command.value("--standard", "WCAG2AA")))
				.includeWarnings(command.has("--include-warnings"))
				.includeNotices(command.has("--include-notices"))
				.timeout(Duration.ofSeconds(command.number("--timeout", 60)))
				.waitAfterLoad(Duration.ofMillis(command.number("--wait", 0)))
				.rootElement(command.value("--root-element", null))
				.hideElements(command.value("--hide-elements", null))
				.ignore(command.all("--ignore").toArray(String[]::new))
				.actions(command.all("--action").toArray(String[]::new))
				.build();
	}

	private static int usageError(IllegalArgumentException failure, PrintStream err) {
		err.println(failure.getMessage());
		err.println();
		err.println(usage());
		return Pa11yCli.EXIT_USAGE;
	}

	private static Standard standard(String value) {
		for (Standard candidate : Standard.values()) {
			if (candidate.wireName().equalsIgnoreCase(value)) {
				return candidate;
			}
		}
		throw new IllegalArgumentException("Unknown standard '" + value + "'. Use WCAG2A, WCAG2AA or WCAG2AAA.");
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
				  --debug                  Log what the scanner is doing.""";
	}
}
