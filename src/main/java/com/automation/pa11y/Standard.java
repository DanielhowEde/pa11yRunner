package com.automation.pa11y;

/**
 * The accessibility standard to test against.
 *
 * <p>Pa11y treats the standard as an upper bound and tests everything at or below it, so
 * {@link #WCAG2AA} also reports level A failures.
 *
 * <p>This is what HTML CodeSniffer tests against, and it appears at the front of every rule
 * code it reports.
 */
public enum Standard {

	/** WCAG 2.1 level A. */
	WCAG2A("WCAG2A"),

	/** WCAG 2.1 level AA. The usual target, and the default. */
	WCAG2AA("WCAG2AA"),

	/** WCAG 2.1 level AAA. */
	WCAG2AAA("WCAG2AAA");

	private final String wireName;

	Standard(String wireName) {
		this.wireName = wireName;
	}

	/**
	 * @return the value Pa11y expects for its {@code standard} option
	 */
	public String wireName() {
		return wireName;
	}

	/**
	 * Looks a standard up by name, case-insensitively.
	 *
	 * <p>Strict, unlike {@link IssueType#fromWireName(String)}. A standard is typed by a
	 * person, on a command line or in a config file, and a misspelling that quietly became
	 * WCAG2AA would mean a run that did not test what it was asked to.
	 *
	 * @param wireName the name to look up
	 * @return the matching standard
	 * @throws IllegalArgumentException if nothing matches
	 */
	public static Standard fromWireName(String wireName) {
		if (wireName != null) {
			for (Standard candidate : values()) {
				if (candidate.wireName.equalsIgnoreCase(wireName.trim())) {
					return candidate;
				}
			}
		}
		throw new IllegalArgumentException(
				"Unknown standard '" + wireName + "'. Use WCAG2A, WCAG2AA or WCAG2AAA.");
	}
}
