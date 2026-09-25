package com.automation.pa11y.internal;

import com.automation.pa11y.FailureKind;
import com.automation.pa11y.Pa11yException;
import com.automation.pa11y.ScanFailedException;
import com.automation.pa11y.ScanRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs one scan by starting the Node scanner script and reading back what it wrote.
 *
 * <p>The process contract is narrow on purpose. The request goes in on stdin, so nothing with
 * credentials in it is ever written to disk or shows up in a process listing. The result comes
 * back in a file rather than on stdout, because Puppeteer and Pa11y's runners are entitled to
 * print whatever they like and a parser reading stdout would break the day one of them did.
 * Whatever they do print is captured for diagnostics.
 */
public final class NodeScanner {

	/** Serialises the request only; reading the response is {@link ScanResponse#parse}. */
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/**
	 * Headroom on top of the scan timeout, covering Node's startup, the CDP connection and
	 * writing the result. The scan's own timeout should be what actually fires.
	 */
	private static final Duration PROCESS_OVERHEAD = Duration.ofSeconds(30);

	/** Enough of the process output to diagnose a failure without burying the message. */
	private static final int DIAGNOSTIC_LINES = 40;

	private final String nodeExecutable;
	private final Path modulesDir;
	private final boolean debug;

	/**
	 * @param nodeExecutable the {@code node} binary to run
	 * @param modulesDir     the {@code node_modules} holding Pa11y
	 * @param debug          whether the script should log its progress to the process output
	 */
	public NodeScanner(String nodeExecutable, Path modulesDir, boolean debug) {
		this.nodeExecutable = nodeExecutable;
		this.modulesDir = modulesDir;
		this.debug = debug;
	}

	/**
	 * @param request      what to scan
	 * @param webSocketUrl the resolved Chrome debugger URL
	 * @return what the scanner reported
	 * @throws ScanFailedException if the scan could not be run
	 */
	public ScanResponse run(ScanRequest request, String webSocketUrl) {
		Path resultFile = temporaryFile(request, "result", ".json");
		Path outputFile = temporaryFile(request, "output", ".log");
		try {
			return runWith(request, webSocketUrl, resultFile, outputFile);
		} finally {
			deleteQuietly(resultFile);
			deleteQuietly(outputFile);
		}
	}

