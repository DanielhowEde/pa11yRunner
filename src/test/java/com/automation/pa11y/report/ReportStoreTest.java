package com.automation.pa11y.report;

import com.automation.pa11y.Issue;
import com.automation.pa11y.IssueType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportStoreTest {

	@Test
	@DisplayName("a saved page reads back with its issues intact")
	void roundTripsAPage(@TempDir Path temp) {
		ReportStore store = new ReportStore(temp);
		store.write(page("checkout", issue("missing-alt"), issue("contrast")));

		List<PageReport> read = store.readAll();

		assertEquals(1, read.size());
		assertEquals("checkout", read.get(0).name());
		assertEquals(2, read.get(0).issues().size());
		assertEquals("missing-alt", read.get(0).issues().get(0).code());
		assertEquals(IssueType.ERROR, read.get(0).issues().get(0).type());
		assertEquals("https://example.com/checkout", read.get(0).pageUrl());
	}

	@Test
	@DisplayName("scanning the same name twice replaces the earlier result instead of doubling it")
	void rescanReplaces(@TempDir Path temp) {
		ReportStore store = new ReportStore(temp);
		store.write(page("checkout", issue("missing-alt")));
		store.write(page("checkout"));

		List<PageReport> read = store.readAll();

		assertEquals(1, read.size());
		assertTrue(read.get(0).issues().isEmpty());
	}

	@Test
	@DisplayName("reading an empty or missing directory gives nothing rather than failing")
	void missingDirectoryIsEmpty(@TempDir Path temp) {
		assertTrue(new ReportStore(temp.resolve("not-created-yet")).readAll().isEmpty());
		assertTrue(new ReportStore(temp).readAll().isEmpty());
	}

	@Test
	@DisplayName("clean removes this tool's reports and leaves anything else alone")
	void cleanOnlyRemovesOurOwnFiles(@TempDir Path temp) throws IOException {
		ReportStore store = new ReportStore(temp);
		store.write(page("checkout"));
		store.write(page("basket"));
		Path somebodyElses = temp.resolve("config.json");
		Files.writeString(somebodyElses, "{\"keep\":\"me\"}", StandardCharsets.UTF_8);

		ReportStore.CleanResult result = store.clean(false);

		assertEquals(2, result.deleted().size());
		assertEquals(List.of(somebodyElses), result.kept());
		assertTrue(Files.exists(somebodyElses), "a file this tool did not write must survive");
		assertTrue(store.readAll().isEmpty());
	}

	@Test
	@DisplayName("clean --all removes every json, for when that is genuinely what is wanted")
	void cleanAllRemovesEverything(@TempDir Path temp) throws IOException {
		ReportStore store = new ReportStore(temp);
		store.write(page("checkout"));
		Files.writeString(temp.resolve("config.json"), "{}", StandardCharsets.UTF_8);

		ReportStore.CleanResult result = store.clean(true);

		assertEquals(2, result.deleted().size());
		assertTrue(result.kept().isEmpty());
	}

	@Test
	@DisplayName("cleaning a directory that does not exist is not an error, so it is safe as step one")
	void cleaningNothingIsFine(@TempDir Path temp) {
		ReportStore.CleanResult result = new ReportStore(temp.resolve("never-created")).clean(false);

		assertTrue(result.deleted().isEmpty());
		assertTrue(result.kept().isEmpty());
	}

	@Test
	@DisplayName("a name cannot escape the reports directory")
	void nameCannotTraversePaths(@TempDir Path temp) {
		// The name comes from a command line and becomes a path, so anything that could climb
		// out of the directory has to be stripped rather than escaped.
		ReportStore store = new ReportStore(temp);
		Path written = store.write(page("../../etc/passwd"));

		assertEquals(temp.toAbsolutePath().normalize(), written.toAbsolutePath().normalize().getParent());
		assertFalse(written.getFileName().toString().contains(".."));
	}

	@Test
	@DisplayName("names become predictable file names")
	void namesBecomeSlugs() {
		assertEquals("customer-details.json", ReportStore.fileName("Customer Details"));
		assertEquals("checkout-step-2.json", ReportStore.fileName("Checkout / Step 2"));
		assertEquals("page.json", ReportStore.fileName("   "));
	}

	@Test
	@DisplayName("a json file that is not one of ours is skipped rather than breaking the run")
	void ignoresForeignJson(@TempDir Path temp) throws IOException {
		Files.writeString(temp.resolve("notes.json"), "[1, 2, 3]", StandardCharsets.UTF_8);
		Files.writeString(temp.resolve("broken.json"), "this is not json at all", StandardCharsets.UTF_8);
		new ReportStore(temp).write(page("checkout"));

		List<PageReport> read = new ReportStore(temp).readAll();

		assertEquals(1, read.size());
		assertEquals("checkout", read.get(0).name());
	}

	/**
	 * @param name   the page name
	 * @param issues what it found
	 * @return the page report
	 */
	private static PageReport page(String name, Issue... issues) {
		return new PageReport(name, "https://example.com/checkout", "https://example.com/checkout",
				"Checkout", Instant.parse("2026-09-20T10:15:30Z"), Duration.ofMillis(1200),
				"WCAG2AA", List.of("htmlcs"), List.of(issues));
	}

	/**
	 * @param code the rule identifier
	 * @return an error-level issue
	 */
	private static Issue issue(String code) {
		return new Issue(code, IssueType.ERROR, 1, "Something is wrong", "<img src=\"a.png\">",
				"img.hero", "htmlcs", null);
	}
}
