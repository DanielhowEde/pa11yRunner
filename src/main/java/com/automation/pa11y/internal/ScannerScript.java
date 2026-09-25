package com.automation.pa11y.internal;

import com.automation.pa11y.Pa11yException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Puts the scanner script somewhere Node can run it.
 *
 * <p>The script ships inside the jar and is unpacked to a temp file on first use. It is
 * deliberately not run from the scanner directory: that directory is installed once and
 * then tends to sit there for months, and a script that can drift from the Java driving it
 * is a bug waiting to happen. Shipping it in the jar makes the two impossible to mismatch.
 *
 * <p>The scanner directory is still needed for {@code node_modules}; the script is told
 * where that is rather than being moved next to it.
 */
public final class ScannerScript {

	private static final String RESOURCE = "/com/automation/pa11y/pa11y-cdp-scan.js";

	/** Unpacked once per JVM; scans are frequent and the file never changes within a run. */
	private static volatile Path extracted;

	private ScannerScript() {
	}

	/**
	 * @return the on-disk path of the scanner script
	 * @throws Pa11yException if the script cannot be unpacked
	 */
	public static Path path() {
		Path current = extracted;
		if (current != null && Files.isRegularFile(current)) {
			return current;
		}
		synchronized (ScannerScript.class) {
			if (extracted == null || !Files.isRegularFile(extracted)) {
				extracted = extract();
			}
			return extracted;
		}
	}

	private static Path extract() {
		try (InputStream source = ScannerScript.class.getResourceAsStream(RESOURCE)) {
			if (source == null) {
				throw new Pa11yException(
						"The scanner script is missing from the jar (" + RESOURCE + "). "
						+ "This build is broken rather than misconfigured.");
			}
			Path directory = Files.createTempDirectory("pa11y-runner");
			Path script = directory.resolve("pa11y-cdp-scan.js");
			Files.copy(source, script, StandardCopyOption.REPLACE_EXISTING);

			// Registered directory-first so that deleteOnExit, which unwinds in reverse,
			// removes the script before the directory holding it.
			directory.toFile().deleteOnExit();
			script.toFile().deleteOnExit();
			return script;
		} catch (IOException e) {
			throw new Pa11yException("Could not unpack the scanner script to a temporary file.", e);
		}
	}
}
