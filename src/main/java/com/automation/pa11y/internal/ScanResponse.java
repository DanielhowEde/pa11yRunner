package com.automation.pa11y.internal;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * The JSON the scanner script writes, mapped one-to-one.
 *
 * <p>Kept separate from {@link com.automation.pa11y.ScanResult} so that the shape on the
 * wire can change without dragging the public API along with it.
 *
 * <p>Unknown fields are ignored, so a newer scanner script never breaks an older jar. That is
 * configured on the reader in {@link NodeScanner} rather than with {@code @JsonIgnoreProperties}
 * here: the annotation would be this project's only use of jackson-annotations, and depending on
 * a whole artifact for one annotation is not worth it.
 *
 * @param status         {@code COMPLETED} or {@code FAILED}
 * @param failureKind    why it failed; only set when {@code status} is {@code FAILED}
 * @param message        detail about a failure
 * @param documentTitle  the page's title
 * @param pageUrl        the URL actually scanned
 * @param durationMillis how long the scan took
 * @param issues         what was found
 */
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
	 * Unknown fields are ignored, so a newer scanner script never breaks an older jar. Done
	 * here rather than with {@code @JsonIgnoreProperties} on the record, which would make
	 * jackson-annotations a second Jackson artifact to depend on for the sake of one
	 * annotation.
	 */
	private static final ObjectMapper MAPPER = new ObjectMapper()
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

	/**
	 * @param json what the scanner script wrote
	 * @return the parsed response
	 * @throws IOException if it is not readable as a response
	 */
	public static ScanResponse parse(String json) throws IOException {
		return MAPPER.readValue(json, ScanResponse.class);
	}

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
