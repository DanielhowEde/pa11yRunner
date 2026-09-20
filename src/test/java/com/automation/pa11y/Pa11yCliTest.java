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
	@DisplayName("--help explains itself and succeeds")
	void printsHelp() {
		assertEquals(Pa11yCli.EXIT_CLEAN, run("--help"));
		assertTrue(output().contains("--chrome"), output());
		assertTrue(output().contains("Exit codes"), output());
	}

	@Test
	@DisplayName("a missing --url is a usage error, not a failed scan")
	void requiresUrl() {
		assertEquals(Pa11yCli.EXIT_USAGE, run());
		assertTrue(errors().contains("--url is required"), errors());
	}

	@Test
	@DisplayName("an unknown option is rejected rather than ignored")
	void rejectsUnknownOption() {
		assertEquals(Pa11yCli.EXIT_USAGE, run("--url", "https://example.com", "--nonsense"));
		assertTrue(errors().contains("Unknown option"), errors());
	}

	@Test
	@DisplayName("an option missing its value is rejected rather than swallowing the next flag")
	void rejectsOptionWithoutValue() {
		assertEquals(Pa11yCli.EXIT_USAGE, run("--url"));
		assertTrue(errors().contains("needs a value"), errors());
	}

	@Test
	@DisplayName("malformed viewport, header, standard and engine values are explained")
	void explainsMalformedValues() {
		assertEquals(Pa11yCli.EXIT_USAGE, run("--url", "https://example.com", "--viewport", "wide"));
		assertTrue(errors().contains("WIDTHxHEIGHT"), errors());

		reset();
		assertEquals(Pa11yCli.EXIT_USAGE, run("--url", "https://example.com", "--header", "no-separator"));
		assertTrue(errors().contains("NAME:VALUE"), errors());

		reset();
		assertEquals(Pa11yCli.EXIT_USAGE, run("--url", "https://example.com", "--standard", "WCAG9"));
		assertTrue(errors().contains("Unknown standard"), errors());

		reset();
		assertEquals(Pa11yCli.EXIT_USAGE, run("--url", "https://example.com", "--engine", "lighthouse"));
		assertTrue(errors().contains("Unknown engine"), errors());
	}

	@Test
	@DisplayName("a scan that cannot run exits 3, which is distinct from the 0 a clean page gets")
	void unreachableChromeIsNotACleanPage() {
		// Port 1 is reserved, so nothing is listening. The point of the assertion is the
		// code: a scanner that could not run must never be mistaken for a passing page.
		int exitCode = run("--url", "https://example.com", "--chrome", "http://127.0.0.1:1");

		assertEquals(Pa11yCli.EXIT_SCAN_FAILED, exitCode);
	}

	/**
	 * @param args the command line
	 * @return the exit code
	 */
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
