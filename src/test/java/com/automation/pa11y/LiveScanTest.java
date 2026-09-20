package com.automation.pa11y;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end tests against a real Chrome and a real page.
 *
 * <p>Skipped unless {@code PA11Y_CHROME_URL} is set, so an ordinary build needs neither a
 * browser nor a network. To run them, point at a Chrome with its debugger open:
 *
 * <pre>{@code
 * PA11Y_CHROME_URL=http://localhost:9222 \
 * PA11Y_TEST_URL=https://example.com \
 *   mvn test -Dtest=LiveScanTest
 * }</pre>
 *
 * <p>Worth running once against the Grid node itself, since the failure modes this library
 * exists to handle -- Chrome's Host header check, the loopback debugger URL, the WebSocket
 * origin check -- only appear when the browser is on a different machine.
 */
@EnabledIfEnvironmentVariable(named = "PA11Y_CHROME_URL", matches = ".+")
class LiveScanTest {

	private static final String TEST_URL =
			System.getenv().getOrDefault("PA11Y_TEST_URL", "https://example.com");

	@Test
	@DisplayName("scans a real page in the attached browser")
	void scansARealPage() {
		ScanResult result = runner().scan(TEST_URL);

		assertNotNull(result.pageUrl());
		assertFalse(result.documentTitle().isBlank(), "the scan should have reached a real page");
		assertTrue(result.duration().toMillis() > 0);
	}

	@Test
	@DisplayName("scans whatever page the browser already has open, without being told a URL")
	void scansTheOpenPage() {
		ScanResult result;
		try {
			result = runner().scanCurrentPage();
		} catch (ScanFailedException e) {
			// Nothing was open to scan, which is a fair state for a browser nobody has driven.
			// The suite's own run will always have navigated first.
			assumeTrue(e.kind() != FailureKind.NO_PAGE_OPEN, "no page is open in this browser");
			throw e;
		}

		assertFalse(result.pageUrl().isBlank(), "the scan should report which page it measured");
		assertTrue(result.duration().toMillis() > 0);
	}

	@Test
	@DisplayName("leaves the browser running, so the WebDriver session that asked for the scan survives it")
	void leavesTheBrowserRunning() {
		Pa11yRunner runner = runner();

		runner.scan(TEST_URL);
		ScanResult second = runner.scan(TEST_URL);

		// The second scan could only connect because the first one disconnected rather than
		// closing the browser. This is the regression that would break a live Grid session.
		assertNotNull(second.pageUrl());
	}

	@Test
	@DisplayName("reports warnings and notices only when they are asked for")
	void honoursSeverityOptions() {
		ScanResult defaults = runner().scan(TEST_URL);
		assertTrue(defaults.warnings().isEmpty(), "warnings are off by default");
		assertTrue(defaults.notices().isEmpty(), "notices are off by default");

		ScanResult verbose = runner().scan(ScanRequest.forUrl(TEST_URL)
				.includeWarnings(true)
				.includeNotices(true)
				.build());

		assertTrue(verbose.issues().size() >= defaults.issues().size());
	}

	@Test
	@DisplayName("a page that cannot be loaded fails the scan rather than reporting a clean result")
	void unreachablePageFails() {
		ScanFailedException thrown = assertThrows(ScanFailedException.class,
				() -> runner().scan(ScanRequest.forUrl("http://this-host-does-not-exist.invalid/")
						.timeout(Duration.ofSeconds(20))
						.build()));

		assertEquals(FailureKind.NAVIGATION_FAILED, thrown.kind());
	}

	/**
	 * @return a runner configured from the environment
	 */
	private static Pa11yRunner runner() {
		return Pa11yRunner.builder().debug(true).build();
	}
}
