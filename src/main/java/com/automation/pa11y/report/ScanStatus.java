package com.automation.pa11y.report;

/**
 * Whether a saved page is a scan that ran or a scan that could not.
 *
 * <p>This distinction is the whole reason a failed scan is written to disk at all. If a scan
 * that could not reach the browser simply left no file, the page would be missing from the
 * combined report, and a report with nine clean pages instead of ten looks like good news. A
 * failure has to be a recorded fact, not an absence.
 */
public enum ScanStatus {

	/** The scan ran. The issue list is what the page actually has, however long or short. */
	COMPLETED("completed"),

	/** The scan could not be run. The issue list is empty because nothing was measured. */
	FAILED("failed");

	private final String wireName;

	ScanStatus(String wireName) {
		this.wireName = wireName;
	}

	/**
	 * @return the value used in the saved JSON
	 */
	public String wireName() {
		return wireName;
	}

	/**
	 * Lenient, because this is read back off disk: a file written by a newer version with a
	 * status this one has never heard of is still a file that did not say it completed, and
	 * treating it as a failure is the safe way to be wrong.
	 *
	 * @param wireName the value from the saved JSON, which may be {@code null} or unknown
	 * @return the status; {@link #COMPLETED} only when the file actually says so, except that
	 *         a missing status means a file written before statuses existed, which only ever
	 *         held completed scans
	 */
	public static ScanStatus fromWireName(String wireName) {
		if (wireName == null || wireName.isBlank()) {
			return COMPLETED;
		}
		for (ScanStatus status : values()) {
			if (status.wireName.equalsIgnoreCase(wireName.trim())) {
				return status;
			}
		}
		return FAILED;
	}
}
