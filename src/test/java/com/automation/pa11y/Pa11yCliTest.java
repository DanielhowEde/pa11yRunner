package com.automation.pa11y;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Pa11yCliTest {

	private final ByteArrayOutputStream out = new ByteArrayOutputStream();
	private final ByteArrayOutputStream err = new ByteArrayOutputStream();

	@Test
	@DisplayName("no arguments prints the three commands rather than doing something surprising")
	void noArgumentsShowsUsage() {
		assertEquals(Pa11yCli.EXIT_USAGE, run());
		assertTrue(errors().contains("clean"), errors());
		assertTrue(errors().contains("scan"), errors());
		assertTrue(errors().contains("report"), errors());
	}

	@Test
	@DisplayName("the default output directory is under the user's home target directory")
	void defaultsToHomeTargetReports() {
		assertEquals(Path.of(System.getProperty("user.home"), "target", "pa11yReports").toString(),
				Pa11yCli.DEFAULT_REPORTS_DIR);
	}

	@Test
	@DisplayName("help explains the whole workflow, and each command on its own")
	void helpExplainsEachCommand() {
		assertEquals(Pa11yCli.EXIT_CLEAN, run("help"));
		assertTrue(output().contains("A typical run"), output());

		reset();
		assertEquals(Pa11yCli.EXIT_CLEAN, run("help", "scan"));
		assertTrue(output().contains("--name"), output());

		reset();
		assertEquals(Pa11yCli.EXIT_CLEAN, run("help", "report"));
		assertTrue(output().contains("--out"), output());

		reset();
		assertEquals(Pa11yCli.EXIT_CLEAN, run("help", "clean"));
		assertTrue(output().contains("--all"), output());
	}

	@Test
	@DisplayName("an unknown command is rejected")
	void rejectsUnknownCommand() {
		assertEquals(Pa11yCli.EXIT_USAGE, run("scandalise"));
		assertTrue(errors().contains("Unknown command"), errors());
	}

	@Test
	@DisplayName("cleardown is accepted as a name for clean, since that is what a run calls the step")
	void acceptsCleardownAlias() {
		assertEquals(Pa11yCli.EXIT_CLEAN, run("help", "cleardown"));
		assertTrue(output().contains("empty the reports directory"), output());
	}

	@Test
	@DisplayName("scan needs a name to save the page under")
	void scanRequiresName() {
		assertEquals(Pa11yCli.EXIT_USAGE, run("scan", "--chrome", "grid:9222"));
		assertTrue(errors().contains("--name is required"), errors());
	}

	@Test
	@DisplayName("a misspelled option is rejected rather than silently ignored")
	void rejectsUnknownOption() {
		assertEquals(Pa11yCli.EXIT_USAGE, run("scan", "--name", "checkout", "--reports-dirr", "out"));
		assertTrue(errors().contains("Unknown option"), errors());
	}

	@Test
	@DisplayName("an option missing its value is rejected rather than swallowing the next flag")
	void rejectsOptionWithoutValue() {
		assertEquals(Pa11yCli.EXIT_USAGE, run("scan", "--name"));
		assertTrue(errors().contains("needs a value"), errors());
	}

	@Test
	@DisplayName("malformed viewport, header, standard and engine values are explained")
	void explainsMalformedValues() {
		assertEquals(Pa11yCli.EXIT_USAGE, run("scan", "--name", "a", "--standard", "WCAG9"));
		assertTrue(errors().contains("Unknown standard"), errors());

		reset();
		assertEquals(Pa11yCli.EXIT_USAGE, run("scan", "--name", "a", "--timeout", "soon"));
		assertTrue(errors().contains("expects a number"), errors());
	}

	@Test
	@DisplayName("options that were dropped are rejected, not quietly ignored")
	void droppedOptionsAreRejected() {
		// Silently accepting one of these would be the worst outcome: the run would look
		// configured and would not be. --engine is gone because there is only one engine;
		// the rest only ever applied when loading a URL.
		for (String[] gone : new String[][] {
				{ "--engine", "htmlcs" },
				{ "--header", "Authorization: Bearer x" },
				{ "--viewport", "1280x1024" },
				{ "--screenshot", "page.png" } }) {
			reset();
			assertEquals(Pa11yCli.EXIT_USAGE, run("scan", "--name", "a", gone[0], gone[1]), gone[0]);
			assertTrue(errors().contains("Unknown option"), gone[0] + ": " + errors());
		}
	}

	@Test
	@DisplayName("a scan that cannot reach Chrome exits 3, distinct from the 0 a working scan gets")
	void unreachableChromeIsNotSuccess() {
		// Port 1 is reserved, so nothing is listening. The point is the code: a scanner that
		// could not run must never be mistaken for a page with nothing wrong.
		int exitCode = run("scan", "--name", "checkout", "--chrome", "http://127.0.0.1:1");

		assertEquals(Pa11yCli.EXIT_SCAN_FAILED, exitCode);
	}

	@Test
	@DisplayName("report exits 3 when there is nothing to combine, because that means the scans did not run")
	void reportWithNothingToCombineFails() {
		int exitCode = run("report", "--reports-dir", "target/no-such-reports-directory");

		assertEquals(Pa11yCli.EXIT_SCAN_FAILED, exitCode);
		assertTrue(errors().contains("No page reports found"), errors());
	}

	@Test
	@DisplayName("a config file that cannot be understood is an argument error, not a failed scan")
	void brokenConfigIsAUsageError(@TempDir Path temp) throws IOException {
		Path config = temp.resolve(Pa11yConfig.FILE_NAME);
		Files.writeString(config, "{\"chromeUrl\": \"ws://grid:4444/session/abc/se/cdp\"}",
				StandardCharsets.UTF_8);

		assertEquals(Pa11yCli.EXIT_USAGE, run("scan", "--name", "a", "--config", config.toString()));
		assertTrue(errors().contains("session id"), errors());
	}

	@Test
	@DisplayName("a --config file that is not there stops the run rather than scanning under other defaults")
	void missingConfigStopsTheRun(@TempDir Path temp) {
		assertEquals(Pa11yCli.EXIT_USAGE,
				run("scan", "--name", "a", "--config", temp.resolve("nope.json").toString()));
		assertTrue(errors().contains("No config file at"), errors());
	}

	@Test
	@DisplayName("the config file can say where reports go, so the clean step need not be told twice")
	void configSuppliesTheReportsDirectory(@TempDir Path temp) throws IOException {
		Path config = temp.resolve(Pa11yConfig.FILE_NAME);
		Files.writeString(config, "{\"reportsDir\": \"out\"}", StandardCharsets.UTF_8);

		assertEquals(Pa11yCli.EXIT_CLEAN, run("clean", "--config", config.toString()));
		assertTrue(output().contains(temp.resolve("out").toString()), output());
	}

	@Test
	@DisplayName("a scan that could not run is saved, and the report says so instead of reading clean")
	void aFailedScanReachesTheReport(@TempDir Path temp) {
		// Port 1 is reserved, so nothing is listening. The scan cannot run, and that has to
		// survive as far as the report rather than leaving a page-shaped hole in it.
		assertEquals(Pa11yCli.EXIT_SCAN_FAILED,
				run("scan", "--name", "Dashboard", "--chrome", "http://127.0.0.1:1",
						"--reports-dir", temp.toString()));

		reset();
		assertEquals(Pa11yCli.EXIT_CLEAN,
				run("report", "--reports-dir", temp.toString(),
						"--out", temp.resolve("combined-report.html").toString()));
		assertTrue(errors().contains("did not run"), errors());

		// And with --fail-on-error it fails the build, because not knowing is a problem too.
		reset();
		assertEquals(Pa11yCli.EXIT_ERRORS_FOUND,
				run("report", "--reports-dir", temp.toString(), "--fail-on-error",
						"--out", temp.resolve("combined-report.html").toString()));
	}

	@Test
	@DisplayName("every command reports a broken config the same way, as an argument error")
	void everyCommandRejectsABrokenConfigAlike(@TempDir Path temp) throws IOException {
		Path config = temp.resolve(Pa11yConfig.FILE_NAME);
		Files.writeString(config, "{\"rootElment\": \"#main\"}", StandardCharsets.UTF_8);

		for (String[] args : new String[][] {
				{ "scan", "--name", "a", "--config", config.toString() },
				{ "clean", "--config", config.toString() },
				{ "report", "--config", config.toString() } }) {
			reset();
			int exitCode = run(args);

			assertEquals(Pa11yCli.EXIT_USAGE, exitCode, args[0] + ": " + errors());
			assertTrue(errors().contains("rootElment"), args[0] + ": " + errors());
		}
	}

	@Test
	@DisplayName("a flag still beats the config file, so one run can be told to do something else")
	void flagBeatsTheConfigFile(@TempDir Path temp) throws IOException {
		Path config = temp.resolve(Pa11yConfig.FILE_NAME);
		Files.writeString(config, "{\"reportsDir\": \"out\"}", StandardCharsets.UTF_8);
		Path elsewhere = temp.resolve("elsewhere");

		assertEquals(Pa11yCli.EXIT_CLEAN,
				run("clean", "--config", config.toString(), "--reports-dir", elsewhere.toString()));

		assertTrue(output().contains(elsewhere.toString()), output());
		assertFalse(output().contains(temp.resolve("out").toString()), output());
	}

	private int run(String... args) {
		return Pa11yCli.run(args,
				new PrintStream(out, true, StandardCharsets.UTF_8),
				new PrintStream(err, true, StandardCharsets.UTF_8));
	}

	private void reset() {
		out.reset();
		err.reset();
	}

	private String output() {
		return out.toString(StandardCharsets.UTF_8);
	}

	private String errors() {
		return err.toString(StandardCharsets.UTF_8);
	}
}
