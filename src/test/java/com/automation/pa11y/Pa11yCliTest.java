package com.automation.pa11y;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
