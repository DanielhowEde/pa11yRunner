package com.automation.pa11y;

import com.automation.pa11y.report.CombinedReport;
import com.automation.pa11y.report.HtmlReport;
import com.automation.pa11y.report.PageReport;
import com.automation.pa11y.report.ReportStore;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The three-call accessibility API for a test suite: clear, scan each page, combine.
 *
 * <pre>{@code
 * Pa11yUtility pa11y = new Pa11yUtility();
 * pa11y.clearPa11y();                                  // once, before the suite
 *
 * // after your framework has logged in and navigated:
 * pa11y.scanPa11y(chromeUrl, "Dashboard");
 * pa11y.scanPa11y(chromeUrl, "UserManagement");
 *
 * pa11y.combinePa11y();                                // once, after the suite
 * }</pre>
 *
 * <h2>Where the browser comes from</h2>
 *
 * <p>{@code chromeUrl} is where the caller says the browser is. This library holds no
 * WebDriver and has no Selenium dependency: the suite that calls it owns the session, and all
 * that crosses the boundary is the address of that session's Chrome. Two forms work:
 *
 * <ul>
 * <li>a Selenium Grid session CDP socket, {@code ws://grid:4444/session/<session-id>/se/cdp},
 *     which is the normal case and is built from the Grid address and
 *     {@code ((RemoteWebDriver) driver).getSessionId()} on the caller's side;</li>
 * <li>a Chrome debugger endpoint, {@code http://host:9222}, for a Chrome started by hand
 *     rather than by chromedriver.</li>
 * </ul>
 *
 * <p>The scan attaches to that browser over CDP and inspects <strong>the page it already has
 * open, as it stands</strong>. Nothing is navigated, nothing is reloaded and no second browser
 * is started, so the authenticated session and whatever state the test clicked its way into
 * are what get measured. Pa11y opens its own tab for the work and closes it again; the tab
 * WebDriver is sitting on is handed back as it was found, emulation overrides cleared.
 *
 * <p>Because the URL names the session directly, there is nothing to verify: a wrong or stale
 * address cannot attach to the right browser by accident, it fails. This class never calls
 * anything resembling {@code driver.quit()} for the simple reason that it has no driver.
 *
 * <h2>What the scanner is</h2>
 *
 * <p>Pa11y, running HTML CodeSniffer, which is what the {@code Pa11y} in these method names
 * refers to. Rule codes are HTML CodeSniffer codes such as
 * {@code WCAG2AA.Principle3.Guideline3_1.3_1_1.H57.2}, and they can be put straight into the
 * {@code ignore} list of {@code pa11yConfig.json}. There is no translation layer and no second
 * engine, so an ignore code either applies or is rejected as malformed; it can never be
 * silently dropped.
 *
 * <h2>Settings</h2>
 *
 * <p>From {@link Pa11yConfig} -- a {@code pa11yConfig.json} in the base directory -- so the
 * standard, the root element and the accepted rules are the same for every page in a run. The
 * output directory defaults to {@code ~/target/pa11yReports} and is overridable without a
 * recompile; see {@link Pa11yCli#DEFAULT_REPORTS_DIR}.
 *
 * <h2>Threads</h2>
 *
 * <p>One instance is safe to share across parallel tests. Each scan runs its own Node process
 * and writes its own file, and files are written by rename, so a reader never sees a partial
 * one. The one shared thing is the set of page names already used, which is synchronised:
 * parallel tests must therefore use distinct page names, and a repeat is refused rather than
 * silently overwriting the earlier page. Give each parallel shard its own output directory if
 * two shards genuinely need to scan the same page name.
 */
public final class Pa11yUtility {

	private final Pa11yConfig config;
	private final Path outputDirectory;
	private final Set<String> namesUsed = new LinkedHashSet<>();

	/**
	 * Takes its settings from a discovered {@code pa11yConfig.json}, or the built-in defaults
	 * if there is none.
	 *
	 * @throws Pa11yException if a config file exists and cannot be understood
	 */
	public Pa11yUtility() {
		this(Pa11yConfig.discover());
	}

	/**
	 * @param config the settings to use
	 */
	public Pa11yUtility(Pa11yConfig config) {
		this(config, Pa11yCli.reportsDirectory(config));
	}

	/**
	 * @param config          the settings to use
	 * @param outputDirectory where the per-page files and the combined report go, overriding
	 *                        whatever the config and environment say
	 */
	public Pa11yUtility(Pa11yConfig config, Path outputDirectory) {
		this.config = config == null ? Pa11yConfig.empty() : config;
		this.outputDirectory = outputDirectory.toAbsolutePath().normalize();
	}

	/**
	 * @return where this instance writes
	 */
	public Path outputDirectory() {
		return outputDirectory;
	}

	/**
	 * Empties the output directory, so that a new run cannot inherit a previous one.
	 *
	 * <p>Worth doing precisely because {@link #combinePa11y()} reads whatever it finds: last
	 * week's pages would be folded in silently, and a page since deleted would keep appearing
	 * as a failure.
	 *
	 * <p>Only files this tool wrote are removed -- its per-page JSON, their matching HTML and
	 * the combined report. Anything else in the directory is left alone, symlinks are not
	 * followed, nothing recurses into subdirectories, and a directory that is a filesystem
	 * root or a home directory is refused outright.
	 *
	 * <p>The output directory is created if it is not there yet, so the first run on a fresh
	 * checkout, or after a build has wiped {@code target}, has somewhere to write without
	 * anyone having to make the directory by hand.
	 *
	 * <p>Call once at the start of a suite, not before each page.
	 *
	 * @return the files that were deleted
	 * @throws Pa11yException if the directory cannot be read or a file cannot be deleted
	 */
	public List<Path> clearPa11y() {
		synchronized (namesUsed) {
			namesUsed.clear();
		}
		return new ReportStore(outputDirectory).clean(false).deleted();
	}

	/**
	 * Scans the page the browser already has open and saves it under {@code pageName}.
	 *
	 * <p>Call this once your framework has navigated and the page is ready. Readiness is the
	 * caller's business -- this is the moment you have already waited for, and a sleep here
	 * would be guessing. {@code waitAfterLoad} in the config covers content that arrives from
	 * an XHR after the DOM is otherwise settled.
	 *
	 * <p>A scan that cannot run is still recorded, as a failed page, and then thrown. That is
	 * deliberate: an unreachable browser must not leave a gap in the combined report that
	 * reads like a page with nothing wrong.
	 *
	 * @param chromeUrl where the browser is: a Grid session CDP socket or a Chrome debugger
	 *                  endpoint. Never a page URL, and never navigated to.
	 * @param pageName  what to call this page; becomes {@code <pageName>.json} and labels the
	 *                  page in the combined report
	 * @return what the scan found
	 * @throws IllegalArgumentException if {@code chromeUrl} is blank, or {@code pageName} is
	 *                                 blank, holds a path separator, or has already been used
	 * @throws ScanFailedException      if the scan could not be run
	 * @throws Pa11yException           if the scanner is not installed or the file cannot be
	 *                                  written
	 */
	public ScanResult scanPa11y(String chromeUrl, String pageName) {
		if (chromeUrl == null || chromeUrl.isBlank()) {
			throw new IllegalArgumentException(
					"chromeUrl is required: it is where the browser is, such as"
							+ " ws://grid:4444/session/<session-id>/se/cdp. It is not the page to scan;"
							+ " the scan measures whatever that browser already has open.");
		}
		String name = claim(pageName);

		ScanRequest request = config.currentPage().build();
		Pa11yRunner runner = Pa11yRunner.builder(config)
				.chromeDebuggerUrl(chromeUrl)
				.build();

		ScanResult result;
		try {
			result = runner.scan(request);
		} catch (Pa11yException e) {
			// Pa11yException, not just ScanFailedException: an unreachable debugger endpoint
			// throws the plainer one, and that is the commonest way a scan does not happen.
			// Recorded before rethrowing, so the run has evidence even if the caller swallows
			// this to let the rest of the suite finish.
			new ReportStore(outputDirectory).write(
					PageReport.failed(name, request, kindOf(e), e.getMessage()));
			throw e;
		}

		PageReport page = PageReport.of(name, request, result);
		ReportStore store = new ReportStore(outputDirectory);
		store.write(page);
		HtmlReport.write(
				CombinedReport.from(List.of(page)),
				page.displayTitle() + " accessibility report",
				outputDirectory.resolve(ReportStore.htmlFileName(name)));
		return result;
	}

	/**
	 * Combines every page saved by this run into one self-contained HTML report.
	 *
	 * <p>The report separates rules that fired on more than one page -- usually a single
	 * defect in a shared header, footer or component -- from rules confined to one page, and
	 * lists any scan that did not run at all before either of them. Grouping is by rule code,
	 * never by message or selector.
	 *
	 * <p>The per-page files are left in place.
	 *
	 * @return the report that was written
	 * @throws Pa11yException if there are no pages to combine, or the report cannot be written
	 */
	public Path combinePa11y() {
		List<PageReport> pages = new ReportStore(outputDirectory).readAll();
		if (pages.isEmpty()) {
			// Not an empty report. Someone asked for a consolidation and there was nothing to
			// consolidate, which almost always means the scans never ran.
			throw new Pa11yException("No page reports found in " + outputDirectory + "."
					+ System.lineSeparator()
					+ "Nothing has been written. Check that scanPa11y ran, and that it used the same"
					+ " output directory as this call.");
		}
		return HtmlReport.write(
				CombinedReport.from(pages),
				"Accessibility report",
				outputDirectory.resolve(config.combinedReportName()));
	}

	/**
	 * What kind of failure to record.
	 *
	 * <p>{@link FailureKind#SCAN_ERROR} rather than a guess when the exception does not carry
	 * a kind. Labelling every unkinded failure {@code CHROME_UNREACHABLE} would be right most
	 * of the time and actively misleading the rest, and the message says what happened anyway.
	 */
	private static FailureKind kindOf(Pa11yException failure) {
		return failure instanceof ScanFailedException scan ? scan.kind() : FailureKind.SCAN_ERROR;
	}

	/**
	 * Validates a page name and records that it has been used.
	 *
	 * <p>Refusing a repeat rather than overwriting is the safer way round. Two scans under one
	 * name is almost always a copy-paste in the suite, and the symptom of overwriting is a
	 * report that is quietly missing a page -- the same failure mode as a scan that did not
	 * run, and just as hard to notice.
	 */
	private String claim(String pageName) {
		String name = pageName == null ? "" : pageName.trim();
		if (name.isEmpty()) {
			throw new IllegalArgumentException("pageName is required: it is what the page is saved as.");
		}
		if (name.contains("/") || name.contains("\\") || name.contains("..")
				|| name.contains(":") || name.startsWith(".")) {
			throw new IllegalArgumentException("pageName '" + pageName
					+ "' must be a plain name, not a path. It becomes a file name in "
					+ outputDirectory + ", so a separator, a drive letter or .. is refused.");
		}
		synchronized (namesUsed) {
			if (!namesUsed.add(name)) {
				throw new IllegalArgumentException("pageName '" + name
						+ "' has already been scanned in this run. Overwriting it would drop the"
						+ " earlier page from the report without saying so; give this page its own"
						+ " name, or start a new run with clearPa11y().");
			}
		}
		return name;
	}
}
