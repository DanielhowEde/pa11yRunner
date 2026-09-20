package com.automation.pa11y.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A very small argument parser: {@code --name value} pairs and {@code --flag} switches.
 *
 * <p>Hand-rolled rather than pulled in from a library because the jar is dropped onto a test
 * harness's classpath, and one shaded dependency is already one more than ideal.
 *
 * <p>It refuses anything it was not told about. A misspelled option that is quietly ignored
 * is how a run ends up scanning the wrong thing, or writing its report where nobody looks.
 */
public final class CommandLine {

	private final Map<String, List<String>> values;
	private final Set<String> present;

	private CommandLine(Map<String, List<String>> values, Set<String> present) {
		this.values = values;
		this.present = present;
	}

	/**
	 * @param args     the whole command line
	 * @param from     the index to start at, so the subcommand itself can be skipped
	 * @param switches options that take no value
	 * @param options  options that take a value
	 * @return the parsed command line
	 * @throws IllegalArgumentException if an option is unknown or missing its value
	 */
	public static CommandLine parse(String[] args, int from, Set<String> switches, Set<String> options) {
		Map<String, List<String>> values = new LinkedHashMap<>();
		Set<String> present = new java.util.LinkedHashSet<>();

		for (int i = from; i < args.length; i++) {
			String argument = args[i];
			if (switches.contains(argument)) {
				present.add(argument);
			} else if (options.contains(argument)) {
				if (i + 1 >= args.length) {
					throw new IllegalArgumentException(argument + " needs a value.");
				}
				present.add(argument);
				values.computeIfAbsent(argument, key -> new ArrayList<>()).add(args[++i]);
			} else if (argument.startsWith("-")) {
				throw new IllegalArgumentException("Unknown option: " + argument);
			} else {
				throw new IllegalArgumentException("Unexpected argument: " + argument);
			}
		}
		return new CommandLine(values, present);
	}

	/**
	 * @param name the option
	 * @return whether it was given
	 */
	public boolean has(String name) {
		return present.contains(name);
	}

	/**
	 * @param name     the option
	 * @param fallback what to use when it was not given
	 * @return the last value given, or {@code fallback}
	 */
	public String value(String name, String fallback) {
		List<String> given = values.get(name);
		return given == null || given.isEmpty() ? fallback : given.get(given.size() - 1);
	}

	/**
	 * @param name the option
	 * @return its value
	 * @throws IllegalArgumentException if it was not given
	 */
	public String require(String name) {
		String value = value(name, null);
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " is required.");
		}
		return value;
	}

	/**
	 * @param name the option
	 * @return every value given for it, in order
	 */
	public List<String> all(String name) {
		return List.copyOf(values.getOrDefault(name, List.of()));
	}

	/**
	 * @param name     the option
	 * @param fallback what to use when it was not given
	 * @return its value as a number
	 * @throws IllegalArgumentException if it is not a number
	 */
	public long number(String name, long fallback) {
		String value = value(name, null);
		if (value == null) {
			return fallback;
		}
		try {
			return Long.parseLong(value.trim());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(name + " expects a number, got '" + value + "'.");
		}
	}
}
