package com.automation.pa11y.report;

import com.automation.pa11y.FailureKind;
import com.automation.pa11y.Issue;
import com.automation.pa11y.IssueType;
import com.automation.pa11y.ScanRequest;
import com.automation.pa11y.ScanResult;
import com.automation.pa11y.internal.SnippetRedaction;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
 * <p>A page is saved whether or not its scan worked. {@link ScanStatus#FAILED} records a scan
 * that could not run, with no issues and a reason: see {@link #failed}.
 *
 * @param name           the short name this page was scanned under, used as the file name
 * @param requestedUrl   the URL that was asked for
 * @param pageUrl        the URL actually scanned, after redirects
 * @param documentTitle  the page's title
 * @param scannedAt      when the scan ran
 * @param duration       how long it took
 * @param standard       the standard tested against
 * @param engines        which Pa11y runners produced these issues, recorded for provenance
 * @param issues         what was found, empty for a failed scan
 * @param status         whether the scan ran at all
 * @param failureKind    for a failed scan, which kind of failure, or {@code null}
 * @param failureMessage for a failed scan, what went wrong, or {@code null}
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
		List<Issue> issues,
		ScanStatus status,
		String failureKind,
		String failureMessage) {

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
		status = Objects.requireNonNullElse(status, ScanStatus.COMPLETED);
	}

	/**
	 * A completed scan, for callers that have no status to express.
	 *
	 * @param name          the short name this page was scanned under
	 * @param requestedUrl  the URL that was asked for
	 * @param pageUrl       the URL actually scanned
	 * @param documentTitle the page's title
	 * @param scannedAt     when the scan ran
	 * @param duration      how long it took
	 * @param standard      the standard tested against
	 * @param engines       which Pa11y runners produced these issues
	 * @param issues        what was found
	 */
	public PageReport(String name, String requestedUrl, String pageUrl, String documentTitle,
			Instant scannedAt, Duration duration, String standard, List<String> engines,
			List<Issue> issues) {
		this(name, requestedUrl, pageUrl, documentTitle, scannedAt, duration, standard, engines,
				issues, ScanStatus.COMPLETED, null, null);
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
				List.of(ScanRequest.ENGINE),
				redacted(result.issues()),
				ScanStatus.COMPLETED,
				null,
				null);
	}

	/**
	 * A scan that could not be run, saved so that it shows up as a failure rather than as a
	 * page nobody notices is missing.
	 *
	 * @param name    the name the page would have been saved under
	 * @param request what was asked for
	 * @param kind    which kind of failure it was
	 * @param message what went wrong
	 * @return the report to save
	 */
	public static PageReport failed(String name, ScanRequest request, FailureKind kind, String message) {
		return new PageReport(
				name,
				request.scanCurrentPage() ? "" : request.url(),
				"",
				"",
				Instant.now(),
				Duration.ZERO,
				request.standard().wireName(),
				List.of(ScanRequest.ENGINE),
				List.of(),
				ScanStatus.FAILED,
				(kind == null ? FailureKind.SCAN_ERROR : kind).name(),
				Objects.requireNonNullElse(message, "no detail given"));
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
	 * @return whether this page was measured at all
	 */
	public boolean isFailed() {
		return status == ScanStatus.FAILED;
	}

	/**
	 * @return the page's title, or its name if the title is empty
	 */
	public String displayTitle() {
		return documentTitle.isBlank() ? name : documentTitle;
	}

	private static List<Issue> redacted(List<Issue> issues) {
		if (issues == null) {
			return List.of();
		}
		List<Issue> safe = new ArrayList<>(issues.size());
		for (Issue issue : issues) {
			String context = SnippetRedaction.redact(issue.context());
			safe.add(context.equals(issue.context()) ? issue : new Issue(
					issue.code(),
					issue.type(),
					issue.typeCode(),
					issue.message(),
					context,
					issue.selector(),
					issue.engine(),
					issue.engineExtras()));
		}
		return safe;
	}
}
