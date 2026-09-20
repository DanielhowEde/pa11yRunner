package com.automation.pa11y;

/**
 * Something went wrong setting up or running a scan.
 *
 * <p>Unchecked on purpose. A test suite that cannot scan should fail loudly at the call
 * site rather than be forced into a {@code try/catch} that ends up swallowing the problem
 * and reporting a clean page.
 */
public class Pa11yException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/**
	 * @param message what went wrong
	 */
	public Pa11yException(String message) {
		super(message);
	}

	/**
	 * @param message what went wrong
	 * @param cause   the underlying failure
	 */
	public Pa11yException(String message, Throwable cause) {
		super(message, cause);
	}
}
