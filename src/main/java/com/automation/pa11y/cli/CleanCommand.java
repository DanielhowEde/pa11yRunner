package com.automation.pa11y.cli;

import com.automation.pa11y.Pa11yCli;
import com.automation.pa11y.Pa11yException;
import com.automation.pa11y.report.ReportStore;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Set;

/**
 * Empties the reports directory before a run.
 *
 * <p>Worth doing precisely because the combined report reads whatever it finds: leftovers from
 * last week would be silently folded in, and a page that has since been deleted would keep
 * showing up as a failure.
 *
 * <p>Only reports this tool wrote are removed by default, including their HTML output. A
 * reports directory is exactly the sort of place someone also keeps a config file or a
 * fixture, and deleting every {@code .json} in a directory named on the command line is not
 * a thing to do by default.
 */
public final class CleanCommand {

	private static final Set<String> SWITCHES = Set.of("--all", "--quiet");
	private static final Set<String> OPTIONS = Set.of("--reports-dir", "--config");

	private CleanCommand() {
	}

	/**
	 * @param args the whole command line
	 * @param out  where the summary is written
	 * @param err  where errors go
	 * @return the exit code
	 */
	public static int run(String[] args, PrintStream out, PrintStream err) {
		CommandLine command;
		try {
			command = CommandLine.parse(args, 1, SWITCHES, OPTIONS);
		} catch (IllegalArgumentException e) {
			err.println(e.getMessage());
			err.println();
			err.println(usage());
			return Pa11yCli.EXIT_USAGE;
		}

		// Read here too, so that a project whose reportsDir lives in the config file does not
		// have to repeat it on the step that empties that very directory.
		Path directory;
		try {
			directory = Pa11yCli.reportsDirectory(command, Pa11yCli.configFor(command));
		} catch (Pa11yException e) {
			// Exits 2 rather than 3, and the same from every command: a config file that
			// cannot be understood is a wrong input, not a step that could not be carried out.
			err.println(e.getMessage());
			return Pa11yCli.EXIT_USAGE;
		}

		ReportStore.CleanResult result;
		try {
			result = new ReportStore(directory).clean(command.has("--all"));
		} catch (Pa11yException e) {
			err.println(e.getMessage());
			return Pa11yCli.EXIT_SCAN_FAILED;
		}

		if (!command.has("--quiet")) {
			out.printf("Removed %d output file(s) from %s%n", result.deleted().size(), directory.toAbsolutePath());
			if (!result.kept().isEmpty()) {
				err.printf("Left %d other .json file(s) alone; pass --all to remove those too.%n",
						result.kept().size());
			}
		}
		return Pa11yCli.EXIT_CLEAN;
	}

	/**
	 * @return the help text for this subcommand
	 */
	public static String usage() {
		return """
				pa11y-runner clean -- empty the reports directory before a run

				Usage:
				  pa11y-runner clean [--reports-dir <dir>] [--all]

				Options:
				  --reports-dir <dir>      Which directory to clear. Default ~/target/pa11yReports,
				                           the PA11Y_REPORTS_DIR environment variable, or the
				                           reportsDir in pa11yConfig.json.
				  --config <file>          Read defaults from this file, instead of looking for a
				                           pa11yConfig.json in the working directory and above.
				  --all                    Delete every .json in the directory, not just the report
				                           files this tool wrote.
				  --quiet                  Say nothing on success.

				Exits 0 even when there was nothing to delete, so it is safe as the first step of
				every run.""";
	}
}
