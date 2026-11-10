package com.automation.pa11y.internal;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Takes secrets out of the HTML snippets that go into a report.
 *
 * <p>Pa11y reports the offending element as a fragment of markup, which is what makes a
 * finding actionable. It is also the one place in the output where application data can end
 * up: a password field carries what was typed into it, and a hidden field is where a CSRF
 * token or a session identifier usually lives. A report gets committed to a build artifact
 * store and emailed around, so those values cannot travel with it.
 *
 * <p>What is redacted is the {@code value} of any {@code input} whose type is {@code password}
 * or {@code hidden}. Everything else is left exactly as Pa11y reported it, because a snippet
 * that has been rewritten past recognition is no longer evidence.
 *
 * <h2>What this does not do</h2>
 *
 * <p>It is not a general secret scanner. Pa11y snippets are a single element and its immediate
 * contents, so an auth header or a bearer token has no ordinary route into one. An application
 * that renders a secret into visible text, a {@code data-} attribute or a URL will have that
 * secret in the report, and no amount of pattern matching here would reliably catch it. The
 * answer to that is not to render secrets into the page.
 */
public final class SnippetRedaction {

	/** What replaces a redacted value, chosen to be obvious in a report rather than silent. */
	public static final String PLACEHOLDER = "[redacted]";

	private static final Pattern INPUT_TAG = Pattern.compile("<input\\b[^>]*>", Pattern.CASE_INSENSITIVE);

	private static final Pattern SENSITIVE_TYPE = Pattern.compile(
			"\\btype\\s*=\\s*[\"']?\\s*(?:password|hidden)\\b", Pattern.CASE_INSENSITIVE);

	private static final Pattern VALUE_ATTRIBUTE = Pattern.compile(
			"\\bvalue\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s\"'>]+)", Pattern.CASE_INSENSITIVE);

	private SnippetRedaction() {
	}

	/**
	 * @param snippet the markup Pa11y reported, which may be {@code null}
	 * @return the same markup with sensitive input values replaced
	 */
	public static String redact(String snippet) {
		if (snippet == null || snippet.isEmpty()) {
			return snippet;
		}
		// Cheap guard: the overwhelming majority of snippets are not inputs at all, and this
		// runs once per issue on every page of a suite.
		if (snippet.indexOf('<') < 0) {
			return snippet;
		}

		Matcher tags = INPUT_TAG.matcher(snippet);
		StringBuilder result = new StringBuilder(snippet.length());
		while (tags.find()) {
			String tag = tags.group();
			tags.appendReplacement(result, Matcher.quoteReplacement(
					SENSITIVE_TYPE.matcher(tag).find() ? redactValue(tag) : tag));
		}
		tags.appendTail(result);
		return result.toString();
	}

	private static String redactValue(String tag) {
		Matcher values = VALUE_ATTRIBUTE.matcher(tag);
		StringBuilder redacted = new StringBuilder(tag.length());
		while (values.find()) {
			// The original quoting is kept so the snippet still reads as the markup it was.
			String quote = values.group(1).startsWith("'") ? "'" : "\"";
			values.appendReplacement(redacted, Matcher.quoteReplacement(
					"value=" + quote + PLACEHOLDER + quote));
		}
		values.appendTail(redacted);
		return redacted.toString();
	}
}
