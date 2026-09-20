package com.automation.pa11y;

import com.automation.pa11y.cli.CleanCommand;
import com.automation.pa11y.cli.CommandLine;
import com.automation.pa11y.cli.ReportCommand;
import com.automation.pa11y.cli.ScanCommand;

import java.io.PrintStream;
import java.nio.file.Path;

/**
 * Command line entry point, for a harness that drives this as a process rather than on its
 * classpath.
 *
 * <p>Three subcommands, matching the three moments in a run:
 *
 * <pre>
 * pa11y-runner clean                          once, before the suite starts
 * pa11y-runner scan   --name checkout ...     after each page the suite navigates to
 * pa11y-runner report --out report.html       once, after the suite finishes
 * </pre>
 *
 * <p>The exit code carries the outcome, so nothing has to be parsed to act on it. Note that a
 * scan which ran exits {@code 0} whatever it found: at scan time the question is whether the
 * scan worked, not whether the page is perfect. Pass {@code --fail-on-error} to the scan or
 * report step to turn findings into a non-zero exit.
 */
public final class Pa11yCli {

	/** The command did what was asked. */
	public static final int EXIT_CLEAN = 0;

	/** Errors were found, and {@code --fail-on-error} was given. */
	public static final int EXIT_ERRORS_FOUND = 1;

	/** The arguments were wrong. */
	public static final int EXIT_USAGE = 2;

	/** The command could not be carried out. */
	public static final int EXIT_SCAN_FAILED = 3;

	/** Where reports go when nothing says otherwise. */
	public static final String DEFAULT_REPORTS_DIR = "pa11y-reports";

	/** Environment variable naming the reports directory. */
	public static final String REPORTS_DIR_ENV = "PA11Y_REPORTS_DIR";

	/** System property naming the reports directory. */
	public static final String REPORTS_DIR_PROPERTY = "pa11y.reports.dir";

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
	 * @param args the command line
	 * @param out  where results go
	 * @param err  where progress and errors go
	 * @return the exit code
	 */
	public static int run(String[] args, PrintStream out, PrintStream err) {
		if (args.length == 0) {
			err.println(usage());
			return EXIT_USAGE;
		}

		return switch (args[0]) {
			case "scan" -> ScanCommand.run(args, out, err);
			case "clean", "cleardown" -> CleanCommand.run(args, out, err);
			case "report" -> ReportCommand.run(args, out, err);
			case "help", "--help", "-h" -> help(args, out);
			default -> unknown(args[0], err);
		};
	}

	/**
	 * Works out where the per-page files live.
	 *
	 * @param command the parsed command line
	 * @return the reports directory
	 */
	public static Path reportsDirectory(CommandLine command) {
		String configured = command.value("--reports-dir", null);
		if (configured == null || configured.isBlank()) {
			configured = System.getProperty(REPORTS_DIR_PROPERTY);
		}
		if (configured == null || configured.isBlank()) {
			configured = System.getenv(REPORTS_DIR_ENV);
		}
		if (configured == null || configured.isBlank()) {
			configured = DEFAULT_REPORTS_DIR;
		}
		return Path.of(configured);
	}

	/**
	 * @param args the command line, where a subcommand may follow {@code help}
	 * @param out  where the help goes
	 * @return the exit code
	 */
	private static int help(String[] args, PrintStream out) {
		String topic = args.length > 1 ? args[1] : "";
		out.println(switch (topic) {
			case "scan" -> ScanCommand.usage();
			case "clean", "cleardown" -> CleanCommand.usage();
			case "report" -> ReportCommand.usage();
			default -> usage();
		});
		return EXIT_CLEAN;
	}

	/**
	 * @param command what was typed
	 * @param err     where the complaint goes
	 * @return the exit code
	 */
	private static int unknown(String command, PrintStream err) {
		err.println("Unknown command: " + command);
		err.println();
		err.println(usage());
		return EXIT_USAGE;
	}

	/**
	 * @return the top-level help text
	 */
	public static String usage() {
		return """
				pa11y-runner -- accessibility scanning for a Selenium Grid suite

				Runs Pa11y inside the Chrome your Grid is already driving, so the scan sees the page
				your test navigated to, signed in and in the state the test left it.

				Commands:
				  clean     Empty the reports directory. Run once, before the suite starts.
				  scan      Scan the page Chrome has open and save it. Run after each page.
				  report    Combine the saved pages into one HTML report. Run once, at the end.

				A typical run:

				  pa11y-runner clean --reports-dir pa11y-reports

				  # from your test, once it has navigated:
				  pa11y-runner scan --chrome grid-node-3:9222 --name checkout \\
				                    --reports-dir pa11y-reports

				  pa11y-runner report --reports-dir pa11y-reports --out accessibility-report.html

				Settings can come from the environment instead of the command line, which is usually
				easier in CI:

				  PA11Y_CHROME_URL       Chrome's debugger, e.g. http://grid-node-3:9222
				  PA11Y_REPORTS_DIR      Where the per-page files go
				  PA11Y_SCANNER_DIR      Directory holding the scanner's node_modules

				Exit codes:
				  0  the command did what was asked
				  1  errors were found, and --fail-on-error was given
				  2  the arguments were wrong
				  3  the command could not be carried out

				'pa11y-runner help <command>' explains one command in full.""";
	}
}
