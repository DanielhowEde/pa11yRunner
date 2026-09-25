package com.automation.pa11y.report;

import com.automation.pa11y.Issue;
import com.automation.pa11y.IssueType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlReportTest {

	@Test
	@DisplayName("markup taken from the page under test is escaped, never rendered")
	void escapesUntrustedMarkup() {
		// An issue's context is a fragment of the offending page, so the report is full of
		// untrusted HTML. Rendering it would break the report; executing it would be worse.
		Issue nasty = new Issue("dangerous", IssueType.ERROR, 1,
				"Bad <b>message</b>",
				"<script>alert('xss')</script>",
				"img[onerror=\"alert(1)\"]",
				"htmlcs", null);

		String html = HtmlReport.render(CombinedReport.from(List.of(page("checkout", nasty))), "Report");

		assertFalse(html.contains("<script>alert"), "the script tag must not survive as markup");
		assertTrue(html.contains("&lt;script&gt;"), "it should appear as text instead");
		assertTrue(html.contains("&quot;") || html.contains("&#39;"), "quotes in selectors are escaped");
		assertFalse(html.contains("Bad <b>message</b>"), "message markup is escaped too");
	}

	@Test
	@DisplayName("separates the shared section from the single-page one, and says which is which")
	void separatesSharedFromUnique() {
		CombinedReport report = CombinedReport.from(List.of(
				page("checkout", issue("missing-alt"), issue("only-here")),
				page("basket", issue("missing-alt"))));

		String html = HtmlReport.render(report, "Report");

		assertTrue(html.contains("Shared across pages"), html);
		assertTrue(html.contains("Specific to one page"), html);
		assertTrue(html.contains("2 of 2 pages"), "a shared rule should say how far it spreads");
	}

	@Test
	@DisplayName("says plainly that one page cannot be compared against anything")
	void explainsSinglePageRuns() {
		String html = HtmlReport.render(
				CombinedReport.from(List.of(page("checkout", issue("missing-alt")))), "Report");

		assertTrue(html.contains("nothing to compare"), html);
	}

	@Test
	@DisplayName("a clean run says so rather than showing empty sections")
	void reportsACleanRun() {
		String html = HtmlReport.render(CombinedReport.from(List.of(page("a"), page("b"))), "Report");

		assertTrue(html.contains("No issues were found"), html);
	}

	@Test
	@DisplayName("is a complete, self-contained document with no external references")
	void isSelfContained() {
		String html = HtmlReport.render(CombinedReport.from(List.of(page("a", issue("x")))), "Report");

		assertTrue(html.startsWith("<!DOCTYPE html>"), "should be a whole document");
		assertTrue(html.contains("<html lang=\"en\">"), "a report about accessibility needs a language");
		assertFalse(html.contains("http://") && html.contains("<link"), "no external stylesheet");
		assertFalse(html.contains("<script"), "no script at all, external or otherwise");
	}

	@Test
	@DisplayName("severity is written in words, not carried by colour alone")
	void severityIsNotColourOnly() {
		CombinedReport report = CombinedReport.from(List.of(page("a", issue("x"), warning("y"))));

		String html = HtmlReport.render(report, "Report");

		assertTrue(html.contains(">Error<"), html);
		assertTrue(html.contains(">Warning<"), html);
	}

	@Test
	@DisplayName("the title given on the command line is used and escaped")
	void usesTheGivenTitle() {
		String html = HtmlReport.render(CombinedReport.from(List.of(page("a"))), "Release <4.2>");

		assertTrue(html.contains("Release &lt;4.2&gt;"), html);
		assertFalse(html.contains("Release <4.2>"), html);
	}

	private static PageReport page(String name, Issue... issues) {
		return new PageReport(name, "https://example.com/" + name, "https://example.com/" + name,
				name, Instant.now(), Duration.ofMillis(100), "WCAG2AA", List.of("htmlcs"), List.of(issues));
	}

	private static Issue issue(String code) {
		return new Issue(code, IssueType.ERROR, 1, "Something is wrong", "<img>", "img.hero", "htmlcs", null);
	}

	private static Issue warning(String code) {
		return new Issue(code, IssueType.WARNING, 2, "Might be wrong", "<p>", "p", "htmlcs", null);
	}
}
