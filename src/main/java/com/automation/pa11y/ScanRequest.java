package com.automation.pa11y;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One page to scan, and how to scan it.
 *
 * <p>The defaults are Pa11y's own: WCAG2AA, the HTML CodeSniffer engine, errors only, a
 * sixty second timeout and a 1280x1024 viewport. For the common case that means:
 *
 * <pre>{@code
 * runner.scan("https://example.com/checkout");
 * }</pre>
 *
 * <p>and for anything else:
 *
 * <pre>{@code
 * ScanRequest request = ScanRequest.forUrl("https://example.com/checkout")
 *         .engines(ScanEngine.HTMLCS, ScanEngine.AXE)
 *         .includeWarnings(true)
 *         .waitAfterLoad(Duration.ofMillis(500))
 *         .ignore("WCAG2AA.Principle1.Guideline1_4.1_4_3.G18.Fail")
 *         .build();
 * }</pre>
 *
 * @param url               the page to scan
 * @param standard          the standard to test against; used by HTML CodeSniffer only
 * @param engines           the engines to run
 * @param includeWarnings   whether warnings are reported as well as errors
 * @param includeNotices    whether notices are reported as well as errors
 * @param timeout           how long the whole scan may take
 * @param waitAfterLoad     how long to wait after load before inspecting, for slow client rendering
 * @param viewportWidth     viewport width in pixels
 * @param viewportHeight    viewport height in pixels
 * @param rootElement       a CSS selector to limit the scan to, or {@code null} for the whole page
 * @param hideElements      a CSS selector for elements to exclude, or {@code null}
 * @param ignore            rule codes or issue types to drop from the results
 * @param headers           extra HTTP headers to send with the request
 * @param userAgent         the User-Agent to present, or {@code null} for Pa11y's default
 * @param method            the HTTP method used to load the page
 * @param postData          the request body when {@code method} is POST, or {@code null}
 * @param screenCapture     where to save a screenshot of the scanned page, or {@code null}
 * @param actions           Pa11y actions to perform before scanning, e.g. {@code "click element #cookie-accept"}
 */
public record ScanRequest(
		String url,
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
	 * @param url               the page to scan
	 * @param standard          the standard to test against
	 * @param engines           the engines to run
	 * @param includeWarnings   whether warnings are reported
	 * @param includeNotices    whether notices are reported
	 * @param timeout           how long the whole scan may take
	 * @param waitAfterLoad     how long to wait after load before inspecting
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
		if (url == null || url.isBlank()) {
			throw new IllegalArgumentException("url is required");
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
	 * @param url the page to scan
	 * @return a builder already pointed at {@code url}
	 */
	public static Builder forUrl(String url) {
		return new Builder(url);
	}

	/**
	 * @return a builder holding this request's values, for deriving a variant of it
	 */
	public Builder toBuilder() {
		return toBuilder(url);
	}

	/**
	 * The same options, pointed at a different page.
	 *
	 * <p>This is how a suite keeps one policy across many screens: build the baseline once,
	 * then derive a request per page.
	 *
	 * <pre>{@code
	 * private static final ScanRequest POLICY = ScanRequest.forUrl("about:blank")
	 *         .engines(ScanEngine.HTMLCS, ScanEngine.AXE)
	 *         .rootElement("#main")
	 *         .ignore("WCAG2AA.Principle1.Guideline1_4.1_4_3.G18.Fail")
	 *         .build();
	 *
	 * pa11y.scan(POLICY.toBuilder(driver.getCurrentUrl()).build());
	 * }</pre>
	 *
	 * @param url the page the derived request should scan
	 * @return a builder holding this request's values, pointed at {@code url}
	 */
	public Builder toBuilder(String url) {
		Builder builder = new Builder(url)
				.standard(standard)
				.engines(engines)
				.includeWarnings(includeWarnings)
				.includeNotices(includeNotices)
				.timeout(timeout)
				.waitAfterLoad(waitAfterLoad)
				.viewport(viewportWidth, viewportHeight)
				.rootElement(rootElement)
				.hideElements(hideElements)
				.userAgent(userAgent)
				.postData(postData)
				.screenCapture(screenCapture);
		builder.ignore.addAll(ignore);
		builder.headers.putAll(headers);
		builder.actions.addAll(actions);
		builder.method = method;
		return builder;
	}

	/** Collects the options for a scan. Not thread-safe; build one per scan. */
	public static final class Builder {

		private final String url;
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

		private Builder(String url) {
			this.url = url;
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
		 * Time to wait after the page has loaded before inspecting it. Worth setting for an
		 * app that renders its real content after an XHR, where scanning on load measures a
		 * spinner.
		 *
		 * @param waitAfterLoad how long to wait
		 * @return this builder
		 */
		public Builder waitAfterLoad(Duration waitAfterLoad) {
			this.waitAfterLoad = waitAfterLoad;
			return this;
		}

		/**
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
		 * Limits the scan to one part of the page, so that a shared header's known problems
		 * do not show up against every page.
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
		 * @param userAgent the User-Agent to present, or {@code null} for Pa11y's default
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

		private Builder postData(String postData) {
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
		 * {@code "click element #cookie-accept"} or {@code "wait for element #results to be visible"}.
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
					url, standard, engines, includeWarnings, includeNotices, timeout, waitAfterLoad,
					viewportWidth, viewportHeight, rootElement, hideElements, ignore, headers,
					userAgent, method, postData, screenCapture, actions);
		}
	}
}
