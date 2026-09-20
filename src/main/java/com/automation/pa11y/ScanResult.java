package com.automation.pa11y;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * What a completed scan found.
 *
 * <p>A {@code ScanResult} always represents a scan that ran. A scan that could not run
 * throws {@link ScanFailedException} instead, so an empty issue list here means a clean
 * page and never a broken scanner.
 *
 * <p>Deciding what fails a build is left to the caller, because that is a project decision
 * rather than a library one:
 *
 * <pre>{@code
 * ScanResult result = runner.scan(url);
 * assertTrue(result.errors().isEmpty(), result::describeErrors);
 * }</pre>
 *
 * @param requestedUrl  the URL that was asked for
 * @param pageUrl       the URL actually scanned, after any redirects
 * @param documentTitle the page's {@code <title>}
 * @param duration      how long the scan took
 * @param issues        every issue found, in the order the engines reported them
 */
public record ScanResult(
		String requestedUrl,
		String pageUrl,
		String documentTitle,
		Duration duration,
		List<Issue> issues) {

	/**
	 * @param requestedUrl  the URL that was asked for
	 * @param pageUrl       the URL actually scanned
	 * @param documentTitle the page's title
	 * @param duration      how long the scan took
	 * @param issues        every issue found
	 */
	public ScanResult {
		requestedUrl = Objects.requireNonNullElse(requestedUrl, "");
		pageUrl = Objects.requireNonNullElse(pageUrl, "");
		documentTitle = Objects.requireNonNullElse(documentTitle, "");
		duration = Objects.requireNonNullElse(duration, Duration.ZERO);
		issues = issues == null ? List.of() : List.copyOf(issues);
	}

	/**
	 * @return only the definite failures
	 */
	public List<Issue> errors() {
		return issuesOfType(IssueType.ERROR);
	}

	/**
	 * @return only the warnings; empty unless the scan asked for them
	 */
	public List<Issue> warnings() {
		return issuesOfType(IssueType.WARNING);
	}

	/**
	 * @return only the notices; empty unless the scan asked for them
	 */
	public List<Issue> notices() {
		return issuesOfType(IssueType.NOTICE);
	}

	/**
	 * @param type the severity to filter by
	 * @return the issues of that severity
	 */
	public List<Issue> issuesOfType(IssueType type) {
		return issues.stream().filter(issue -> issue.type() == type).toList();
	}

	/**
	 * @return {@code true} if the page has at least one error
	 */
	public boolean hasErrors() {
		return !errors().isEmpty();
	}

	/**
	 * How many times each rule fired, most frequent first. Useful for spotting the one
	 * template defect that accounts for ninety findings.
	 *
	 * @return issue counts keyed by rule code
	 */
	public Map<String, Long> countsByCode() {
		return issues.stream().collect(Collectors.groupingBy(
				Issue::code,
				java.util.LinkedHashMap::new,
				Collectors.counting()));
	}

	/**
	 * A multi-line rendering of the errors, intended as an assertion failure message.
	 *
	 * @return the errors, one per line, or a short note if there are none
	 */
	public String describeErrors() {
		List<Issue> errors = errors();
		if (errors.isEmpty()) {
			return "No accessibility errors on " + pageUrl;
		}
		return "%d accessibility error(s) on %s%n%s".formatted(
				errors.size(),
				pageUrl,
				errors.stream().map(issue -> "  - " + issue.describe()).collect(Collectors.joining(System.lineSeparator())));
	}
}
