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
		assertEquals(List.of(ScanEngine.HTMLCS), request.engines());
		assertFalse(request.includeWarnings());
		assertFalse(request.includeNotices());
		assertEquals(Duration.ofSeconds(60), request.timeout());
		assertEquals(Duration.ZERO, request.waitAfterLoad());
		assertEquals(1280, request.viewportWidth());
		assertEquals(1024, request.viewportHeight());
		assertEquals("GET", request.method());
	}

	@Test
	@DisplayName("an empty engine list falls back to htmlcs instead of scanning with nothing")
	void emptyEngineListFallsBack() {
		ScanRequest request = ScanRequest.forUrl("https://example.com").engines(List.of()).build();

		assertEquals(List.of(ScanEngine.HTMLCS), request.engines());
	}

	@Test
	@DisplayName("rejects a missing url at construction rather than at scan time")
	void rejectsMissingUrl() {
		assertThrows(IllegalArgumentException.class, () -> ScanRequest.of(""));
		assertThrows(IllegalArgumentException.class, () -> ScanRequest.of(null));
	}

	@Test
	@DisplayName("rejects a viewport that cannot be rendered")
	void rejectsImpossibleViewport() {
		assertThrows(IllegalArgumentException.class,
				() -> ScanRequest.forUrl("https://example.com").viewport(0, 1024).build());
	}

	@Test
	@DisplayName("collects repeated options rather than replacing them")
	void collectsRepeatedOptions() {
		ScanRequest request = ScanRequest.forUrl("https://example.com")
				.ignore("code-one")
				.ignore("code-two")
				.header("Authorization", "Bearer token")
				.header("X-Env", "staging")
				.actions("click element #accept")
				.build();

		assertEquals(List.of("code-one", "code-two"), request.ignore());
		assertEquals(2, request.headers().size());
		assertEquals("Bearer token", request.headers().get("Authorization"));
		assertEquals(List.of("click element #accept"), request.actions());
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
				.engines(ScanEngine.HTMLCS, ScanEngine.AXE)
				.includeWarnings(true)
				.timeout(Duration.ofSeconds(90))
				.waitAfterLoad(Duration.ofMillis(250))
				.viewport(375, 812)
				.rootElement("#main")
				.hideElements(".advert")
				.ignore("noisy-rule")
				.header("X-Env", "staging")
				.actions("click element #accept")
				.request("POST", "a=b")
				.build();

		ScanRequest copy = original.toBuilder().build();

		assertEquals(original, copy);
	}

	@Test
	@DisplayName("toBuilder(url) keeps the policy and changes only the page, for scanning many screens alike")
	void toBuilderRepointsAtAnotherPage() {
		ScanRequest policy = ScanRequest.forUrl("about:blank")
				.engines(ScanEngine.HTMLCS, ScanEngine.AXE)
				.rootElement("#main")
				.ignore("noisy-rule")
				.includeWarnings(true)
				.build();

		ScanRequest checkout = policy.toBuilder("https://example.com/checkout").build();

		assertEquals("https://example.com/checkout", checkout.url());
		assertEquals(List.of(ScanEngine.HTMLCS, ScanEngine.AXE), checkout.engines());
		assertEquals("#main", checkout.rootElement());
		assertEquals(List.of("noisy-rule"), checkout.ignore());
		assertTrue(checkout.includeWarnings());
		assertEquals("about:blank", policy.url(), "the baseline should be unchanged");
	}

	@Test
	@DisplayName("toBuilder can change one option without disturbing the rest")
	void toBuilderVariesOneOption() {
		ScanRequest mobile = ScanRequest.forUrl("https://example.com")
				.viewport(375, 812)
				.includeWarnings(true)
				.build();

		ScanRequest desktop = mobile.toBuilder().viewport(1440, 900).build();

		assertEquals(1440, desktop.viewportWidth());
		assertTrue(desktop.includeWarnings());
	}
}
