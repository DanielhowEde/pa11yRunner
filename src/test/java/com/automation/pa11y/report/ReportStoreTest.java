package com.automation.pa11y.report;

import com.automation.pa11y.FailureKind;
import com.automation.pa11y.Issue;
import com.automation.pa11y.IssueType;
import com.automation.pa11y.Pa11yException;
import com.automation.pa11y.ScanRequest;
import com.automation.pa11y.ScanResult;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
		Files.writeString(temp.resolve("checkout.html"), "report", StandardCharsets.UTF_8);
		Files.writeString(temp.resolve("basket.html"), "report", StandardCharsets.UTF_8);
		Path somebodyElsesHtml = temp.resolve("notes.html");
		Files.writeString(somebodyElsesHtml, "keep me", StandardCharsets.UTF_8);
		Path somebodyElses = temp.resolve("config.json");
		Files.writeString(somebodyElses, "{\"keep\":\"me\"}", StandardCharsets.UTF_8);

		ReportStore.CleanResult result = store.clean(false);

		assertEquals(4, result.deleted().size());
		assertEquals(List.of(somebodyElses), result.kept());
		assertTrue(Files.exists(somebodyElses), "a file this tool did not write must survive");
		assertTrue(Files.exists(somebodyElsesHtml), "an unrelated HTML file must survive");
		assertFalse(Files.exists(temp.resolve("checkout.html")));
		assertFalse(Files.exists(temp.resolve("basket.html")));
		assertTrue(store.readAll().isEmpty());
	}

	@Test
	@DisplayName("clean --all removes every json, for when that is genuinely what is wanted")
	void cleanAllRemovesEverything(@TempDir Path temp) throws IOException {
		ReportStore store = new ReportStore(temp);
		store.write(page("checkout"));
		Files.writeString(temp.resolve("checkout.html"), "report", StandardCharsets.UTF_8);
		Files.writeString(temp.resolve("config.json"), "{}", StandardCharsets.UTF_8);

		ReportStore.CleanResult result = store.clean(true);

		assertEquals(3, result.deleted().size());
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
		assertEquals("checkout-step-2.html", ReportStore.htmlFileName("Checkout / Step 2"));
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

	@Test
	@DisplayName("a failed scan reads back as a failure, with its reason")
	void roundTripsAFailure(@TempDir Path temp) {
		new ReportStore(temp).write(PageReport.failed("checkout",
				ScanRequest.currentPage().build(), FailureKind.CHROME_UNREACHABLE, "nothing listening"));

		PageReport read = new ReportStore(temp).readAll().get(0);

		assertEquals(ScanStatus.FAILED, read.status());
		assertTrue(read.isFailed());
		assertEquals("CHROME_UNREACHABLE", read.failureKind());
		assertEquals("nothing listening", read.failureMessage());
		assertTrue(read.issues().isEmpty());
	}

	@Test
	@DisplayName("a file written before statuses existed reads back as the completed scan it is")
	void readsPreStatusFilesAsCompleted(@TempDir Path temp) throws IOException {
		// Schema 1 had no status field. Treating its absence as a failure would turn every
		// report from a previous version into a run that looks broken.
		Files.writeString(temp.resolve("checkout.json"), """
				{
				  "generator": "pa11y-runner",
				  "schema": 1,
				  "name": "checkout",
				  "issues": []
				}""", StandardCharsets.UTF_8);

		PageReport read = new ReportStore(temp).readAll().get(0);

		assertEquals(ScanStatus.COMPLETED, read.status());
		assertFalse(read.isFailed());
	}

	@Test
	@DisplayName("a password or hidden value is redacted on the way to disk")
	void redactsSensitiveValuesWhenSaving(@TempDir Path temp) {
		Issue leak = new Issue("WCAG2AA.X", IssueType.ERROR, 1, "No label",
				"<input type=\"password\" name=\"pw\" value=\"hunter2\">", "#pw", "htmlcs", null);
		ScanResult result = new ScanResult("https://example.com", "https://example.com", "Login",
				Duration.ofMillis(10), List.of(leak));

		new ReportStore(temp).write(PageReport.of("login", ScanRequest.currentPage().build(), result));

		PageReport read = new ReportStore(temp).readAll().get(0);
		assertFalse(read.issues().get(0).context().contains("hunter2"),
				read.issues().get(0).context());
	}

	@Test
	@DisplayName("a half-written file is never visible, because the write is a rename")
	void writesByRename(@TempDir Path temp) throws IOException {
		new ReportStore(temp).write(page("checkout"));

		try (var entries = Files.list(temp)) {
			assertTrue(entries.noneMatch(path -> path.getFileName().toString().endsWith(".partial")),
					"the temporary file must not be left behind");
		}
	}

	@Test
	@DisplayName("a directory that would be catastrophic to empty is refused before anything is deleted")
	void refusesDangerousDirectories(@TempDir Path temp) throws IOException {
		// Checked through the validator rather than through clean(), so that a regression in
		// the guard cannot make this test delete anything real.
		Path root = temp.getRoot();
		Pa11yException atRoot = assertThrows(Pa11yException.class,
				() -> new ReportStore(root).refuseDangerousDirectory());
		assertTrue(atRoot.getMessage().contains("filesystem root"), atRoot.getMessage());

		// A stand-in home, so the real one is never a candidate even momentarily.
		Path fakeHome = Files.createDirectory(temp.resolve("home"));
		String realHome = System.getProperty("user.home");
		System.setProperty("user.home", fakeHome.toString());
		try {
			Pa11yException atHome = assertThrows(Pa11yException.class,
					() -> new ReportStore(fakeHome).refuseDangerousDirectory());
			assertTrue(atHome.getMessage().contains("home directory"), atHome.getMessage());

			// A directory inside it is perfectly normal, and must still be allowed.
			new ReportStore(fakeHome.resolve("target").resolve("pa11yReports")).refuseDangerousDirectory();
		} finally {
			System.setProperty("user.home", realHome);
		}
	}

	private static PageReport page(String name, Issue... issues) {
		return new PageReport(name, "https://example.com/checkout", "https://example.com/checkout",
				"Checkout", Instant.parse("2026-09-20T10:15:30Z"), Duration.ofMillis(1200),
				"WCAG2AA", List.of("htmlcs"), List.of(issues));
	}

	private static Issue issue(String code) {
		return new Issue(code, IssueType.ERROR, 1, "Something is wrong", "<img src=\"a.png\">",
				"img.hero", "htmlcs", null);
	}
}