	private ScanResponse runWith(ScanRequest request, String webSocketUrl, Path resultFile, Path outputFile) {
		String payload = serialise(buildRequest(request, webSocketUrl, resultFile));

		ProcessBuilder builder = new ProcessBuilder(nodeExecutable, ScannerScript.path().toString());
		builder.redirectErrorStream(true);
		builder.redirectOutput(outputFile.toFile());

		Process process;
		try {
			process = builder.start();
		} catch (IOException e) {
			throw new ScanFailedException(FailureKind.SCANNER_PROCESS_FAILED, request.target(),
					"Could not start '" + nodeExecutable + "'. Node 22.13+ or 24+ has to be installed "
					+ "and on the PATH of the process running the tests, or named with the "
					+ "pa11y.node.executable system property.", e);
		}

		writeRequest(process, payload);

		Duration limit = request.timeout().plus(PROCESS_OVERHEAD);
		boolean finished;
		try {
			finished = process.waitFor(limit.toMillis(), TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			process.destroyForcibly();
			Thread.currentThread().interrupt();
			throw new ScanFailedException(FailureKind.SCANNER_PROCESS_FAILED, request.target(),
					"Interrupted while waiting for the scan.", e);
		}

		if (!finished) {
			process.destroyForcibly();
			throw new ScanFailedException(FailureKind.TIMEOUT, request.target(),
					"The scanner process did not finish within " + limit.toSeconds() + "s (scan timeout "
					+ request.timeout().toSeconds() + "s plus " + PROCESS_OVERHEAD.toSeconds()
					+ "s of headroom).");
		}

		if (!Files.isRegularFile(resultFile)) {
			throw new ScanFailedException(FailureKind.SCANNER_PROCESS_FAILED, request.target(),
					"The scanner exited with code " + process.exitValue() + " and wrote no result."
					+ diagnostics(outputFile));
		}

		try {
			return ScanResponse.parse(Files.readString(resultFile, StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new ScanFailedException(FailureKind.SCANNER_PROCESS_FAILED, request.target(),
					"The scanner's result file could not be read." + diagnostics(outputFile), e);
		}
	}

	private void writeRequest(Process process, String payload) {
		try (OutputStream stdin = process.getOutputStream()) {
			stdin.write(payload.getBytes(StandardCharsets.UTF_8));
			stdin.flush();
		} catch (IOException e) {
			// A broken pipe means the process has already died. Say nothing here and let
			// waitFor report the real reason, which is more useful than "broken pipe".
			process.destroyForcibly();
		}
	}

	/** Field names here match what the scanner script expects. */
	private Map<String, Object> buildRequest(ScanRequest request, String webSocketUrl, Path resultFile) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("scanCurrentPage", request.scanCurrentPage());
		if (request.url() != null) {
			payload.put("url", request.url());
		}
		payload.put("browserWSEndpoint", webSocketUrl);
		payload.put("outputFile", resultFile.toAbsolutePath().toString());
		payload.put("modulesDir", modulesDir.toAbsolutePath().toString());
		payload.put("standard", request.standard().wireName());
		// Still an array because that is the shape Pa11y's own option takes, but there is only
		// ever one name in it. See ScanRequest.ENGINE.
		payload.put("runners", List.of(ScanRequest.ENGINE));
		payload.put("includeWarnings", request.includeWarnings());
		payload.put("includeNotices", request.includeNotices());
		payload.put("timeout", request.timeout().toMillis());
		payload.put("wait", request.waitAfterLoad().toMillis());
		payload.put("ignore", request.ignore());
		payload.put("actions", request.actions());
		payload.put("debug", debug);

		// Puppeteer's own protocol timeout has to outlast the scan, or a slow page is reported
		// as a CDP failure rather than as the timeout it is.
		payload.put("protocolTimeout", request.timeout().plus(PROCESS_OVERHEAD).toMillis());

		putIfSet(payload, "rootElement", request.rootElement());
		putIfSet(payload, "hideElements", request.hideElements());
		return payload;
	}

	/** Skips blanks so Pa11y's own defaults apply rather than an explicit empty value. */
	private static void putIfSet(Map<String, Object> payload, String key, String value) {
		if (value != null && !value.isBlank()) {
			payload.put(key, value);
		}
	}

	private static String serialise(Map<String, Object> payload) {
		try {
			return MAPPER.writeValueAsString(payload);
		} catch (IOException e) {
			throw new Pa11yException("Could not serialise the scan request.", e);
		}
	}

	private static Path temporaryFile(ScanRequest request, String name, String suffix) {
		try {
			Path file = Files.createTempFile("pa11y-" + name + "-", suffix);
			file.toFile().deleteOnExit();
			return file;
		} catch (IOException e) {
			throw new ScanFailedException(FailureKind.SCANNER_PROCESS_FAILED, request.target(),
					"Could not create a temporary file for the scan.", e);
		}
	}

	/** The tail of the captured output, formatted for appending to an error message. */
	private static String diagnostics(Path outputFile) {
		List<String> lines;
		try {
			lines = Files.readAllLines(outputFile, StandardCharsets.UTF_8);
		} catch (IOException e) {
			return "";
		}
		if (lines.isEmpty()) {
			return System.lineSeparator() + "The scanner produced no output.";
		}
		List<String> tail = lines.size() <= DIAGNOSTIC_LINES
				? lines
				: lines.subList(lines.size() - DIAGNOSTIC_LINES, lines.size());
		return System.lineSeparator() + "Scanner output:" + System.lineSeparator()
				+ String.join(System.lineSeparator(), tail);
	}

	private static void deleteQuietly(Path file) {
		try {
			Files.deleteIfExists(file);
		} catch (IOException e) {
			// Temp files are also registered for deletion on exit; losing one is not worth
			// failing a scan that otherwise worked.
		}
	}
}
