package com.automation.pa11y;

/**
 * Why a scan could not be completed.
 *
 * <p>This is deliberately not about accessibility. A page with 200 errors produced a
 * perfectly successful scan; a page that never loaded produced no scan at all. Only the
 * second kind is a {@link ScanFailedException}, and this says which one it was.
 */
public enum FailureKind {

	/** Chrome's debugging endpoint could not be reached, or the connection dropped mid-scan. */
	CHROME_UNREACHABLE,

	/** Chrome was reached, but the page did not load. Usually a bad URL, DNS or TLS. */
	NAVIGATION_FAILED,

	/** The page took longer than the scan timeout. */
	TIMEOUT,

	/** The scanner's npm dependencies are missing. Run {@code npm ci} in the scanner directory. */
	SCANNER_NOT_INSTALLED,

	/** Node could not be started, exited unexpectedly, or produced no result file. */
	SCANNER_PROCESS_FAILED,

	/** Pa11y itself raised an error while evaluating the page. */
	SCAN_ERROR,

	/** A failure kind this version of the library does not recognise. */
	UNKNOWN;

	/**
	 * @param wireName the value reported by the scanner script, may be {@code null}
	 * @return the matching kind, or {@link #UNKNOWN} if there is no match
	 */
	public static FailureKind fromWireName(String wireName) {
		if (wireName != null) {
			for (FailureKind kind : values()) {
				if (kind.name().equalsIgnoreCase(wireName)) {
					return kind;
				}
			}
		}
		return UNKNOWN;
	}
}
