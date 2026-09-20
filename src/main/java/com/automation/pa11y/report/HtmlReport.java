package com.automation.pa11y.report;

import com.automation.pa11y.Issue;
import com.automation.pa11y.IssueType;
import com.automation.pa11y.Pa11yException;
import com.automation.pa11y.report.CombinedReport.PageOccurrences;
import com.automation.pa11y.report.CombinedReport.RuleGroup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Renders a {@link CombinedReport} as one self-contained HTML file.
 *
 * <p>No stylesheet, script or font is fetched from anywhere: the file is meant to be attached
 * to a build, emailed, or opened from a network share long after the run, and any of those
 * would break an external reference.
 *
 * <p>The report is ordered by what is worth fixing first. Rules that fire on several pages come
 * before rules confined to one, because a defect in a shared header is one fix that clears
 * dozens of findings, while a contrast failure on a single screen is one fix for one finding.
 *
 * <p>It is also, for obvious reasons, built to pass the kind of check it reports on: real
 * headings, table headers with scope, visible focus, labelled landmarks, and severity conveyed
 * in words rather than by colour alone.
 */
public final class HtmlReport {

	/** How many selectors to list per page before summarising the rest. */
	private static final int SELECTORS_SHOWN = 8;

	private static final DateTimeFormatter TIMESTAMP =
			DateTimeFormatter.ofPattern("d MMM yyyy 'at' HH:mm");

	private HtmlReport() {
	}

