package com.automation.pa11y;

import com.automation.pa11y.internal.ChromeEndpoint;
import com.automation.pa11y.internal.NodeScanner;
import com.automation.pa11y.internal.ScanResponse;
import com.automation.pa11y.internal.ScannerLocation;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs Pa11y against a page in a Chrome that something else is already driving.
 *
 * <p>The browser is not launched here. It is the one on the Selenium Grid node, started
 * with a remote debugging port, and the scan attaches to it over CDP. That means the scan
 * sees the same session the test suite established -- cookies and all -- and that no second
 * browser has to be installed alongside the tests.
 *
 * <p>Pa11y opens its own tab, scans in it, and closes it again. The tab WebDriver is sitting
 * on is never touched, so a scan can safely run in the middle of a test.
 *
 * <h2>From a test harness</h2>
 *
 * <pre>{@code
 * // once, e.g. in a base class or a @BeforeAll
 * Pa11yRunner pa11y = Pa11yRunner.builder()
 *         .chromeDebuggerUrl("http://grid-node-3:9222")
 *         .build();
 *
 * // in a test, after the page under test is on screen
 * ScanResult result = pa11y.scan(driver.getCurrentUrl());
 * assertTrue(result.errors().isEmpty(), result::describeErrors);
 * }</pre>
 *
 * <p>Instances are immutable and safe to share between threads. Each scan runs its own Node
 * process, so parallel tests each get their own; the shared Chrome is the limit on how many
 * are worth running at once.
 */
public final class Pa11yRunner {

	/** System property naming the Chrome debugger endpoint. */
	public static final String CHROME_URL_PROPERTY = "pa11y.chrome.url";

	/** Environment variable naming the Chrome debugger endpoint. */
	public static final String CHROME_URL_ENV = "PA11Y_CHROME_URL";

	/** System property naming the {@code node} binary to run. */
	public static final String NODE_PROPERTY = "pa11y.node.executable";

	/** System property that turns on scanner logging. */
	public static final String DEBUG_PROPERTY = "pa11y.debug";

	private final String chromeDebuggerUrl;
	private final Duration endpointTimeout;
	private final NodeScanner scanner;

	private Pa11yRunner(Builder builder, Path modulesDir) {
		this.chromeDebuggerUrl = builder.chromeDebuggerUrl;
		this.endpointTimeout = builder.endpointTimeout;
		this.scanner = new NodeScanner(builder.nodeExecutable, modulesDir, builder.debug);
	}

	/**
	 * @return a builder, pre-filled from system properties and the environment where they are set
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Scans the page Chrome already has open, as it stands.
	 *
	 * <p>This is the one to call once the suite has navigated: nothing is reloaded, so the
	 * page is measured in whatever state the test left it, and no URL has to be threaded
	 * through. Defaults are WCAG2AA, HTML CodeSniffer, errors only.
	 *
	 * @return what was found
	 * @throws ScanFailedException if the scan could not be run
	 */
	public ScanResult scanCurrentPage() {
		return scan(ScanRequest.currentPage().build());
	}

	/**
	 * Loads a URL in a new tab, scans it and closes the tab.
	 *
	 * @param url the page to scan
	 * @return what was found
	 * @throws ScanFailedException if the scan could not be run
	 */
	public ScanResult scan(String url) {
		return scan(ScanRequest.of(url));
	}

	/**
	 * Scans a page.
	 *
	 * @param request the page and the options to scan it with
	 * @return what was found
	 * @throws ScanFailedException if the scan could not be run
	 */
	public ScanResult scan(ScanRequest request) {
		// Resolved per scan rather than cached: on a Grid the browser comes and goes, and a
		// stale debugger URL would fail in a way that looks like a network problem.
		String webSocketUrl = ChromeEndpoint.resolveWebSocketUrl(chromeDebuggerUrl, endpointTimeout);

		ScanResponse response = scanner.run(request, webSocketUrl);
		if (!response.completed()) {
			throw new ScanFailedException(
					FailureKind.fromWireName(response.failureKind()),
					request.target(),
					response.message() == null ? "no detail given" : response.message());
		}
		return toResult(request, response);
	}

