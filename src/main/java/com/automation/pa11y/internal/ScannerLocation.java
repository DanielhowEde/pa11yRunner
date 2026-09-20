package com.automation.pa11y.internal;

import com.automation.pa11y.Pa11yException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds the {@code node_modules} that holds Pa11y.
 *
 * <p>The Node dependencies cannot travel inside the jar, so one directory on disk has to
 * have had {@code npm ci} run in it.
 *
 * <p>Configuration wins over searching, and a configured directory that turns out not to be
 * an install is an error rather than a reason to look elsewhere. Falling back would be worse
 * than failing: the run would quietly use some other copy of Pa11y, at some other version,
 * and nothing would say so.
 */
public final class ScannerLocation {

	private static final String SCANNER_DIR_PROPERTY = "pa11y.scanner.dir";
	private static final String SCANNER_DIR_ENV = "PA11Y_SCANNER_DIR";

	/** How far up from the working directory to look, which covers a module inside a reactor build. */
	private static final int ANCESTORS_SEARCHED = 5;

	private static final String INSTALL_ADVICE = """
			The Node dependencies cannot be bundled in the jar, so the scanner directory has to exist
			on the machine running the tests and have been installed once:

			    cd scanner && npm ci""";

	private ScannerLocation() {
	}

	/**
	 * @param configured the directory set on the builder, or {@code null} to search
	 * @return the {@code node_modules} directory containing Pa11y
	 * @throws Pa11yException if no installed scanner can be found
	 */
	public static Path discoverModulesDir(Path configured) {
		Configured explicit = explicitlyConfigured(configured);
		if (explicit != null) {
			Path modules = modulesWithin(explicit.directory());
			if (modules == null) {
				throw new Pa11yException(configuredButNotInstalled(explicit));
			}
			return modules.toAbsolutePath().normalize();
		}
		return search();
	}

	/**
	 * @param configured the directory set on the builder, or {@code null}
	 * @return the first explicit setting, or {@code null} if nothing was configured
	 */
	private static Configured explicitlyConfigured(Path configured) {
		if (configured != null) {
			return new Configured("Pa11yRunner.builder().scannerDirectory(...)", configured);
		}
		String property = System.getProperty(SCANNER_DIR_PROPERTY);
		if (property != null && !property.isBlank()) {
			return new Configured("the " + SCANNER_DIR_PROPERTY + " system property", Path.of(property.trim()));
		}
		String environment = System.getenv(SCANNER_DIR_ENV);
		if (environment != null && !environment.isBlank()) {
			return new Configured("the " + SCANNER_DIR_ENV + " environment variable", Path.of(environment.trim()));
		}
		return null;
	}

	/**
	 * Looks for a scanner near the working directory. A test suite is usually started from
	 * the module root, but in a multi-module build it can be a level or two below.
	 *
	 * @return the {@code node_modules} directory containing Pa11y
	 */
	private static Path search() {
		List<Path> searched = new ArrayList<>();
		Path directory = Path.of("").toAbsolutePath();

		for (int depth = 0; depth <= ANCESTORS_SEARCHED && directory != null; depth++) {
			for (Path candidate : List.of(directory.resolve("scanner"), directory)) {
				Path modules = modulesWithin(candidate);
				if (modules != null) {
					return modules.toAbsolutePath().normalize();
				}
				searched.add(candidate.normalize());
			}
			directory = directory.getParent();
		}

		StringBuilder message = new StringBuilder("Could not find an installed Pa11y scanner.")
				.append(System.lineSeparator()).append(System.lineSeparator())
				.append(INSTALL_ADVICE)
				.append(System.lineSeparator()).append(System.lineSeparator())
				.append("Then point at it with Pa11yRunner.builder().scannerDirectory(...), the ")
				.append(SCANNER_DIR_PROPERTY).append(" system property, or the ")
				.append(SCANNER_DIR_ENV).append(" environment variable.")
				.append(System.lineSeparator()).append(System.lineSeparator())
				.append("Looked for node_modules/pa11y in:");
		for (Path path : searched) {
			message.append(System.lineSeparator()).append("  - ").append(path);
		}
		throw new Pa11yException(message.toString());
	}

	/**
	 * Accepts either the scanner directory or the {@code node_modules} inside it, since
	 * both are reasonable things for someone to configure.
	 *
	 * @param candidate a directory to test
	 * @return the {@code node_modules} directory, or {@code null} if Pa11y is not installed there
	 */
	private static Path modulesWithin(Path candidate) {
		if (candidate == null) {
			return null;
		}
		Path asModulesDir = candidate.getFileName() != null
				&& candidate.getFileName().toString().equals("node_modules")
				? candidate
				: candidate.resolve("node_modules");
		return Files.isDirectory(asModulesDir.resolve("pa11y")) ? asModulesDir : null;
	}

	/**
	 * @param explicit what was configured, and where it came from
	 * @return an error message that names the setting at fault
	 */
	private static String configuredButNotInstalled(Configured explicit) {
		return """
				No Pa11y install at %s, which came from %s.

				%s

				Expected to find %s."""
				.formatted(
						explicit.directory().toAbsolutePath().normalize(),
						explicit.source(),
						INSTALL_ADVICE,
						explicit.directory().resolve("node_modules").resolve("pa11y").toAbsolutePath().normalize());
	}

	/**
	 * A configured scanner directory, remembered with its origin so the error can name it.
	 *
	 * @param source    where the setting came from
	 * @param directory the directory it named
	 */
	private record Configured(String source, Path directory) {
	}
}
