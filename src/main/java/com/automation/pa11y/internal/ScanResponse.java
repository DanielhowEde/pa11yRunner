package com.automation.pa11y.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * The JSON the scanner script writes, mapped one-to-one.
 *
 * <p>Kept separate from {@link com.automation.pa11y.ScanResult} so that the shape on the
 * wire can change without dragging the public API along with it. Unknown fields are ignored
 * so that a newer scanner script never breaks an older jar.
 *
 * @param status         {@code COMPLETED} or {@code FAILED}
 * @param failureKind    why it failed; only set when {@code status} is {@code FAILED}
 * @param message        detail about a failure
 * @param documentTitle  the page's title
 * @param pageUrl        the URL actually scanned
 * @param durationMillis how long the scan took
 * @param issues         what was found
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScanResponse(
		String status,
		String failureKind,
		String message,
		String documentTitle,
		String pageUrl,
		long durationMillis,
		List<WireIssue> issues) {

	/** The status the script reports for a scan that ran, however many issues it found. */
	public static final String COMPLETED = "COMPLETED";

	/**
	 * @return {@code true} if the scan ran
	 */
	public boolean completed() {
		return COMPLETED.equalsIgnoreCase(status);
	}

	/**
	 * @return the issues, never {@code null}
	 */
	public List<WireIssue> issuesOrEmpty() {
		return issues == null ? List.of() : issues;
	}

	/**
	 * One issue as the script reports it.
	 *
	 * @param code         the rule identifier
	 * @param type         the severity, as a string
	 * @param typeCode     Pa11y's numeric severity
	 * @param message      the description
	 * @param context      the offending HTML
	 * @param selector     a CSS selector for the element
	 * @param runner       the engine that found it
	 * @param runnerExtras engine-specific detail
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record WireIssue(
			String code,
			String type,
			int typeCode,
			String message,
			String context,
			String selector,
			String runner,
			Map<String, Object> runnerExtras) {
	}
}
