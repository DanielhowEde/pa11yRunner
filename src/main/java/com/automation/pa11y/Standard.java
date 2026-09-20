package com.automation.pa11y;

/**
 * The accessibility standard to test against.
 *
 * <p>Pa11y treats the standard as an upper bound and tests everything at or below it, so
 * {@link #WCAG2AA} also reports level A failures.
 *
 * <p>Only the HTML CodeSniffer runner uses this. The axe runner has its own rule set and
 * ignores the standard entirely.
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
}
