package com.automation.pa11y.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanResponseTest {

	@Test
	@DisplayName("reads a completed scan, issues and all")
	void readsACompletedScan() throws IOException {
		ScanResponse response = ScanResponse.parse("""
				{
				  "status": "COMPLETED",
				  "documentTitle": "Basket",
				  "pageUrl": "https://example.com/basket",
				  "durationMillis": 1234,
				  "issues": [{
				    "code": "WCAG2AA.Principle1.Guideline1_1.1_1_1.H37",
				    "type": "error",
				    "typeCode": 1,
				    "message": "Img element missing an alt attribute.",
				    "context": "<img src=\\"a.png\\">",
				    "selector": "html > body > img",
				    "runner": "htmlcs",
				    "runnerExtras": {}
				  }]
				}""");

		assertTrue(response.completed());
		assertEquals("Basket", response.documentTitle());
		assertEquals(1234, response.durationMillis());
		assertEquals(1, response.issuesOrEmpty().size());
		assertEquals("htmlcs", response.issuesOrEmpty().get(0).runner());
	}

	@Test
	@DisplayName("ignores fields it does not know, so a newer scanner script cannot break an older jar")
	void toleratesUnknownFields() throws IOException {
		// This is the behaviour that used to come from @JsonIgnoreProperties and now comes from
		// the reader's own configuration. Without it, every one of these extra fields would
		// throw and the scan would be reported as a scanner failure.
		ScanResponse response = ScanResponse.parse("""
				{
				  "status": "COMPLETED",
				  "documentTitle": "Basket",
				  "pageUrl": "https://example.com/basket",
				  "durationMillis": 10,
				  "somethingAddedLater": true,
				  "engineVersions": { "htmlcs": "2.6.0" },
				  "issues": [{
				    "code": "X",
				    "type": "error",
				    "typeCode": 1,
				    "message": "m",
				    "context": "c",
				    "selector": "s",
				    "runner": "htmlcs",
				    "runnerExtras": {},
				    "screenshotRef": "abc"
				  }]
				}""");

		assertTrue(response.completed());
		assertEquals(1, response.issuesOrEmpty().size());
		assertEquals("X", response.issuesOrEmpty().get(0).code());
	}

	@Test
	@DisplayName("a failed scan is not a completed one, however it is spelled")
	void readsAFailure() throws IOException {
		ScanResponse response = ScanResponse.parse("""
				{
				  "status": "FAILED",
				  "failureKind": "NO_PAGE_OPEN",
				  "message": "Chrome has no page open to scan.",
				  "durationMillis": 5,
				  "issues": []
				}""");

		assertFalse(response.completed());
		assertEquals("NO_PAGE_OPEN", response.failureKind());
		assertTrue(response.issuesOrEmpty().isEmpty());
	}

	@Test
	@DisplayName("a missing issues array reads as no issues rather than a null")
	void missingIssuesIsEmpty() throws IOException {
		ScanResponse response = ScanResponse.parse("{\"status\":\"COMPLETED\",\"durationMillis\":1}");

		assertTrue(response.issuesOrEmpty().isEmpty());
	}

	@Test
	@DisplayName("something that is not a response at all is rejected")
	void rejectsRubbish() {
		assertThrows(IOException.class, () -> ScanResponse.parse("not json"));
	}
}
