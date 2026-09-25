package com.automation.pa11y.report;

import com.automation.pa11y.Issue;
import com.automation.pa11y.IssueType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Several scanned pages, analysed together.
 *
 * <p>The question this answers is which problems are the site's and which are one page's.
 * Twenty pages each reporting the same unlabelled search box is one defect in a shared
 * header, not twenty; a contrast failure on the checkout page alone is that page's own.
 * Sorting the findings that way is the difference between a list nobody reads and a list
 * with three things at the top of it.
 *
 * <p>Two issues are treated as the same problem when they share a rule {@link Issue#code()}.
 * Matching on the selector as well would be stricter, but selectors differ between pages
 * almost by definition, and everything would come out unique.
 *
 * @param pages  the pages that were read, in scan order
 * @param shared rules that appear on more than one page, worst first
 * @param unique rules that appear on exactly one page
 */
public record CombinedReport(List<PageReport> pages, List<RuleGroup> shared, List<RuleGroup> unique) {

	public CombinedReport {
		pages = pages == null ? List.of() : List.copyOf(pages);
		shared = shared == null ? List.of() : List.copyOf(shared);
		unique = unique == null ? List.of() : List.copyOf(unique);
	}

	/**
	 * @param pages the scanned pages
	 * @return the analysis
	 */
	public static CombinedReport from(List<PageReport> pages) {
		Map<String, Builder> byCode = new LinkedHashMap<>();

		for (PageReport page : pages) {
			for (Issue issue : page.issues()) {
				byCode.computeIfAbsent(issue.code(), code -> new Builder(code, issue)).add(page.name(), issue);
			}
		}

		List<RuleGroup> shared = new ArrayList<>();
		List<RuleGroup> unique = new ArrayList<>();
		for (Builder builder : byCode.values()) {
			RuleGroup group = builder.build();
			if (group.pageCount() > 1) {
				shared.add(group);
			} else {
				unique.add(group);
			}
		}

		// Most widespread first, then most numerous. A rule on eight pages outranks one that
		// fires forty times on a single page, because the first is a systemic defect.
		Comparator<RuleGroup> worstFirst = Comparator
				.comparingInt(RuleGroup::pageCount).reversed()
				.thenComparing(Comparator.comparingInt(RuleGroup::totalOccurrences).reversed())
				.thenComparing(RuleGroup::code);

		shared.sort(worstFirst);
		unique.sort(Comparator
				.comparingInt(RuleGroup::totalOccurrences).reversed()
				.thenComparing(RuleGroup::code));

		return new CombinedReport(pages, shared, unique);
	}

	/**
	 * @param type the severity to count
	 * @return how many issues of that severity there are across every page
	 */
	public long total(IssueType type) {
		return pages.stream().mapToLong(page -> page.count(type)).sum();
	}

	/**
	 * @return every issue on every page
	 */
	public long totalIssues() {
		return pages.stream().mapToLong(page -> page.issues().size()).sum();
	}

	/**
	 * @return {@code true} if there is nothing to compare against
	 */
	public boolean isSinglePage() {
		return pages.size() < 2;
	}

	/**
	 * One rule, and everywhere it fired.
	 *
	 * @param code             the rule identifier
	 * @param type             the severity, taken from the first occurrence
	 * @param message          the description, taken from the first occurrence
	 * @param pages            the pages it fired on, most occurrences first
	 * @param totalOccurrences how many times it fired in total
	 */
	public record RuleGroup(
			String code,
			IssueType type,
			String message,
			List<PageOccurrences> pages,
			int totalOccurrences) {

		/**
		 * @return how many pages this rule fired on
		 */
		public int pageCount() {
			return pages.size();
		}

		/**
		 * @return the names of the pages it fired on
		 */
		public List<String> pageNames() {
			return pages.stream().map(PageOccurrences::pageName).toList();
		}
	}

	/**
	 * One rule's occurrences on one page.
	 *
	 * @param pageName the page's name
	 * @param issues   every occurrence on that page
	 */
	public record PageOccurrences(String pageName, List<Issue> issues) {

		/**
		 * @return how many times the rule fired on this page
		 */
		public int count() {
			return issues.size();
		}
	}

	/** Accumulates the occurrences of one rule while the pages are read. */
	private static final class Builder {

		private final String code;
		private final Issue first;
		private final Map<String, List<Issue>> byPage = new LinkedHashMap<>();
		private int total;

		Builder(String code, Issue first) {
			this.code = code;
			this.first = first;
		}

		/**
		 * @param pageName the page the issue was found on
		 * @param issue    the occurrence
		 */
		void add(String pageName, Issue issue) {
			byPage.computeIfAbsent(pageName, name -> new ArrayList<>()).add(issue);
			total++;
		}

		/**
		 * @return the finished group
		 */
		RuleGroup build() {
			List<PageOccurrences> occurrences = new ArrayList<>();
			byPage.forEach((pageName, issues) -> occurrences.add(new PageOccurrences(pageName, List.copyOf(issues))));
			occurrences.sort(Comparator
					.comparingInt(PageOccurrences::count).reversed()
					.thenComparing(PageOccurrences::pageName));
			return new RuleGroup(code, first.type(), first.message(), List.copyOf(occurrences), total);
		}
	}
}
