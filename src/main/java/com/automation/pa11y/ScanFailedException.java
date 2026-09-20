package com.automation.pa11y;

/**
 * The scan did not run, so nothing is known about the page's accessibility.
 *
 * <p>This is not "the page has problems" -- that is a {@link ScanResult} with errors in it.
 * The distinction matters: a scanner that silently returned an empty result when it could
 * not reach the page would turn every outage into a green build.
 */
public class ScanFailedException extends Pa11yException {

	private static final long serialVersionUID = 1L;

	private final transient FailureKind kind;
	private final transient String url;

	/**
	 * @param kind    why the scan could not run
	 * @param url     the URL that was being scanned
	 * @param message the detail from the scanner
	 */
	public ScanFailedException(FailureKind kind, String url, String message) {
		super("Pa11y scan of %s failed (%s): %s".formatted(url, kind, message));
		this.kind = kind;
		this.url = url;
	}

	/**
	 * @param kind    why the scan could not run
	 * @param url     the URL that was being scanned
	 * @param message the detail from the scanner
	 * @param cause   the underlying failure
	 */
	public ScanFailedException(FailureKind kind, String url, String message, Throwable cause) {
		super("Pa11y scan of %s failed (%s): %s".formatted(url, kind, message), cause);
		this.kind = kind;
		this.url = url;
	}

	/**
	 * @return why the scan could not run, for callers that want to retry only some kinds
	 */
	public FailureKind kind() {
		return kind;
	}

	/**
	 * @return the URL that was being scanned
	 */
	public String url() {
		return url;
	}
}
