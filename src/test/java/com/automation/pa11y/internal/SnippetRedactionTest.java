package com.automation.pa11y.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnippetRedactionTest {

	@Test
	@DisplayName("a password value never reaches the report")
	void redactsPasswordValues() {
		String redacted = SnippetRedaction.redact(
				"<input type=\"password\" name=\"pw\" value=\"hunter2\">");

		assertFalse(redacted.contains("hunter2"), redacted);
		assertTrue(redacted.contains("value=\"[redacted]\""), redacted);
		// The rest of the element survives, because that is what makes the finding actionable.
		assertTrue(redacted.contains("type=\"password\""), redacted);
		assertTrue(redacted.contains("name=\"pw\""), redacted);
	}

	@Test
	@DisplayName("a hidden field value never reaches the report, which is where csrf tokens live")
	void redactsHiddenValues() {
		String redacted = SnippetRedaction.redact(
				"<input type='hidden' name='csrf' value='a1b2c3d4e5'>");

		assertFalse(redacted.contains("a1b2c3d4e5"), redacted);
		// The original quoting style is kept, so the snippet still reads as markup.
		assertTrue(redacted.contains("value='[redacted]'"), redacted);
	}

	@Test
	@DisplayName("unquoted and oddly spaced attributes are caught too")
	void redactsAwkwardMarkup() {
		assertFalse(SnippetRedaction.redact("<input type=hidden value=secret>").contains("secret"));
		assertFalse(SnippetRedaction.redact("<INPUT TYPE = \"PASSWORD\" VALUE = \"s3cret\">")
				.contains("s3cret"));
	}

	@Test
	@DisplayName("an ordinary field is left exactly as Pa11y reported it")
	void leavesOrdinaryMarkupAlone() {
		String snippet = "<input type=\"text\" name=\"town\" value=\"Sheffield\">";

		assertEquals(snippet, SnippetRedaction.redact(snippet));
	}

	@Test
	@DisplayName("only the sensitive input in a snippet is touched")
	void redactsOnlyTheSensitiveInput() {
		String redacted = SnippetRedaction.redact(
				"<div><input type=\"text\" value=\"keep\"><input type=\"hidden\" value=\"drop\"></div>");

		assertTrue(redacted.contains("value=\"keep\""), redacted);
		assertFalse(redacted.contains("drop"), redacted);
	}

	@Test
	@DisplayName("nothing to do is not an error")
	void handlesNothing() {
		assertEquals(null, SnippetRedaction.redact(null));
		assertEquals("", SnippetRedaction.redact(""));
		assertEquals("plain text", SnippetRedaction.redact("plain text"));
	}
}
