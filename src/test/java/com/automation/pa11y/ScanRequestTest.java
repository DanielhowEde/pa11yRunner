package com.automation.pa11y;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanRequestTest {

	@Test
	@DisplayName("defaults match Pa11y's own, so a bare scan behaves the way the docs say")
	void defaultsMatchPa11y() {
		ScanRequest request = ScanRequest.of("https://example.com");

		assertEquals(Standard.WCAG2AA, request.standard());
		assertFalse(request.includeWarnings());
		assertFalse(request.includeNotices());
		assertEquals(Duration.ofSeconds(60), request.timeout());
		assertEquals(Duration.ZERO, request.waitAfterLoad());
		assertTrue(request.ignore().isEmpty());
		assertTrue(request.actions().isEmpty());
	}

	@Test
	@DisplayName("a url request and a current-page request are told apart")
	void distinguishesTheTwoTargets() {
		ScanRequest byUrl = ScanRequest.of("https://example.com");
		ScanRequest open = ScanRequest.currentPage().build();

		assertFalse(byUrl.scanCurrentPage());
		assertEquals("https://example.com", byUrl.target());

		assertTrue(open.scanCurrentPage());
		assertEquals(null, open.url());
		assertTrue(open.target().contains("currently open"));
	}

	@Test
	@DisplayName("rejects a missing url at construction rather than at scan time")
	void rejectsMissingUrl() {
		assertThrows(IllegalArgumentException.class, () -> ScanRequest.of(""));
		assertThrows(IllegalArgumentException.class, () -> ScanRequest.of(null));
	}

	@Test
	@DisplayName("collects repeated options rather than replacing them")
	void collectsRepeatedOptions() {
		ScanRequest request = ScanRequest.forUrl("https://example.com")
				.ignore("code-one")
				.ignore("code-two")
				.actions("click element #accept")
				.actions("wait for element #results to be visible")
				.build();

		assertEquals(List.of("code-one", "code-two"), request.ignore());
		assertEquals(2, request.actions().size());
	}

	@Test
	@DisplayName("collections are copied, so a builder reused after build cannot mutate a request")
	void collectionsAreDefensivelyCopied() {
		ScanRequest.Builder builder = ScanRequest.forUrl("https://example.com").ignore("first");
		ScanRequest request = builder.build();

		builder.ignore("second");

		assertEquals(List.of("first"), request.ignore());
		assertThrows(UnsupportedOperationException.class, () -> request.ignore().add("third"));
	}

	@Test
	@DisplayName("toBuilder carries every option across, so a shared baseline can be varied per page")
	void toBuilderRoundTrips() {
		ScanRequest original = ScanRequest.forUrl("https://example.com/one")
				.standard(Standard.WCAG2AAA)
				.includeWarnings(true)
				.includeNotices(true)
				.timeout(Duration.ofSeconds(90))
				.waitAfterLoad(Duration.ofMillis(250))
				.rootElement("#main")
				.hideElements(".advert")
				.ignore("noisy-rule")
				.actions("click element #accept")
				.build();

		assertEquals(original, original.toBuilder().build());
	}

	@Test
	@DisplayName("toBuilder keeps a current-page request pointed at the open tab")
	void toBuilderKeepsTheCurrentPageTarget() {
		ScanRequest open = ScanRequest.currentPage().rootElement("#main").build();

		ScanRequest copy = open.toBuilder().build();

		assertTrue(copy.scanCurrentPage());
		assertEquals("#main", copy.rootElement());
	}

	@Test
	@DisplayName("toBuilder(url) keeps the policy and changes only the page, for scanning many screens alike")
	void toBuilderRepointsAtAnotherPage() {
		ScanRequest policy = ScanRequest.currentPage()
				.standard(Standard.WCAG2AAA)
				.rootElement("#main")
				.ignore("noisy-rule")
				.includeWarnings(true)
				.build();

		ScanRequest checkout = policy.toBuilder("https://example.com/checkout").build();

		assertEquals("https://example.com/checkout", checkout.url());
		assertFalse(checkout.scanCurrentPage(), "a url request should stop being a current-page one");
		assertEquals(Standard.WCAG2AAA, checkout.standard());
		assertEquals("#main", checkout.rootElement());
		assertEquals(List.of("noisy-rule"), checkout.ignore());
		assertTrue(checkout.includeWarnings());
		assertTrue(policy.scanCurrentPage(), "the baseline should be unchanged");
	}

	@Test
	@DisplayName("toBuilder can change one option without disturbing the rest")
	void toBuilderVariesOneOption() {
		ScanRequest original = ScanRequest.forUrl("https://example.com")
				.includeWarnings(true)
				.rootElement("#main")
				.build();

		ScanRequest longer = original.toBuilder().timeout(Duration.ofSeconds(120)).build();

		assertEquals(Duration.ofSeconds(120), longer.timeout());
		assertTrue(longer.includeWarnings());
		assertEquals("#main", longer.rootElement());
	}
}
