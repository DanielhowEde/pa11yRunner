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
	public static final String DEFAULT_REPORTS_DIR =
			Path.of(System.getProperty("user.home"), "target", "pa11yReports").toString();

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
	 * <p>Flag, then property, then environment variable, then the config file, then the
	 * built-in default. Every step of that ladder is something someone set deliberately, in
	 * descending order of how specific it is to this one run.
	 *
	 * @param command the parsed command line
	 * @param config  the config file for this run, or {@link Pa11yConfig#empty()}
	 * @return the reports directory
	 */
	public static Path reportsDirectory(CommandLine command, Pa11yConfig config) {
		String fromFlag = command.value("--reports-dir", null);
		return fromFlag == null || fromFlag.isBlank() ? reportsDirectory(config) : Path.of(fromFlag);
	}

	/**
	 * The same ladder without a command line, for a caller using the library directly.
	 *
	 * @param config the config file for this run, or {@link Pa11yConfig#empty()}
	 * @return the reports directory
	 */
	public static Path reportsDirectory(Pa11yConfig config) {
		String configured = System.getProperty(REPORTS_DIR_PROPERTY);
		if (configured == null || configured.isBlank()) {
			configured = System.getenv(REPORTS_DIR_ENV);
		}
		if (configured == null || configured.isBlank()) {
			if (config != null && config.reportsDir() != null) {
				return config.reportsDir();
			}
			configured = DEFAULT_REPORTS_DIR;
		}
		return Path.of(configured);
	}

	/**
	 * Reads the config file for a command, if there is one.
	 *
	 * @param command the parsed command line
	 * @return the config, or {@link Pa11yConfig#empty()} if no file was found
	 * @throws Pa11yException if a named file is missing, or a file was found and cannot be read
	 */
	public static Pa11yConfig configFor(CommandLine command) {
		String named = command.value("--config", null);
		return Pa11yConfig.discover(named == null || named.isBlank() ? null : Path.of(named));
	}

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
				  scan      Scan the page Chrome has open and save a per-page HTML report.
				  clean     Remove reports from a previous run.
				  report    Optionally combine saved pages into one HTML report.

				A typical run:

				  # from your test, once it has navigated:
				  pa11y-runner scan --chrome "ws://grid:4444/session/${SESSION_ID}/se/cdp" \\
				                    --name checkout

				The scan writes ~/target/pa11yReports/checkout.html by default. Use --reports-dir
				or PA11Y_REPORTS_DIR to change the output directory.

				Settings can come from the environment instead of the command line, which is usually
				easier in CI:

				  PA11Y_CHROME_URL       Selenium Grid CDP WebSocket for the session being tested
				  PA11Y_REPORTS_DIR      Where per-page reports go (default ~/target/pa11yReports)
				  PA11Y_SCANNER_DIR      Directory holding the scanner's node_modules
				  PA11Y_CONFIG_FILE      The pa11yConfig.json to read, instead of searching for one

				A pa11yConfig.json in the base directory -- the working directory or one of its
				first five ancestors -- supplies the defaults for a whole project: the standard, the
				root element, the rules already accepted as known. Flags override it. Chrome's
				debugger URL is deliberately not one of its keys: it contains the session id, so it
				is different for every run and has to be passed per scan.

				Exit codes:
				  0  the command did what was asked
				  1  errors were found, and --fail-on-error was given
				  2  the arguments were wrong
				  3  the command could not be carried out

				'pa11y-runner help <command>' explains one command in full.""";
	}
}
