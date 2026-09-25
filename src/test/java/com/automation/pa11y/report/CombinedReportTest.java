package com.automation.pa11y.report;

import com.automation.pa11y.Issue;
import com.automation.pa11y.IssueType;
import com.automation.pa11y.report.CombinedReport.RuleGroup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombinedReportTest {

	@Test
	@DisplayName("a rule on several pages is shared; one confined to a page is not")
	void splitsSharedFromUnique() {
		CombinedReport report = CombinedReport.from(List.of(
				page("checkout", issue("missing-alt"), issue("contrast")),
				page("basket", issue("missing-alt")),
				page("login", issue("empty-link"))));

		assertEquals(List.of("missing-alt"), codes(report.shared()));
		assertEquals(List.of("contrast", "empty-link"), codes(report.unique()));
	}

	@Test
	@DisplayName("the most widespread rule comes first, because that is the one fix worth making")
	void ordersBySpread() {
		CombinedReport report = CombinedReport.from(List.of(
				page("one", issue("everywhere"), issue("twice")),
				page("two", issue("everywhere"), issue("twice")),
				page("three", issue("everywhere"))));

		// 'everywhere' is on three pages, 'twice' on two, so 'everywhere' leads even though
		// both fired the same number of times overall.
		assertEquals(List.of("everywhere", "twice"), codes(report.shared()));
		assertEquals(3, report.shared().get(0).pageCount());
	}

	@Test
	@DisplayName("a rule firing many times on one page still ranks below one spread across pages")
	void spreadBeatsVolume() {
		Issue[] many = new Issue[40];
		Arrays.fill(many, issue("noisy"));

		CombinedReport report = CombinedReport.from(List.of(
				page("one", many),
				page("two", issue("spread")),
				page("three", issue("spread"))));

		assertEquals(List.of("spread"), codes(report.shared()));
		assertEquals(List.of("noisy"), codes(report.unique()));
		assertEquals(40, report.unique().get(0).totalOccurrences());
	}

	@Test
	@DisplayName("counts every occurrence, and records which pages they were on")
	void countsOccurrencesPerPage() {
		CombinedReport report = CombinedReport.from(List.of(
				page("checkout", issue("missing-alt"), issue("missing-alt"), issue("missing-alt")),
				page("basket", issue("missing-alt"))));

		RuleGroup group = report.shared().get(0);

		assertEquals(4, group.totalOccurrences());
		assertEquals(2, group.pageCount());
		// Worst page first, so the place to start is at the top.
		assertEquals(List.of("checkout", "basket"), group.pageNames());
		assertEquals(3, group.pages().get(0).count());
	}

	@Test
	@DisplayName("one page means nothing can be shared, and the report can say so")
	void singlePageHasNothingToCompare() {
		CombinedReport report = CombinedReport.from(List.of(page("checkout", issue("missing-alt"))));

		assertTrue(report.isSinglePage());
		assertTrue(report.shared().isEmpty());
		assertEquals(1, report.unique().size());
	}

	@Test
	@DisplayName("pages with no issues still count as scanned")
	void cleanPagesAreStillPages() {
		CombinedReport report = CombinedReport.from(List.of(page("clean"), page("also-clean")));

		assertEquals(2, report.pages().size());
		assertEquals(0, report.totalIssues());
		assertFalse(report.isSinglePage());
		assertTrue(report.shared().isEmpty());
	}

	@Test
	@DisplayName("totals add up across every page")
	void totalsAcrossPages() {
		CombinedReport report = CombinedReport.from(List.of(
				page("one", issue("a"), warning("b")),
				page("two", issue("a"))));

		assertEquals(2, report.total(IssueType.ERROR));
		assertEquals(1, report.total(IssueType.WARNING));
		assertEquals(3, report.totalIssues());
	}

	private static List<String> codes(List<RuleGroup> groups) {
		return groups.stream().map(RuleGroup::code).toList();
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
