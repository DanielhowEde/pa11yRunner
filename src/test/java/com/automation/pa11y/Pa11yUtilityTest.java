package com.automation.pa11y;

import com.automation.pa11y.report.CombinedReport;
import com.automation.pa11y.report.PageReport;
import com.automation.pa11y.report.ReportStore;
import com.automation.pa11y.report.ScanStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three-call API, tested without a browser.
 *
 * <p>Everything a scan needs a live Chrome for is covered by {@code LiveScanTest}, which runs
 * only when {@code PA11Y_CHROME_URL} is set. What is tested here is the part that goes wrong
 * silently: where files land, what is refused, and whether a scan that did not happen can be
 * mistaken for a page with nothing wrong.
 */
class Pa11yUtilityTest {

	@Test
	@DisplayName("clear removes this tool's output and leaves everything else alone")
	void clearRemovesOnlyOurOwnFiles(@TempDir Path temp) throws IOException {
		Pa11yUtility pa11y = new Pa11yUtility(Pa11yConfig.empty(), temp);
		new ReportStore(temp).write(page("Dashboard"));
		Path foreign = Files.writeString(temp.resolve("fixture.json"), "{\"mine\": true}",
				StandardCharsets.UTF_8);
		Path notJson = Files.writeString(temp.resolve("notes.txt"), "keep me", StandardCharsets.UTF_8);

		List<Path> deleted = pa11y.clearPa11y();

		assertTrue(deleted.contains(temp.resolve("dashboard.json")), deleted.toString());
		assertTrue(Files.exists(foreign), "a .json this tool did not write must survive");
		assertTrue(Files.exists(notJson));
	}

	@Test
	@DisplayName("clear on a directory that does not exist yet is fine, not an error")
	void clearToleratesAMissingDirectory(@TempDir Path temp) {
		Pa11yUtility pa11y = new Pa11yUtility(Pa11yConfig.empty(), temp.resolve("not-made-yet"));

		assertTrue(pa11y.clearPa11y().isEmpty());
	}

	@Test
	@DisplayName("a symlinked json is not deleted, because what it points at is somewhere else")
	void clearDoesNotFollowSymlinks(@TempDir Path temp) throws IOException {
		Path outside = Files.createDirectory(temp.resolve("outside"));
		Path target = Files.writeString(outside.resolve("precious.json"), "{\"generator\": \"pa11y-runner\"}",
				StandardCharsets.UTF_8);
		Path reports = Files.createDirectory(temp.resolve("reports"));
		try {
			Files.createSymbolicLink(reports.resolve("link.json"), target);
		} catch (IOException | UnsupportedOperationException e) {
			// Windows needs a privilege for this. The guard is still in place; it just cannot
			// be demonstrated here, and skipping beats a test that fails for the wrong reason.
			return;
		}

		new Pa11yUtility(Pa11yConfig.empty(), reports).clearPa11y();

		assertTrue(Files.exists(target), "the file the link pointed at must be untouched");
		assertTrue(Files.exists(reports.resolve("link.json"), LinkOption.NOFOLLOW_LINKS));
	}

	@Test
	@DisplayName("a blank browser location is refused, and the message says it is not a page url")
	void refusesABlankChromeUrl(@TempDir Path temp) {
		Pa11yUtility pa11y = new Pa11yUtility(Pa11yConfig.empty(), temp);

		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
				() -> pa11y.scanPa11y("  ", "Dashboard"));

