package com.automation.pa11y;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanResultTest {

	@Test
	@DisplayName("separates errors from warnings and notices")
	void separatesBySeverity() {
		ScanResult result = resultWith(
				issue("code-a", IssueType.ERROR),
				issue("code-b", IssueType.WARNING),
				issue("code-c", IssueType.NOTICE),
				issue("code-d", IssueType.ERROR));

		assertEquals(2, result.errors().size());
		assertEquals(1, result.warnings().size());
		assertEquals(1, result.notices().size());
		assertTrue(result.hasErrors());
	}

	@Test
	@DisplayName("a page with only warnings has no errors, so it does not fail a default policy")
	void warningsAreNotErrors() {
		ScanResult result = resultWith(issue("code-b", IssueType.WARNING));

		assertFalse(result.hasErrors());
	}

	@Test
	@DisplayName("groups counts by rule, which is how one template defect shows up as ninety findings")
	void countsByCode() {
		ScanResult result = resultWith(
				issue("missing-alt", IssueType.ERROR),
				issue("missing-alt", IssueType.ERROR),
				issue("contrast", IssueType.ERROR));

		Map<String, Long> counts = result.countsByCode();

		assertEquals(2L, counts.get("missing-alt"));
		assertEquals(1L, counts.get("contrast"));
	}

	@Test
	@DisplayName("describeErrors is usable as an assertion message")
	void describesErrors() {
		ScanResult result = resultWith(issue("missing-alt", IssueType.ERROR));

		String description = result.describeErrors();

		assertTrue(description.contains("1 accessibility error"), description);
		assertTrue(description.contains("missing-alt"), description);
		assertTrue(description.contains("img.hero"), description);
	}

	@Test
	@DisplayName("describeErrors says so plainly when the page is clean")
	void describesCleanPage() {
		ScanResult result = resultWith();

		assertTrue(result.describeErrors().contains("No accessibility errors"));
	}

	@Test
	@DisplayName("an unrecognised issue type does not break the mapping")
	void toleratesUnknownIssueType() {
		assertEquals(IssueType.UNKNOWN, IssueType.fromWireName("something-new"));
		assertEquals(IssueType.UNKNOWN, IssueType.fromWireName(null));
		assertEquals(IssueType.ERROR, IssueType.fromWireName("error"));
	}

	@Test
	@DisplayName("nulls from the wire become empty values rather than NPEs at the call site")
	void normalisesNulls() {
		Issue issue = new Issue(null, null, -1, null, null, null, null, null);

		assertEquals("", issue.code());
		assertEquals(IssueType.UNKNOWN, issue.type());
		assertEquals(Map.of(), issue.engineExtras());
	}

	private static ScanResult resultWith(Issue... issues) {
		return new ScanResult(
				"https://example.com/page",
				"https://example.com/page",
				"Example",
				Duration.ofMillis(1200),
				List.of(issues));
	}

	private static Issue issue(String code, IssueType type) {
		return new Issue(code, type, 1, "Something is wrong", "<img>", "img.hero", "htmlcs", Map.of());
	}
}
