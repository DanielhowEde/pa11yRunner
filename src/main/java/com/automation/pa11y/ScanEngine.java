package com.automation.pa11y;

/**
 * An engine that inspects the page. Pa11y calls these "runners".
 *
 * <p>More than one can be used in a single scan, in which case the issues from each are
 * merged and every {@link Issue} says which engine found it. The two engines overlap but do
 * not agree: running both finds more, and duplicates the findings they share.
 */
public enum ScanEngine {

	/** HTML CodeSniffer. Pa11y's default, and the one that honours {@link Standard}. */
	HTMLCS("htmlcs"),

	/** axe-core. Has its own rule set, and ignores {@link Standard}. */
	AXE("axe");

	private final String wireName;

	ScanEngine(String wireName) {
		this.wireName = wireName;
	}

	/**
	 * @return the value Pa11y expects in its {@code runners} option
	 */
	public String wireName() {
		return wireName;
	}
}