	/**
	 * @param report the analysis to render
	 * @param title  the heading for the report
	 * @param file   where to write it
	 * @return the file written
	 */
	public static Path write(CombinedReport report, String title, Path file) {
		try {
			Path parent = file.toAbsolutePath().getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Files.writeString(file, render(report, title), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new Pa11yException("Could not write the HTML report to " + file + ".", e);
		}
		return file;
	}

	/**
	 * @param report the analysis to render
	 * @param title  the heading for the report
	 * @return the whole HTML document
	 */
	public static String render(CombinedReport report, String title) {
		StringBuilder html = new StringBuilder(64_000);

		html.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n")
				.append("<meta charset=\"utf-8\">\n")
				.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
				.append("<title>").append(escape(title)).append("</title>\n")
				.append("<style>\n").append(STYLES).append("</style>\n")
				.append("</head>\n<body>\n");

		appendHeader(html, report, title);
		html.append("<main id=\"main\">\n");
		appendSummary(html, report);
		appendShared(html, report);
		appendUnique(html, report);
		appendPages(html, report);
		html.append("</main>\n</body>\n</html>\n");

		return html.toString();
	}

	/**
	 * @param html   the document being built
	 * @param report the analysis
	 * @param title  the heading
	 */
	private static void appendHeader(StringBuilder html, CombinedReport report, String title) {
		String standards = report.pages().stream()
				.map(PageReport::standard)
				.filter(value -> !value.isBlank())
				.distinct()
				.reduce((a, b) -> a + ", " + b)
				.orElse("");

		html.append("<header>\n<h1>").append(escape(title)).append("</h1>\n<p class=\"meta\">")
				.append(report.pages().size()).append(report.pages().size() == 1 ? " page" : " pages")
				.append(" &middot; generated ")
				.append(escape(ZonedDateTime.now(ZoneId.systemDefault()).format(TIMESTAMP)));
		if (!standards.isBlank()) {
			html.append(" &middot; ").append(escape(standards));
		}
		html.append("</p>\n</header>\n");
	}

	/**
	 * @param html   the document being built
	 * @param report the analysis
	 */
	private static void appendSummary(StringBuilder html, CombinedReport report) {
		html.append("<section aria-labelledby=\"summary-heading\">\n")
				.append("<h2 id=\"summary-heading\">Summary</h2>\n<ul class=\"tiles\">\n");

		tile(html, report.pages().size(), "pages scanned", null);
		tile(html, report.total(IssueType.ERROR), "errors", "error");
		tile(html, report.total(IssueType.WARNING), "warnings", "warning");
		tile(html, report.shared().size(), "rules on more than one page", null);
		tile(html, report.unique().size(), "rules on a single page", null);

		html.append("</ul>\n");

		if (report.totalIssues() == 0 && !report.pages().isEmpty()) {
			html.append("<p class=\"clean\">No issues were found on any page scanned.</p>\n");
		}
		html.append("</section>\n");
	}

	/**
	 * @param html    the document being built
	 * @param value   the number to show
	 * @param label   what it counts
	 * @param variant a severity class, or {@code null}
	 */
	private static void tile(StringBuilder html, long value, String label, String variant) {
		html.append("<li class=\"tile").append(variant == null ? "" : " " + variant).append("\">")
				.append("<span class=\"tile-value\">").append(value).append("</span>")
				.append("<span class=\"tile-label\">").append(escape(label)).append("</span>")
				.append("</li>\n");
	}

	/**
	 * @param html   the document being built
	 * @param report the analysis
	 */
	private static void appendShared(StringBuilder html, CombinedReport report) {
		html.append("<section aria-labelledby=\"shared-heading\">\n")
				.append("<h2 id=\"shared-heading\">Shared across pages</h2>\n");

		if (report.isSinglePage()) {
			html.append("<p class=\"note\">Only one page was scanned, so there is nothing to compare "
					+ "it against. Scan more pages in the same run to see which problems are shared.</p>\n");
		} else if (report.shared().isEmpty()) {
			html.append("<p class=\"note\">No rule failed on more than one page. Every problem found "
					+ "belongs to a single page.</p>\n");
		} else {
			html.append("<p class=\"note\">These rules failed on more than one page, so they are most "
					+ "likely one defect in something shared &mdash; a header, a footer, a component or a "
					+ "template. Fixing the top of this list clears the most findings.</p>\n");
			for (RuleGroup group : report.shared()) {
				appendRule(html, group, report.pages().size(), true);
			}
		}
		html.append("</section>\n");
	}

	/**
	 * @param html   the document being built
	 * @param report the analysis
	 */
	private static void appendUnique(StringBuilder html, CombinedReport report) {
		html.append("<section aria-labelledby=\"unique-heading\">\n")
				.append("<h2 id=\"unique-heading\">Specific to one page</h2>\n");

		if (report.unique().isEmpty()) {
			html.append("<p class=\"note\">Nothing here: every rule that failed did so on more than "
					+ "one page.</p>\n");
		} else {
			html.append("<p class=\"note\">These rules failed on one page only, so each is that "
					+ "page's own problem rather than a site-wide one.</p>\n");
			for (RuleGroup group : report.unique()) {
				appendRule(html, group, report.pages().size(), false);
			}
		}
		html.append("</section>\n");
	}

	/**
	 * @param html       the document being built
	 * @param group      the rule to render
	 * @param totalPages how many pages were scanned
	 * @param shared     whether this rule spans pages
	 */
	private static void appendRule(StringBuilder html, RuleGroup group, int totalPages, boolean shared) {
		String severity = severityName(group.type());

		// Shared rules open by default: they are the headline, and it means a printed or
		// emailed copy carries the detail that matters without anyone having to click.
		html.append("<details class=\"rule ").append(group.type().name().toLowerCase())
				.append(shared ? "\" open>\n" : "\">\n")
				.append("<summary>\n")
				.append("<span class=\"badge\">").append(escape(severity)).append("</span>\n")
				.append("<span class=\"rule-message\">").append(escape(group.message())).append("</span>\n")
				.append("<span class=\"rule-scope\">");

		if (shared) {
			html.append(group.pageCount()).append(" of ").append(totalPages).append(" pages");
		} else {
			html.append(escape(group.pageNames().isEmpty() ? "one page" : group.pageNames().get(0)));
		}
		html.append(" &middot; ").append(group.totalOccurrences())
				.append(group.totalOccurrences() == 1 ? " occurrence" : " occurrences")
				.append("</span>\n</summary>\n");

		html.append("<p class=\"rule-code\"><code>").append(escape(group.code())).append("</code>");
		if (!group.engine().isBlank()) {
			html.append(" &middot; ").append(escape(group.engine()));
		}
		html.append("</p>\n");

		html.append("<table>\n<caption>Where it occurs</caption>\n<thead>\n<tr>")
				.append("<th scope=\"col\">Page</th>")
				.append("<th scope=\"col\" class=\"numeric\">Count</th>")
				.append("<th scope=\"col\">Elements</th>")
				.append("</tr>\n</thead>\n<tbody>\n");

		for (PageOccurrences occurrence : group.pages()) {
			html.append("<tr><th scope=\"row\">").append(escape(occurrence.pageName())).append("</th>")
					.append("<td class=\"numeric\">").append(occurrence.count()).append("</td>")
					.append("<td>").append(selectors(occurrence.issues())).append("</td></tr>\n");
		}
		html.append("</tbody>\n</table>\n");

		String context = firstContext(group);
		if (!context.isBlank()) {
			html.append("<p class=\"context-label\">Example of the markup</p>\n<pre class=\"context\"><code>")
					.append(escape(context)).append("</code></pre>\n");
		}

		html.append("</details>\n");
	}

	/**
	 * @param issues the occurrences on one page
	 * @return their selectors as escaped HTML, truncated if there are many
	 */
	private static String selectors(List<Issue> issues) {
		StringBuilder rendered = new StringBuilder();
		int shown = Math.min(issues.size(), SELECTORS_SHOWN);

		for (int i = 0; i < shown; i++) {
			String selector = issues.get(i).selector();
			if (selector.isBlank()) {
				continue;
			}
			if (rendered.length() > 0) {
				rendered.append(" ");
			}
			rendered.append("<code>").append(escape(selector)).append("</code>");
		}
		if (issues.size() > shown) {
			if (rendered.length() > 0) {
				rendered.append(" ");
			}
			rendered.append("<span class=\"more\">and ").append(issues.size() - shown).append(" more</span>");
		}
		return rendered.length() == 0 ? "<span class=\"more\">no selector reported</span>" : rendered.toString();
	}

	/**
	 * @param group the rule
	 * @return the HTML of its first occurrence, or an empty string
	 */
	private static String firstContext(RuleGroup group) {
		for (PageOccurrences occurrence : group.pages()) {
			for (Issue issue : occurrence.issues()) {
				if (!issue.context().isBlank()) {
					return issue.context();
				}
			}
		}
		return "";
	}

	/**
	 * @param html   the document being built
	 * @param report the analysis
	 */
	private static void appendPages(StringBuilder html, CombinedReport report) {
		html.append("<section aria-labelledby=\"pages-heading\">\n")
				.append("<h2 id=\"pages-heading\">Pages</h2>\n");

		if (report.pages().isEmpty()) {
			html.append("<p class=\"note\">No page reports were found.</p>\n</section>\n");
			return;
		}

		html.append("<table>\n<caption>Every page in this run</caption>\n<thead>\n<tr>")
				.append("<th scope=\"col\">Page</th>")
				.append("<th scope=\"col\">URL</th>")
				.append("<th scope=\"col\" class=\"numeric\">Errors</th>")
				.append("<th scope=\"col\" class=\"numeric\">Warnings</th>")
				.append("<th scope=\"col\" class=\"numeric\">Notices</th>")
				.append("</tr>\n</thead>\n<tbody>\n");

		for (PageReport page : report.pages()) {
			html.append("<tr><th scope=\"row\">").append(escape(page.name())).append("</th>")
					.append("<td class=\"url\">").append(escape(page.pageUrl())).append("</td>")
					.append("<td class=\"numeric\">").append(page.count(IssueType.ERROR)).append("</td>")
					.append("<td class=\"numeric\">").append(page.count(IssueType.WARNING)).append("</td>")
					.append("<td class=\"numeric\">").append(page.count(IssueType.NOTICE)).append("</td>")
					.append("</tr>\n");
		}
		html.append("</tbody>\n</table>\n</section>\n");
	}

	/**
	 * @param type the severity
	 * @return a word for it, so severity is never carried by colour alone
	 */
	private static String severityName(IssueType type) {
		return switch (type) {
			case ERROR -> "Error";
			case WARNING -> "Warning";
			case NOTICE -> "Notice";
			case UNKNOWN -> "Issue";
		};
	}

	/**
	 * Escapes text for HTML.
	 *
	 * <p>This is load-bearing rather than decorative: an issue's context is a fragment of the
	 * offending markup, so the report is full of untrusted HTML taken from the pages under
	 * test. Without this the report would render that markup and, worse, could execute it.
	 *
	 * @param value any text
	 * @return it, safe to place in an element or an attribute
	 */
	static String escape(String value) {
		if (value == null || value.isEmpty()) {
			return "";
		}
		StringBuilder escaped = new StringBuilder(value.length() + 16);
		for (int i = 0; i < value.length(); i++) {
			char character = value.charAt(i);
			switch (character) {
				case '&' -> escaped.append("&amp;");
				case '<' -> escaped.append("&lt;");
				case '>' -> escaped.append("&gt;");
				case '"' -> escaped.append("&quot;");
				case '\'' -> escaped.append("&#39;");
				default -> escaped.append(character);
			}
		}
		return escaped.toString();
	}

	private static final String STYLES = """
			:root {
			  color-scheme: light dark;
			  --bg: #ffffff;
			  --surface: #f6f7f9;
			  --border: #d7dbe0;
			  --text: #16191d;
			  --muted: #5b6470;
			  --accent: #0b5cad;
			  --error: #a3231d;
			  --error-bg: #fdeceb;
			  --warning: #7a5300;
			  --warning-bg: #fdf3dd;
			  --notice: #2b4a6f;
			  --notice-bg: #eaf1f9;
			}
			@media (prefers-color-scheme: dark) {
			  :root {
			    --bg: #14171a;
			    --surface: #1d2126;
			    --border: #333a42;
			    --text: #e9ecef;
			    --muted: #a3acb8;
			    --accent: #7ab4f0;
			    --error: #ff9b94;
			    --error-bg: #3a1e1c;
			    --warning: #f0c76b;
			    --warning-bg: #382e13;
			    --notice: #9dc2ec;
			    --notice-bg: #1c2a3a;
			  }
			}
			* { box-sizing: border-box; }
			body {
			  margin: 0;
			  padding: 0 16px 64px;
			  background: var(--bg);
			  color: var(--text);
			  font: 16px/1.55 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
			}
			header, main { max-width: 68rem; margin: 0 auto; }
			header { padding: 32px 0 8px; border-bottom: 1px solid var(--border); margin-bottom: 8px; }
			h1 { font-size: 1.75rem; margin: 0 0 4px; }
			h2 { font-size: 1.25rem; margin: 40px 0 8px; }
			.meta, .note { color: var(--muted); margin: 0 0 8px; }
			.note { max-width: 58rem; }
			.clean { color: var(--muted); font-weight: 600; }
			a { color: var(--accent); }
			:focus-visible { outline: 3px solid var(--accent); outline-offset: 2px; }

			.tiles { display: flex; flex-wrap: wrap; gap: 12px; list-style: none; padding: 0; margin: 16px 0 0; }
			.tile {
			  flex: 1 1 8.5rem;
			  background: var(--surface);
			  border: 1px solid var(--border);
			  border-radius: 8px;
			  padding: 12px 14px;
			  display: flex;
			  flex-direction: column;
			  gap: 2px;
			}
			.tile-value { font-size: 1.75rem; font-weight: 700; line-height: 1.1; }
			.tile-label { color: var(--muted); font-size: 0.85rem; }
			.tile.error .tile-value { color: var(--error); }
			.tile.warning .tile-value { color: var(--warning); }

			details.rule {
			  border: 1px solid var(--border);
			  border-left: 4px solid var(--muted);
			  border-radius: 6px;
			  background: var(--surface);
			  margin: 10px 0;
			  padding: 0;
			}
			details.rule.error { border-left-color: var(--error); }
			details.rule.warning { border-left-color: var(--warning); }
			details.rule.notice { border-left-color: var(--notice); }
			summary {
			  cursor: pointer;
			  padding: 12px 14px;
			  display: grid;
			  grid-template-columns: auto 1fr;
			  grid-template-areas: "badge message" "badge scope";
			  gap: 2px 10px;
			  align-items: start;
			}
			summary::marker { color: var(--muted); }
			.badge {
			  grid-area: badge;
			  align-self: center;
			  font-size: 0.72rem;
			  font-weight: 700;
			  text-transform: uppercase;
			  letter-spacing: 0.04em;
			  padding: 3px 8px;
			  border-radius: 999px;
			  white-space: nowrap;
			}
			.error .badge { background: var(--error-bg); color: var(--error); }
			.warning .badge { background: var(--warning-bg); color: var(--warning); }
			.notice .badge, .unknown .badge { background: var(--notice-bg); color: var(--notice); }
			.rule-message { grid-area: message; font-weight: 600; }
			.rule-scope { grid-area: scope; color: var(--muted); font-size: 0.85rem; }
			.rule-code { margin: 0 14px 10px; color: var(--muted); font-size: 0.85rem; }

			table { border-collapse: collapse; width: calc(100% - 28px); margin: 0 14px 14px; font-size: 0.9rem; }
			section > table { width: 100%; margin: 16px 0 0; }
			caption { text-align: left; color: var(--muted); font-size: 0.85rem; padding-bottom: 6px; }
			th, td { text-align: left; padding: 7px 10px; border-bottom: 1px solid var(--border); vertical-align: top; }
			thead th { border-bottom: 2px solid var(--border); white-space: nowrap; }
			tbody th { font-weight: 600; white-space: nowrap; }
			.numeric { text-align: right; font-variant-numeric: tabular-nums; }
			.url { color: var(--muted); word-break: break-all; }
			.more { color: var(--muted); font-size: 0.85rem; }

			code { font-family: ui-monospace, SFMono-Regular, Consolas, "Liberation Mono", monospace; font-size: 0.85em; }
			td code { background: var(--bg); border: 1px solid var(--border); border-radius: 4px; padding: 1px 5px; display: inline-block; margin: 1px 0; }
			.context-label { margin: 0 14px 4px; color: var(--muted); font-size: 0.85rem; }
			pre.context {
			  margin: 0 14px 14px;
			  padding: 10px 12px;
			  background: var(--bg);
			  border: 1px solid var(--border);
			  border-radius: 6px;
			  overflow-x: auto;
			  white-space: pre-wrap;
			  word-break: break-word;
			}

			@media print {
			  body { padding: 0; }
			  details.rule { break-inside: avoid; }
			}
			""";
}
