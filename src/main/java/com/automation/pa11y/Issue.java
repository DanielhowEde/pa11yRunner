package com.automation.pa11y;

import java.util.Map;
import java.util.Objects;

/**
 * One accessibility problem found on a page.
 *
 * @param code         the rule identifier, e.g. {@code WCAG2AA.Principle1.Guideline1_1.1_1_1.H30.2}
 * @param type         how severe the engine considers it
 * @param typeCode     Pa11y's numeric form of {@code type}; {@code -1} when absent
 * @param message      the human-readable description
 * @param context      the offending HTML, truncated by Pa11y
 * @param selector     a CSS selector for the element, usable with {@code By.cssSelector}
 * @param engine       which engine reported it
 * @param engineExtras engine-specific detail; populated by axe, empty for HTML CodeSniffer
 */
public record Issue(
		String code,
		IssueType type,
		int typeCode,
		String message,
		String context,
		String selector,
		String engine,
		Map<String, Object> engineExtras) {

	/**
	 * @param code         the rule identifier
	 * @param type         how severe the engine considers it
	 * @param typeCode     Pa11y's numeric form of {@code type}
	 * @param message      the human-readable description
	 * @param context      the offending HTML
	 * @param selector     a CSS selector for the element
	 * @param engine       which engine reported it
	 * @param engineExtras engine-specific detail
	 */
	public Issue {
		code = Objects.requireNonNullElse(code, "");
		type = Objects.requireNonNullElse(type, IssueType.UNKNOWN);
		message = Objects.requireNonNullElse(message, "");
		context = Objects.requireNonNullElse(context, "");
		selector = Objects.requireNonNullElse(selector, "");
		engine = Objects.requireNonNullElse(engine, "");
		engineExtras = engineExtras == null ? Map.of() : Map.copyOf(engineExtras);
	}

	/**
	 * @return {@code true} if this is a definite failure rather than a warning or notice
	 */
	public boolean isError() {
		return type == IssueType.ERROR;
	}

	/**
	 * A single line suitable for an assertion message or a log.
	 *
	 * @return the issue as one line
	 */
	public String describe() {
		return "[%s] %s (%s)%s".formatted(
				type.name(),
				message,
				code,
				selector.isEmpty() ? "" : " at " + selector);
	}
}