	/**
	 * @param request  what was asked for
	 * @param response what the scanner reported
	 * @return the public result
	 */
	private static ScanResult toResult(ScanRequest request, ScanResponse response) {
		List<Issue> issues = new ArrayList<>(response.issuesOrEmpty().size());
		for (ScanResponse.WireIssue wire : response.issuesOrEmpty()) {
			issues.add(new Issue(
					wire.code(),
					IssueType.fromWireName(wire.type()),
					wire.typeCode(),
					wire.message(),
					wire.context(),
					wire.selector(),
					wire.runner(),
					wire.runnerExtras()));
		}
		return new ScanResult(
				// Scanning the open tab means the URL was not known until Chrome was asked.
				request.scanCurrentPage() ? response.pageUrl() : request.url(),
				response.pageUrl(),
				response.documentTitle(),
				Duration.ofMillis(response.durationMillis()),
				issues);
	}

	/** Collects the settings shared by every scan. */
	public static final class Builder {

		private String chromeDebuggerUrl = firstSet(System.getProperty(CHROME_URL_PROPERTY), System.getenv(CHROME_URL_ENV));
		private String nodeExecutable = firstSet(System.getProperty(NODE_PROPERTY), System.getenv("PA11Y_NODE"), "node");
		private Path scannerDirectory;
		private Duration endpointTimeout = Duration.ofSeconds(10);
		private boolean debug = Boolean.getBoolean(DEBUG_PROPERTY);

		private Builder() {
		}

		/**
		 * Where Chrome's remote debugger is listening.
		 *
		 * <p>Accepts {@code http://grid-node-3:9222}, {@code grid-node-3:9222}, a bare host
		 * or IP (port 9222 assumed), or an already-resolved {@code ws://} URL such as the
		 * {@code se:cdp} capability from a Selenium session.
		 *
		 * @param chromeDebuggerUrl the endpoint
		 * @return this builder
		 */
		public Builder chromeDebuggerUrl(String chromeDebuggerUrl) {
			this.chromeDebuggerUrl = chromeDebuggerUrl;
			return this;
		}

		/**
		 * The directory holding the scanner's {@code node_modules}.
		 *
		 * <p>Optional. When it is not set, the {@code pa11y.scanner.dir} system property and
		 * {@code PA11Y_SCANNER_DIR} environment variable are consulted, then a {@code scanner}
		 * directory is looked for from the working directory upwards.
		 *
		 * @param scannerDirectory the scanner directory
		 * @return this builder
		 */
		public Builder scannerDirectory(Path scannerDirectory) {
			this.scannerDirectory = scannerDirectory;
			return this;
		}

		/**
		 * @param nodeExecutable the {@code node} binary to run; defaults to {@code node} on the PATH
		 * @return this builder
		 */
		public Builder nodeExecutable(String nodeExecutable) {
			this.nodeExecutable = nodeExecutable;
			return this;
		}

		/**
		 * @param endpointTimeout how long to allow for looking up Chrome's debugger URL
		 * @return this builder
		 */
		public Builder endpointTimeout(Duration endpointTimeout) {
			this.endpointTimeout = endpointTimeout;
			return this;
		}

		/**
		 * Makes the scanner log what it is doing. The output is only shown when a scan
		 * fails, as part of the exception message.
		 *
		 * @param debug whether to log
		 * @return this builder
		 */
		public Builder debug(boolean debug) {
			this.debug = debug;
			return this;
		}

		/**
		 * @return the runner
		 * @throws Pa11yException if no installed scanner can be found
		 */
		public Pa11yRunner build() {
			// Located now rather than at the first scan, so a missing npm install fails in
			// setup where it is obvious, not in the middle of a test where it looks flaky.
			Path modulesDir = ScannerLocation.discoverModulesDir(scannerDirectory);
			return new Pa11yRunner(this, modulesDir);
		}

		/**
		 * @param values candidate settings, most specific first
		 * @return the first that is set and not blank, or {@code null}
		 */
		private static String firstSet(String... values) {
			for (String value : values) {
				if (value != null && !value.isBlank()) {
					return value;
				}
			}
			return null;
		}
	}
}
