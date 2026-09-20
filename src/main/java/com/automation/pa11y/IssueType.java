package com.automation.pa11y;

/**
 * The severity Pa11y assigns to an issue.
 *
 * <p>Only {@link #ERROR} is reported by default. Warnings and notices have to be asked for
 * with {@link ScanRequest.Builder#includeWarnings(boolean)} and
 * {@link ScanRequest.Builder#includeNotices(boolean)}, which mirrors Pa11y's own defaults.
 */
public enum IssueType {

	/** A definite failure against the standard being tested. */
	ERROR("error"),

	/** Something that is probably a failure but that the runner could not decide alone. */
	WARNING("warning"),

	/** Something a human needs to look at; not a failure in itself. */
	NOTICE("notice"),

	/** A type this version of the library does not recognise, so that new Pa11y output never breaks a run. */
	UNKNOWN("");

	private final String wireName;

	IssueType(String wireName) {
		this.wireName = wireName;
	}

	/**
	 * @return the value Pa11y uses in its JSON output
	 */
	public String wireName() {
		return wireName;
	}

	/**
	 * @param wireName the value from Pa11y's JSON output, may be {@code null}
	 * @return the matching type, or {@link #UNKNOWN} if there is no match
	 */
	public static IssueType fromWireName(String wireName) {
		if (wireName != null) {
			for (IssueType type : values()) {
				if (type.wireName.equalsIgnoreCase(wireName)) {
					return type;
				}
			}
		}
		return UNKNOWN;
	}
}