		assertTrue(failure.getMessage().contains("not the page to scan"), failure.getMessage());
	}

	@Test
	@DisplayName("a page name cannot climb out of the output directory")
	void refusesPageNamesThatArePaths(@TempDir Path temp) {
		Pa11yUtility pa11y = new Pa11yUtility(Pa11yConfig.empty(), temp);

		for (String name : List.of("../escape", "sub/dir", "sub\\dir", "..", "C:evil", ".hidden")) {
			assertThrows(IllegalArgumentException.class,
					() -> pa11y.scanPa11y("http://127.0.0.1:1", name), name);
		}
		assertThrows(IllegalArgumentException.class,
				() -> pa11y.scanPa11y("http://127.0.0.1:1", "   "));
	}

	@Test
	@DisplayName("the same page name twice in a run is refused rather than silently overwriting")
	void refusesADuplicatePageName(@TempDir Path temp) {
		Pa11yUtility pa11y = new Pa11yUtility(Pa11yConfig.empty(), temp);

		// Port 1 is reserved, so the first scan cannot reach a browser. It still claims the
		// name, which is the point: the second call must not quietly replace its record.
		assertThrows(Pa11yException.class, () -> pa11y.scanPa11y("http://127.0.0.1:1", "Dashboard"));

		IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
				() -> pa11y.scanPa11y("http://127.0.0.1:1", "Dashboard"));

		assertTrue(duplicate.getMessage().contains("already been scanned"), duplicate.getMessage());
	}

	@Test
	@DisplayName("a scan that could not run is saved as a failure, not left as a gap")
	void recordsAScanThatCouldNotRun(@TempDir Path temp) {
		Pa11yUtility pa11y = new Pa11yUtility(Pa11yConfig.empty(), temp);

		assertThrows(Pa11yException.class, () -> pa11y.scanPa11y("http://127.0.0.1:1", "Dashboard"));

		List<PageReport> saved = new ReportStore(temp).readAll();
		assertEquals(1, saved.size(), "the failure must be on disk");
		PageReport failure = saved.get(0);
		assertEquals("Dashboard", failure.name());
		assertEquals(ScanStatus.FAILED, failure.status());
		assertTrue(failure.isFailed());
		assertTrue(failure.issues().isEmpty());
		assertFalse(failure.failureMessage().isBlank());
	}

	@Test
	@DisplayName("a run holding a failed scan is not clean, however few issues were found")
	void aFailedScanIsNotACleanRun(@TempDir Path temp) {
		ReportStore store = new ReportStore(temp);
		store.write(page("Dashboard"));
		store.write(PageReport.failed("UserManagement", ScanRequest.currentPage().build(),
				FailureKind.CHROME_UNREACHABLE, "nothing was listening"));

		CombinedReport combined = CombinedReport.from(store.readAll());

		assertEquals(0, combined.totalIssues());
		assertFalse(combined.isClean(), "zero findings from a scan that did not happen is not clean");
		assertEquals(1, combined.failedPages().size());
		assertEquals(1, combined.completedPages().size());
		assertEquals("UserManagement", combined.failedPages().get(0).name());
	}

	@Test
	@DisplayName("combining with nothing saved is an error, because it means the scans did not run")
	void combineWithNothingSavedFails(@TempDir Path temp) {
		Pa11yUtility pa11y = new Pa11yUtility(Pa11yConfig.empty(), temp);

		Pa11yException failure = assertThrows(Pa11yException.class, pa11y::combinePa11y);

		assertTrue(failure.getMessage().contains("No page reports found"), failure.getMessage());
	}

	@Test
	@DisplayName("combine writes combined-report.html next to the pages and keeps the page files")
	void combineWritesTheReportAndKeepsThePages(@TempDir Path temp) throws IOException {
		Pa11yUtility pa11y = new Pa11yUtility(Pa11yConfig.empty(), temp);
		new ReportStore(temp).write(page("Dashboard"));

		Path report = pa11y.combinePa11y();

		assertEquals(temp.resolve("combined-report.html"), report);
		assertTrue(Files.exists(report));
		assertTrue(Files.exists(temp.resolve("dashboard.json")), "the page file must survive combining");
		assertTrue(Files.readString(report, StandardCharsets.UTF_8).contains("Dashboard"));
	}

	@Test
	@DisplayName("the output directory can be pointed anywhere without touching the default")
	void outputDirectoryIsOverridable(@TempDir Path temp) {
		assertEquals(temp.toAbsolutePath().normalize(),
				new Pa11yUtility(Pa11yConfig.empty(), temp).outputDirectory());

		// The default stays under the user's home, never project-relative.
		assertTrue(Pa11yCli.reportsDirectory(Pa11yConfig.empty()).isAbsolute());
		assertTrue(Pa11yCli.reportsDirectory(Pa11yConfig.empty()).toString()
				.startsWith(System.getProperty("user.home")));
	}

	private static PageReport page(String name) {
		return new PageReport(name, "https://example.com", "https://example.com", name,
				Instant.now(), Duration.ofMillis(5), Standard.WCAG2AA.wireName(),
				List.of(ScanRequest.ENGINE), List.of());
	}
}
