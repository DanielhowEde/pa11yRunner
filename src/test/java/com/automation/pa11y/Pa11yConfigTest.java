package com.automation.pa11y;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Pa11yConfigTest {

	@Test
	@DisplayName("no config file leaves every Pa11y default exactly as it was")
	void emptyChangesNothing() {
		ScanRequest request = Pa11yConfig.empty().currentPage().build();

		assertFalse(Pa11yConfig.empty().isPresent());
		assertEquals(Standard.WCAG2AA, request.standard());
		assertFalse(request.includeWarnings());
		assertFalse(request.includeNotices());
		assertEquals(Duration.ofSeconds(60), request.timeout());
		assertEquals(Duration.ZERO, request.waitAfterLoad());
		assertTrue(request.ignore().isEmpty());
		assertTrue(request.actions().isEmpty());
	}

	@Test
	@DisplayName("a config file supplies the policy every scan in the run is judged by")
	void suppliesScanDefaults(@TempDir Path temp) throws IOException {
		Pa11yConfig config = write(temp, """
				{
				  "standard": "WCAG2AAA",
				  "includeWarnings": true,
				  "timeout": 90,
				  "wait": 500,
				  "rootElement": "#main",
				  "hideElements": ".third-party-widget",
				  "ignore": ["WCAG2AA.Principle1.Guideline1_4.1_4_3.G18.Fail"],
				  "actions": ["click element #cookie-accept"]
				}""");

		ScanRequest request = config.currentPage().build();

		assertTrue(config.isPresent());
		assertTrue(request.scanCurrentPage());
		assertEquals(Standard.WCAG2AAA, request.standard());
		assertTrue(request.includeWarnings());
		assertFalse(request.includeNotices());
		assertEquals(Duration.ofSeconds(90), request.timeout());
		assertEquals(Duration.ofMillis(500), request.waitAfterLoad());
		assertEquals("#main", request.rootElement());
		assertEquals(".third-party-widget", request.hideElements());
		assertEquals(List.of("WCAG2AA.Principle1.Guideline1_4.1_4_3.G18.Fail"), request.ignore());
		assertEquals(List.of("click element #cookie-accept"), request.actions());
	}

	@Test
	@DisplayName("the same policy can be pointed at a url, so one file covers both ways of scanning")
	void keepsPolicyWhenLoadingAUrl(@TempDir Path temp) throws IOException {
		Pa11yConfig config = write(temp, "{\"rootElement\": \"#main\"}");

		ScanRequest request = config.forUrl("https://example.com/basket").build();

		assertFalse(request.scanCurrentPage());
		assertEquals("https://example.com/basket", request.url());
		assertEquals("#main", request.rootElement());
	}

	@Test
	@DisplayName("relative paths are resolved against the config file, not the working directory")
	void resolvesPathsAgainstTheFile(@TempDir Path temp) throws IOException {
		// The point of the file living in the base directory is that it travels with the
		// project. A path that moved depending on which module the suite was started from
		// would defeat that.
		Pa11yConfig config = write(temp, "{\"reportsDir\": \"target/pa11yReports\"}");

		assertEquals(temp.resolve("target").resolve("pa11yReports").toAbsolutePath().normalize(),
				config.reportsDir());
	}

	@Test
	@DisplayName("an absolute path in the file is left alone")
	void keepsAbsolutePaths(@TempDir Path temp) throws IOException {
		Path absolute = temp.resolve("elsewhere").toAbsolutePath();
		Pa11yConfig config = write(temp,
				"{\"reportsDir\": " + quoted(absolute) + "}");

		assertEquals(absolute.normalize(), config.reportsDir());
	}

	@Test
	@DisplayName("command line values override the file, and its lists are added to rather than replaced")
	void overridesMergeLists(@TempDir Path temp) throws IOException {
		Pa11yConfig config = write(temp, """
				{
				  "standard": "WCAG2A",
				  "ignore": ["FROM_CONFIG"],
				  "actions": ["click element #cookie-accept"]
				}""");

		ScanRequest request = config.currentPage()
				.standard(Standard.WCAG2AAA)
				.ignore("FROM_FLAG")
				.actions("click element #login")
				.build();

		assertEquals(Standard.WCAG2AAA, request.standard());
		assertEquals(List.of("FROM_CONFIG", "FROM_FLAG"), request.ignore());
		// Order matters: the cookie banner the whole project has to click through comes first.
		assertEquals(List.of("click element #cookie-accept", "click element #login"), request.actions());
	}

	@Test
	@DisplayName("chromeUrl is refused, because the Grid address is different for every session")
	void refusesChromeUrl(@TempDir Path temp) {
		for (String key : List.of("chromeUrl", "chrome", "chromeDebuggerUrl")) {
			Pa11yException failure = assertThrows(Pa11yException.class,
					() -> write(temp, "{\"" + key + "\": \"ws://grid:4444/session/abc/se/cdp\"}"), key);

			assertTrue(failure.getMessage().contains("session id"), key + ": " + failure.getMessage());
			assertTrue(failure.getMessage().contains("--chrome"), key + ": " + failure.getMessage());
		}
	}

	@Test
	@DisplayName("an underscored key is a note, since JSON has no comments")
	void treatsUnderscoredKeysAsNotes(@TempDir Path temp) throws IOException {
		Pa11yConfig config = write(temp, """
				{
				  "_about": "the policy for this project",
				  "_why_ignore": "accepted by the application owner, not fixed per page",
				  "ignore": ["WCAG2AA.X"],
				  "standard": "WCAG2A"
				}""");

		assertEquals(Standard.WCAG2A, config.standard());
		assertEquals(List.of("WCAG2AA.X"), config.ignore());
	}

	@Test
	@DisplayName("the project's own pa11yConfig.json is valid, so a build cannot ship a broken one")
	void theProjectsOwnConfigIsValid() {
		// This file sits in the repository root and is discovered by anything run from it,
		// including this test suite. A typo in it would break every command at once, so it is
		// worth one assertion that it still parses.
		Path own = Path.of("pa11yConfig.json");
		if (!Files.isRegularFile(own)) {
			return;
		}
		Pa11yConfig config = Pa11yConfig.load(own);

		assertEquals(Standard.WCAG2AA, config.standard());
		assertEquals(own.toAbsolutePath().getParent().resolve("target").resolve("pa11yReports"),
				config.reportsDir());
	}

	@Test
	@DisplayName("a misspelled key is refused and the known ones are listed")
	void refusesUnknownKey(@TempDir Path temp) {
		// Quietly ignoring this is the failure mode worth preventing: the whole suite would
		// run without the root element anyone configured, and nothing would say so.
		Pa11yException failure = assertThrows(Pa11yException.class,
				() -> write(temp, "{\"rootElment\": \"#main\"}"));

		assertTrue(failure.getMessage().contains("rootElment"), failure.getMessage());
		assertTrue(failure.getMessage().contains("rootElement"), failure.getMessage());
	}

	@Test
	@DisplayName("per-scan settings are refused with what to pass instead")
	void refusesPerScanKeys(@TempDir Path temp) {
		Pa11yException url = assertThrows(Pa11yException.class,
				() -> write(temp, "{\"url\": \"https://example.com\"}"));
		assertTrue(url.getMessage().contains("--url"), url.getMessage());

		Pa11yException name = assertThrows(Pa11yException.class,
				() -> write(temp, "{\"name\": \"checkout\"}"));
		assertTrue(name.getMessage().contains("--name"), name.getMessage());

		Pa11yException runner = assertThrows(Pa11yException.class,
				() -> write(temp, "{\"runner\": \"axe\"}"));
		assertTrue(runner.getMessage().contains("HTML CodeSniffer"), runner.getMessage());
	}

	@Test
	@DisplayName("a value of the wrong shape names the key that holds it")
	void refusesWrongTypes(@TempDir Path temp) {
		assertTrue(failureFor(temp, "{\"timeout\": \"90\"}").contains("whole number of seconds"));
		assertTrue(failureFor(temp, "{\"wait\": -1}").contains("milliseconds"));
		assertTrue(failureFor(temp, "{\"debug\": \"yes\"}").contains("true or false"));
		assertTrue(failureFor(temp, "{\"rootElement\": 7}").contains("non-empty string"));
		assertTrue(failureFor(temp, "{\"ignore\": \"ONE_CODE\"}").contains("array of strings"));
		assertTrue(failureFor(temp, "{\"ignore\": [\"\"]}").contains("non-empty strings"));
		assertTrue(failureFor(temp, "{\"standard\": \"WCAG9\"}").contains("WCAG2A, WCAG2AA or WCAG2AAA"));
		assertTrue(failureFor(temp, "{\"standard\": \"WCAG9\"}").contains("WCAG9"));
		assertTrue(failureFor(temp, "[]").contains("must hold a JSON object"));
	}

	@Test
	@DisplayName("a file named explicitly but not there is an error, not a reason to search elsewhere")
	void refusesMissingNamedFile(@TempDir Path temp) {
		Pa11yException failure = assertThrows(Pa11yException.class,
				() -> Pa11yConfig.discover(temp.resolve("nowhere.json")));

		assertTrue(failure.getMessage().contains("nowhere.json"), failure.getMessage());
		assertTrue(failure.getMessage().contains("--config"), failure.getMessage());
	}

	@Test
	@DisplayName("the system property names the file to read")
	void readsTheFileNamedByTheProperty(@TempDir Path temp) throws IOException {
		Path file = temp.resolve(Pa11yConfig.FILE_NAME);
		Files.writeString(file, "{\"standard\": \"WCAG2A\"}", StandardCharsets.UTF_8);

		System.setProperty(Pa11yConfig.CONFIG_FILE_PROPERTY, file.toString());
		try {
			Pa11yConfig config = Pa11yConfig.discover();

			assertEquals(Standard.WCAG2A, config.standard());
			assertEquals(file.toAbsolutePath().normalize(), config.source());
		} finally {
			System.clearProperty(Pa11yConfig.CONFIG_FILE_PROPERTY);
		}
	}

	private static String failureFor(Path directory, String json) {
		Pa11yException failure = assertThrows(Pa11yException.class,
				() -> write(directory, json), json);
		return failure.getMessage();
	}

	private static String quoted(Path path) {
		return "\"" + path.toString().replace("\\", "\\\\") + "\"";
	}

	private static Pa11yConfig write(Path directory, String json) throws IOException {
		Path file = directory.resolve(Pa11yConfig.FILE_NAME);
		Files.writeString(file, json, StandardCharsets.UTF_8);
		return Pa11yConfig.load(file);
	}
}
