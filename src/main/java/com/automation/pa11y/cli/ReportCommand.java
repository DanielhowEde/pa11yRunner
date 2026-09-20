package com.automation.pa11y.cli;

import com.automation.pa11y.IssueType;
import com.automation.pa11y.Pa11yCli;
import com.automation.pa11y.Pa11yException;
import com.automation.pa11y.report.CombinedReport;
import com.automation.pa11y.report.HtmlReport;
import com.automation.pa11y.report.PageReport;
import com.automation.pa11y.report.ReportStore;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Combines every saved page into one HTML report.
 *
 * <p>Run at the end of a suite, once every page has been scanned. The interesting part is not
 * the totals but the split: which rules failed across several pages, and which are stuck to
 * one. The first group is usually a single defect in something shared.
 */
public final class ReportCommand {

	private static final Set<String> SWITCHES = Set.of("--fail-on-error");
	private static final Set<String> OPTIONS = Set.of("--reports-dir", "--out", "--title");

	private ReportCommand() {
	}

	/**
	 * @param args the whole command line
	 * @param out  where the report's path is written
	 * @param err  where the summary and errors go
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

		Path directory = Pa11yCli.reportsDirectory(command);
		Path output = Path.of(command.value("--out", "accessibility-report.html"));
		String title = command.value("--title", "Accessibility report");

		List<PageReport> pages;
		try {
			pages = new ReportStore(directory).readAll();
		} catch (Pa11yException e) {
			err.println(e.getMessage());
			return Pa11yCli.EXIT_SCAN_FAILED;
		}

		if (pages.isEmpty()) {
			// Not a success with an empty report: someone asked for a consolidation and there
			// was nothing to consolidate, which almost always means the scans did not run.
			err.println("No page reports found in " + directory.toAbsolutePath() + "."
					+ System.lineSeparator()
					+ "Nothing has been written. Check that the scan step ran, and that it was "
					+ "pointed at the same --reports-dir as this command.");
			return Pa11yCli.EXIT_SCAN_FAILED;
		}

		CombinedReport combined = CombinedReport.from(pages);
		Path written;
		try {
			written = HtmlReport.write(combined, title, output);
		} catch (Pa11yException e) {
			err.println(e.getMessage());
			return Pa11yCli.EXIT_SCAN_FAILED;
		}

		out.println(written.toAbsolutePath());
		err.printf("%d page(s), %d error(s), %d warning(s). %d rule(s) on more than one page, "
						+ "%d on a single page.%n",
				pages.size(),
				combined.total(IssueType.ERROR),
				combined.total(IssueType.WARNING),
				combined.shared().size(),
				combined.unique().size());

		return command.has("--fail-on-error") && combined.total(IssueType.ERROR) > 0
				? Pa11yCli.EXIT_ERRORS_FOUND
				: Pa11yCli.EXIT_CLEAN;
	}

	/**
	 * @return the help text for this subcommand
	 */
	public static String usage() {
		return """
				pa11y-runner report -- combine the saved pages into one HTML report

				Usage:
				  pa11y-runner report [--out <file>] [--reports-dir <dir>] [--title <title>]

				Reads every page saved by 'scan' and writes a single self-contained HTML file. The
				report separates rules that failed on more than one page -- usually one defect in a
				shared header, footer or component -- from rules confined to a single page.

				Options:
				  --out <file>             Where to write. Default 'accessibility-report.html'.
				  --reports-dir <dir>      Where the saved pages are. Default 'pa11y-reports', or the
				                           PA11Y_REPORTS_DIR environment variable.
				  --title <title>          Heading for the report. Default 'Accessibility report'.
				  --fail-on-error          Exit 1 if any page has errors, for failing a build.

				Exits 3 if there are no saved pages to combine, since that means the scans did not
				run rather than that the site is clean.""";
	}
}
