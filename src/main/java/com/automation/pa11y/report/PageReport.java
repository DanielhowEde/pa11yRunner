package com.automation.pa11y.report;

import com.automation.pa11y.Issue;
import com.automation.pa11y.IssueType;
import com.automation.pa11y.ScanRequest;
import com.automation.pa11y.ScanResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One scanned page, as saved to disk.
 *
 * <p>This is the unit the combined report is built from: a run scans several pages, each
 * writing its own file, and the report command reads them all back. Keeping one file per
 * page rather than appending to a shared one means parallel tests cannot corrupt each
 * other's output, and a crashed run still leaves the pages that did finish.
 *
 * @param name           the short name this page was scanned under, used as the file name
 * @param requestedUrl   the URL that was asked for
 * @param pageUrl        the URL actually scanned, after redirects
 * @param documentTitle  the page's title
 * @param scannedAt      when the scan ran
 * @param duration       how long it took
 * @param standard       the standard tested against
 * @param engines        the engines that ran
 * @param issues         what was found
 */
public record PageReport(
		String name,
		String requestedUrl,
		String pageUrl,
		String documentTitle,
		Instant scannedAt,
		Duration duration,
		String standard,
		List<String> engines,
		List<Issue> issues) {

	/**
	 * @param name           the short name this page was scanned under
	 * @param requestedUrl   the URL that was asked for
	 * @param pageUrl        the URL actually scanned
	 * @param documentTitle  the page's title
	 * @param scannedAt      when the scan ran
	 * @param duration       how long it took
	 * @param standard       the standard tested against
	 * @param engines        the engines that ran
	 * @param issues         what was found
	 */
	public PageReport {
		name = Objects.requireNonNullElse(name, "");
		requestedUrl = Objects.requireNonNullElse(requestedUrl, "");
		pageUrl = Objects.requireNonNullElse(pageUrl, "");
		documentTitle = Objects.requireNonNullElse(documentTitle, "");
		scannedAt = Objects.requireNonNullElse(scannedAt, Instant.EPOCH);
		duration = Objects.requireNonNullElse(duration, Duration.ZERO);
		standard = Objects.requireNonNullElse(standard, "");
		engines = engines == null ? List.of() : List.copyOf(engines);
		issues = issues == null ? List.of() : List.copyOf(issues);
	}

	/**
	 * @param name    the name to save this page under
	 * @param request what was asked for
	 * @param result  what came back
	 * @return the report to save
	 */
	public static PageReport of(String name, ScanRequest request, ScanResult result) {
		return new PageReport(
				name,
				result.requestedUrl(),
				result.pageUrl(),
				result.documentTitle(),
				Instant.now(),
				result.duration(),
				request.standard().wireName(),
				request.engines().stream().map(engine -> engine.wireName()).toList(),
				result.issues());
	}

	/**
	 * @param type the severity to count
	 * @return how many issues of that severity this page has
	 */
	public long count(IssueType type) {
		return issues.stream().filter(issue -> issue.type() == type).count();
	}

	/**
	 * @return how many definite failures this page has
	 */
	public long errorCount() {
		return count(IssueType.ERROR);
	}

	/**
	 * @return the page's title, or its name if the title is empty
	 */
	public String displayTitle() {
		return documentTitle.isBlank() ? name : documentTitle;
	}
}
