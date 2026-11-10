package com.automation.pa11y.report;

import com.automation.pa11y.FailureKind;
import com.automation.pa11y.Issue;
import com.automation.pa11y.IssueType;
import com.automation.pa11y.ScanRequest;
import com.automation.pa11y.ScanResult;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders a whole mock run to {@code target/sample/combined-report.html}, so there is an
 * example report to look at on a machine with no Grid, and checks the properties that would be
 * embarrassing to get wrong.
 *
 * <p>Written as a test rather than kept as a committed file on purpose: a checked-in sample
 * drifts away from the renderer the first time anyone changes the renderer, and nobody notices
 * until it is shown to someone. This one is rebuilt by every {@code mvn test}.
 *
 * <p>Everything in it is invented -- the pages, the findings, the failure and the planted
 * secret.
 */
class SampleReportTest {

	private static final Path OUTPUT = Path.of("target", "sample", "combined-report.html");

	/** A value planted in a hidden field, to prove it does not reach the report. */
	private static final String PLANTED_SECRET = "7f3c91ab20d4";

	@Test
	@DisplayName("a whole mock run renders to target/sample/combined-report.html")
	void writesASampleReport() throws IOException {
		Issue missingAlt = issue("WCAG2AA.Principle1.Guideline1_1.1_1_1.H37", IssueType.ERROR,
				"Img element missing an alt attribute. Use the alt attribute to specify a short text alternative.",
				"<img src=\"/assets/hero.png\">", "#masthead > img");
		Issue noLabel = issue("WCAG2AA.Principle1.Guideline1_3.1_3_1.F68", IssueType.ERROR,
				"This form field should be labelled in some way.",
				"<input type=\"text\" name=\"q\">", "#site-search > input");
		Issue contrast = issue("WCAG2AA.Principle1.Guideline1_4.1_4_3.G18.Fail", IssueType.ERROR,
				"This element has insufficient contrast at this conformance level.",
				"<a class=\"muted\" href=\"/terms\">Terms</a>", "footer > a:nth-child(3)");
		Issue emptyLink = issue("WCAG2AA.Principle4.Guideline4_1.4_1_2.H91.A.NoContent", IssueType.ERROR,
				"Anchor element found with a valid href attribute, but no link content has been supplied.",
				"<a href=\"/basket\"></a>", "#basket-link");
		Issue langWarning = issue("WCAG2AA.Principle3.Guideline3_1.3_1_2.H58.1.Lang", IssueType.WARNING,
				"The language specified in the lang attribute of the document element is not well-formed.",
				"<span lang=\"en_GB\">colour</span>", "#intro > span");
		Issue hiddenField = issue("WCAG2AA.Principle1.Guideline1_3.1_3_1.F68", IssueType.ERROR,
				"This form field should be labelled in some way.",
				"<input type=\"hidden\" name=\"csrf\" value=\"" + PLANTED_SECRET + "\">",
				"#login > input[name=csrf]");

		PageReport login = page("Login", "Sign in -- Sample App", hiddenField, missingAlt);
		CombinedReport report = CombinedReport.from(List.of(
				page("Dashboard", "Dashboard -- Sample App", missingAlt, noLabel, contrast),
				page("UserManagement", "Users -- Sample App", noLabel, contrast, emptyLink, langWarning),
				login,
				PageReport.failed("Reports", ScanRequest.currentPage().build(),
						FailureKind.CHROME_UNREACHABLE,
						"Could not reach Chrome's debugger at "
								+ "ws://grid.example:4444/session/3f9a/se/cdp: connection refused")));

		Files.createDirectories(OUTPUT.getParent());
		Path written = HtmlReport.write(report, "Sample accessibility report (mock data)", OUTPUT);
		String html = Files.readString(written, StandardCharsets.UTF_8);

		// Redaction happens on the way into the page record, which is what gets saved and
		// rendered. Checked on the record, because the report shows one snippet per rule and
		// may not happen to show this one.
		String savedSnippet = login.issues().get(0).context();
		assertFalse(savedSnippet.contains(PLANTED_SECRET), savedSnippet);
		assertTrue(savedSnippet.contains("[redacted]"), savedSnippet);

		// Whatever it chose to render, the secret is not in it.
		assertFalse(html.contains(PLANTED_SECRET), "a hidden field value reached the report");

		// The unmeasured page is reported as one, and the run is not described as clean.
		assertTrue(html.contains("Scans that did not run"), html.substring(0, 200));
		assertTrue(html.contains("CHROME_UNREACHABLE"));
		assertFalse(html.contains("No issues were found on any page scanned"));
		assertFalse(report.isClean());

		// Shared versus page-unique, by rule code: F68 fired on three pages, the empty link on one.
		assertTrue(report.shared().stream().anyMatch(group -> group.code().contains("1_3_1.F68")));
		assertTrue(report.unique().stream().anyMatch(group -> group.code().contains("H91.A.NoContent")));

		// Self-contained: nothing is fetched when this is opened, so it works from a build
		// artifact store, an email attachment or a machine with no network.
		assertFalse(Pattern.compile("(?:src|href)\\s*=\\s*[\"']https?://").matcher(html).find(),
				"the report must not reference anything external");
	}

	private static PageReport page(String name, String title, Issue... issues) {
		// Through PageReport.of, the path a real scan takes, so the sample exercises the
		// redaction rather than describing it.
		String url = "https://sample.example/" + name.toLowerCase();
		ScanResult result = new ScanResult(url, url, title, Duration.ofMillis(1840), List.of(issues));
		return PageReport.of(name, ScanRequest.currentPage().build(), result);
	}

	private static Issue issue(String code, IssueType type, String message, String context, String selector) {
		return new Issue(code, type, type == IssueType.ERROR ? 1 : 2, message, context, selector,
				ScanRequest.ENGINE, null);
	}
}
