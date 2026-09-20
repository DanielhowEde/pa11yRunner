package com.automation.pa11y.internal;

import com.automation.pa11y.Pa11yException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScannerLocationTest {

	@Test
	@DisplayName("finds node_modules inside a configured scanner directory")
	void findsWithinScannerDirectory(@TempDir Path temp) throws IOException {
		Path scanner = installedScannerAt(temp.resolve("scanner"));

		Path modules = ScannerLocation.discoverModulesDir(scanner);

		assertEquals(scanner.resolve("node_modules").toAbsolutePath().normalize(), modules);
	}

	@Test
	@DisplayName("accepts being pointed straight at node_modules, since that is an easy mistake to make")
	void acceptsModulesDirectoryItself(@TempDir Path temp) throws IOException {
		Path scanner = installedScannerAt(temp.resolve("scanner"));

		Path modules = ScannerLocation.discoverModulesDir(scanner.resolve("node_modules"));

		assertEquals(scanner.resolve("node_modules").toAbsolutePath().normalize(), modules);
	}

	@Test
	@DisplayName("an empty directory is not an install, even though node_modules exists")
	void rejectsDirectoryWithoutPa11y(@TempDir Path temp) throws IOException {
		Path scanner = temp.resolve("scanner");
		Files.createDirectories(scanner.resolve("node_modules"));

		assertThrows(Pa11yException.class, () -> ScannerLocation.discoverModulesDir(scanner));
	}

	@Test
	@DisplayName("a configured directory that is wrong fails instead of quietly using a different install")
	void doesNotFallBackFromAConfiguredDirectory(@TempDir Path temp) {
		// This project has a real scanner/node_modules/pa11y next to the working directory,
		// so a fallback search would succeed here. It must not: silently scanning with some
		// other copy of Pa11y, at some other version, is worse than refusing to start.
		assertThrows(Pa11yException.class, () -> ScannerLocation.discoverModulesDir(temp.resolve("nowhere")));
	}

	@Test
	@DisplayName("the failure says how to install, which setting was at fault, and what it expected to find")
	void failureIsActionable(@TempDir Path temp) {
		Pa11yException thrown = assertThrows(Pa11yException.class,
				() -> ScannerLocation.discoverModulesDir(temp.resolve("nowhere")));

		assertTrue(thrown.getMessage().contains("npm ci"), thrown.getMessage());
		assertTrue(thrown.getMessage().contains("scannerDirectory"), thrown.getMessage());
		assertTrue(thrown.getMessage().contains("nowhere"), thrown.getMessage());
		assertTrue(thrown.getMessage().contains("node_modules"), thrown.getMessage());
	}

	/**
	 * @param scanner where to build a stand-in install
	 * @return the scanner directory
	 * @throws IOException if it cannot be created
	 */
	private static Path installedScannerAt(Path scanner) throws IOException {
		Files.createDirectories(scanner.resolve("node_modules").resolve("pa11y"));
		return scanner;
	}
}
