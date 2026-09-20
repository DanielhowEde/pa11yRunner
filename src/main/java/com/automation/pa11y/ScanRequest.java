package com.automation.pa11y;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What to scan, and how to scan it.
 *
 * <p>There are two ways to say what to scan, and the difference matters:
 *
 * <ul>
 * <li>{@link #currentPage()} scans the tab that is already open, exactly as it stands. Nothing
 *     is reloaded, so an application that has been clicked into a particular state is measured
 *     in that state. This is the one to use when the suite has already navigated.</li>
 * <li>{@link #forUrl(String)} opens a new tab, loads the URL and closes the tab again. The
 *     suite's own tab is untouched, but the page is loaded fresh, so any state built up by
 *     clicking around is not there.</li>
 * </ul>
 *
 * <p>The remaining defaults are Pa11y's own: WCAG2AA, the HTML CodeSniffer engine, errors only
 * and a sixty second timeout.
 *
 * @param url               the page to load, or {@code null} when scanning the open tab
 * @param scanCurrentPage   whether to scan the tab that is already open rather than loading {@code url}
 * @param standard          the standard to test against; used by HTML CodeSniffer only
 * @param engines           the engines to run
 * @param includeWarnings   whether warnings are reported as well as errors
 * @param includeNotices    whether notices are reported as well as errors
 * @param timeout           how long the whole scan may take
 * @param waitAfterLoad     how long to wait before inspecting, for slow client rendering
 * @param viewportWidth     viewport width in pixels; ignored when scanning the open tab
 * @param viewportHeight    viewport height in pixels; ignored when scanning the open tab
 * @param rootElement       a CSS selector to limit the scan to, or {@code null} for the whole page
 * @param hideElements      a CSS selector for elements to exclude, or {@code null}
 * @param ignore            rule codes or issue types to drop from the results
 * @param headers           extra HTTP headers to send; only applies when loading a URL
 * @param userAgent         the User-Agent to present, or {@code null} to leave it alone
 * @param method            the HTTP method used to load the page
 * @param postData          the request body when {@code method} is POST, or {@code null}
 * @param screenCapture     where to save a screenshot of the scanned page, or {@code null}
 * @param actions           Pa11y actions to perform before scanning, e.g. {@code "click element #accept"}
 */
public record ScanRequest(
		String url,
		boolean scanCurrentPage,
		Standard standard,
		List<ScanEngine> engines,
		boolean includeWarnings,
		boolean includeNotices,
		Duration timeout,
		Duration waitAfterLoad,
		int viewportWidth,
		int viewportHeight,
		String rootElement,
		String hideElements,
		List<String> ignore,
		Map<String, String> headers,
		String userAgent,
		String method,
		String postData,
		Path screenCapture,
		List<String> actions) {

	/** Pa11y's own default scan timeout. */
	public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

	/**
	 * @param url               the page to load, or {@code null} when scanning the open tab
	 * @param scanCurrentPage   whether to scan the tab that is already open
	 * @param standard          the standard to test against
	 * @param engines           the engines to run
	 * @param includeWarnings   whether warnings are reported
	 * @param includeNotices    whether notices are reported
	 * @param timeout           how long the whole scan may take
	 * @param waitAfterLoad     how long to wait before inspecting
	 * @param viewportWidth     viewport width in pixels
	 * @param viewportHeight    viewport height in pixels
	 * @param rootElement       a CSS selector to limit the scan to
	 * @param hideElements      a CSS selector for elements to exclude
	 * @param ignore            rule codes to drop
	 * @param headers           extra HTTP headers
	 * @param userAgent         the User-Agent to present
	 * @param method            the HTTP method
	 * @param postData          the request body
	 * @param screenCapture     where to save a screenshot
	 * @param actions           Pa11y actions to perform first
	 */
	public ScanRequest {
		if (!scanCurrentPage && (url == null || url.isBlank())) {
			throw new IllegalArgumentException(
					"url is required unless the request is for the currently open page");
		}
		standard = Objects.requireNonNullElse(standard, Standard.WCAG2AA);
		engines = engines == null || engines.isEmpty() ? List.of(ScanEngine.HTMLCS) : List.copyOf(engines);
		timeout = Objects.requireNonNullElse(timeout, DEFAULT_TIMEOUT);
		waitAfterLoad = Objects.requireNonNullElse(waitAfterLoad, Duration.ZERO);
		if (viewportWidth <= 0 || viewportHeight <= 0) {
			throw new IllegalArgumentException("viewport dimensions must be positive");
		}
		ignore = ignore == null ? List.of() : List.copyOf(ignore);
		headers = headers == null ? Map.of() : Map.copyOf(headers);
		method = Objects.requireNonNullElse(method, "GET");
		actions = actions == null ? List.of() : List.copyOf(actions);
	}

	/**
	 * A scan of {@code url} with every default left alone.
	 *
	 * @param url the page to scan
	 * @return the request
	 */
	public static ScanRequest of(String url) {
		return forUrl(url).build();
	}

	/**
	 * Loads {@code url} in a new tab, scans it and closes the tab.
	 *
	 * @param url the page to scan
	 * @return a builder already pointed at {@code url}
	 */
	public static Builder forUrl(String url) {
		return new Builder(url, false);
	}

	/**
	 * Scans the tab that is already open, as it stands.
	 *
	 * <p>This is the usual choice when a Selenium suite has navigated to the page under test:
	 * no URL has to be threaded through, and no state is lost to a reload.
	 *
	 * @return a builder for the open tab
	 */
	public static Builder currentPage() {
		return new Builder(null, true);
	}

	/**
	 * @return something to call this target in a message
	 */
	public String target() {
		return scanCurrentPage ? "the page currently open in Chrome" : url;
	}

	/**
	 * @return a builder holding this request's values, for deriving a variant of it
	 */
	public Builder toBuilder() {
		return copyInto(new Builder(url, scanCurrentPage));
	}

	/**
	 * The same options, pointed at a different page.
	 *
	 * <p>This is how a suite keeps one policy across many screens: build the baseline once,
	 * then derive a request per page.
	 *
	 * <pre>{@code
	 * private static final ScanRequest POLICY = ScanRequest.currentPage()
	 *         .engines(ScanEngine.HTMLCS, ScanEngine.AXE)
	 *         .rootElement("#main")
	 *         .build();
	 *
	 * pa11y.scan(POLICY.toBuilder("https://example.com/basket").build());
	 * }</pre>
	 *
	 * @param url the page the derived request should load
	 * @return a builder holding this request's values, pointed at {@code url}
	 */
	public Builder toBuilder(String url) {
		return copyInto(new Builder(url, false));
	}

	/**
	 * @param builder a fresh builder for some target
	 * @return it, with this request's options copied in
	 */
	private Builder copyInto(Builder builder) {
		builder.standard(standard)
				.engines(engines)
				.includeWarnings(includeWarnings)
				.includeNotices(includeNotices)
				.timeout(timeout)
				.waitAfterLoad(waitAfterLoad)
				.viewport(viewportWidth, viewportHeight)
				.rootElement(rootElement)
				.hideElements(hideElements)
				.userAgent(userAgent)
				.screenCapture(screenCapture);
		builder.ignore.addAll(ignore);
		builder.headers.putAll(headers);
		builder.actions.addAll(actions);
		builder.method = method;
		builder.postData = postData;
		return builder;
	}

	/** Collects the options for a scan. Not thread-safe; build one per scan. */
	public static final class Builder {

		private final String url;
		private final boolean scanCurrentPage;
		private final List<String> ignore = new ArrayList<>();
		private final Map<String, String> headers = new LinkedHashMap<>();
		private final List<String> actions = new ArrayList<>();
		private Standard standard = Standard.WCAG2AA;
		private List<ScanEngine> engines = List.of(ScanEngine.HTMLCS);
		private boolean includeWarnings;
		private boolean includeNotices;
		private Duration timeout = DEFAULT_TIMEOUT;
		private Duration waitAfterLoad = Duration.ZERO;
		private int viewportWidth = 1280;
		private int viewportHeight = 1024;
		private String rootElement;
		private String hideElements;
		private String userAgent;
		private String method = "GET";
		private String postData;
		private Path screenCapture;

		private Builder(String url, boolean scanCurrentPage) {
			this.url = url;
			this.scanCurrentPage = scanCurrentPage;
		}

		/**
		 * @param standard the standard to test against
		 * @return this builder
		 */
		public Builder standard(Standard standard) {
			this.standard = standard;
			return this;
		}

		/**
		 * @param engines the engines to run
		 * @return this builder
		 */
		public Builder engines(ScanEngine... engines) {
			return engines(List.of(engines));
		}

		/**
		 * @param engines the engines to run
		 * @return this builder
		 */
		public Builder engines(List<ScanEngine> engines) {
			this.engines = List.copyOf(engines);
			return this;
		}

		/**
		 * @param includeWarnings whether warnings are reported as well as errors
		 * @return this builder
		 */
		public Builder includeWarnings(boolean includeWarnings) {
			this.includeWarnings = includeWarnings;
			return this;
		}

		/**
		 * @param includeNotices whether notices are reported as well as errors
		 * @return this builder
		 */
		public Builder includeNotices(boolean includeNotices) {
			this.includeNotices = includeNotices;
			return this;
		}

		/**
		 * @param timeout how long the whole scan may take
		 * @return this builder
		 */
		public Builder timeout(Duration timeout) {
			this.timeout = timeout;
			return this;
		}

		/**
		 * Time to wait before inspecting the page. Worth setting for an app that renders its
		 * real content after an XHR, where scanning too early measures a spinner.
		 *
		 * @param waitAfterLoad how long to wait
		 * @return this builder
		 */
		public Builder waitAfterLoad(Duration waitAfterLoad) {
			this.waitAfterLoad = waitAfterLoad;
			return this;
		}

		/**
		 * Ignored when scanning the open tab, which keeps the size the browser already has.
		 *
		 * @param width  viewport width in pixels
		 * @param height viewport height in pixels
		 * @return this builder
		 */
		public Builder viewport(int width, int height) {
			this.viewportWidth = width;
			this.viewportHeight = height;
			return this;
		}

		/**
		 * Limits the scan to one part of the page, so that a shared header's known problems do
		 * not show up against every page.
		 *
		 * @param rootElement a CSS selector, or {@code null} for the whole page
		 * @return this builder
		 */
		public Builder rootElement(String rootElement) {
			this.rootElement = rootElement;
			return this;
		}

		/**
		 * @param hideElements a CSS selector for elements to exclude, or {@code null}
		 * @return this builder
		 */
		public Builder hideElements(String hideElements) {
			this.hideElements = hideElements;
			return this;
		}

		/**
		 * Drops matching issues from the results. Takes a rule code
		 * ({@code "WCAG2AA.Principle1.Guideline1_4.1_4_3.G18.Fail"}) or a type
		 * ({@code "warning"}).
		 *
		 * @param codes the codes or types to drop
		 * @return this builder
		 */
		public Builder ignore(String... codes) {
			this.ignore.addAll(List.of(codes));
			return this;
		}

		/**
		 * @param name  the header name
		 * @param value the header value
		 * @return this builder
		 */
		public Builder header(String name, String value) {
			this.headers.put(name, value);
			return this;
		}

		/**
		 * @param headers extra HTTP headers to send
		 * @return this builder
		 */
		public Builder headers(Map<String, String> headers) {
			this.headers.putAll(headers);
			return this;
		}

		/**
		 * @param userAgent the User-Agent to present, or {@code null} to leave it alone
		 * @return this builder
		 */
		public Builder userAgent(String userAgent) {
			this.userAgent = userAgent;
			return this;
		}

		/**
		 * @param method   the HTTP method used to load the page
		 * @param postData the request body, or {@code null}
		 * @return this builder
		 */
		public Builder request(String method, String postData) {
			this.method = method;
			this.postData = postData;
			return this;
		}

		/**
		 * Saves a screenshot of the page as scanned. Handy for working out why a scan saw
		 * something other than what the tester expected.
		 *
		 * @param screenCapture where to write the PNG, or {@code null} for none
		 * @return this builder
		 */
		public Builder screenCapture(Path screenCapture) {
			this.screenCapture = screenCapture;
			return this;
		}

		/**
		 * Adds a Pa11y action to run before the scan, such as
		 * {@code "click element #cookie-accept"} or
		 * {@code "wait for element #results to be visible"}.
		 *
		 * @param actions the actions, in order
		 * @return this builder
		 */
		public Builder actions(String... actions) {
			this.actions.addAll(List.of(actions));
			return this;
		}

		/**
		 * @return the finished request
		 */
		public ScanRequest build() {
			return new ScanRequest(
					url, scanCurrentPage, standard, engines, includeWarnings, includeNotices, timeout,
					waitAfterLoad, viewportWidth, viewportHeight, rootElement, hideElements, ignore,
					headers, userAgent, method, postData, screenCapture, actions);
		}
	}
}
