package com.automation.pa11y;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
 * <p>The remaining defaults are Pa11y's own: WCAG2AA, errors only and a sixty second timeout.
 * Scanning is done by HTML CodeSniffer; see {@link #ENGINE}.
 *
 * @param url             the page to load, or {@code null} when scanning the open tab
 * @param scanCurrentPage whether to scan the tab that is already open rather than loading {@code url}
 * @param standard        the standard to test against
 * @param includeWarnings whether warnings are reported as well as errors
 * @param includeNotices  whether notices are reported as well as errors
 * @param timeout         how long the whole scan may take
 * @param waitAfterLoad   how long to wait before inspecting, for slow client rendering
 * @param rootElement     a CSS selector to limit the scan to, or {@code null} for the whole page
 * @param hideElements    a CSS selector for elements to exclude, or {@code null}
 * @param ignore          rule codes or issue types to drop from the results
 * @param actions         Pa11y actions to perform first, e.g. {@code "click element #accept"}
 */
public record ScanRequest(
		String url,
		boolean scanCurrentPage,
		Standard standard,
		boolean includeWarnings,
		boolean includeNotices,
		Duration timeout,
		Duration waitAfterLoad,
		String rootElement,
		String hideElements,
		List<String> ignore,
		List<String> actions) {

	/** Pa11y's own default scan timeout. */
	public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

	/**
	 * The Pa11y runner every scan uses: HTML CodeSniffer, which is what {@link Standard}
	 * applies to. There is no choice of engine, so the combined report can group findings by
	 * rule code and have that mean one thing.
	 */
	public static final String ENGINE = "htmlcs";

	public ScanRequest {
		if (!scanCurrentPage && (url == null || url.isBlank())) {
			throw new IllegalArgumentException(
					"url is required unless the request is for the currently open page");
		}
		standard = Objects.requireNonNullElse(standard, Standard.WCAG2AA);
		timeout = Objects.requireNonNullElse(timeout, DEFAULT_TIMEOUT);
		waitAfterLoad = Objects.requireNonNullElse(waitAfterLoad, Duration.ZERO);
		ignore = ignore == null ? List.of() : List.copyOf(ignore);
		actions = actions == null ? List.of() : List.copyOf(actions);
	}

	/**
	 * @param url the page to scan
	 * @return a scan of {@code url} with every default left alone
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
	 * The same options, pointed at a different page. This is how a suite keeps one policy
	 * across many screens: build the baseline once, then derive a request per page.
	 *
	 * <pre>{@code
	 * private static final ScanRequest POLICY = ScanRequest.currentPage()
	 *         .rootElement("#main")
	 *         .ignore("WCAG2AA.Principle1.Guideline1_4.1_4_3.G18.Fail")
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

	private Builder copyInto(Builder builder) {
		builder.standard(standard)
				.includeWarnings(includeWarnings)
				.includeNotices(includeNotices)
				.timeout(timeout)
				.waitAfterLoad(waitAfterLoad)
				.rootElement(rootElement)
				.hideElements(hideElements);
		builder.ignore.addAll(ignore);
		builder.actions.addAll(actions);
		return builder;
	}

	/** Collects the options for a scan. Not thread-safe; build one per scan. */
	public static final class Builder {

		private final String url;
		private final boolean scanCurrentPage;
		private final List<String> ignore = new ArrayList<>();
		private final List<String> actions = new ArrayList<>();
		private Standard standard = Standard.WCAG2AA;
		private boolean includeWarnings;
		private boolean includeNotices;
		private Duration timeout = DEFAULT_TIMEOUT;
		private Duration waitAfterLoad = Duration.ZERO;
		private String rootElement;
		private String hideElements;

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
		 * Adds a Pa11y action to run before the scan, such as
		 * {@code "click element #cookie-accept"}.
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
			return new ScanRequest(url, scanCurrentPage, standard, includeWarnings, includeNotices,
					timeout, waitAfterLoad, rootElement, hideElements, ignore, actions);
		}
	}
}
