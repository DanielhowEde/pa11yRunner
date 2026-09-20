package com.automation.pa11y.internal;

import com.automation.pa11y.Pa11yException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Turns "the Chrome on that Grid node has its debugger on 9222" into the WebSocket URL
 * Puppeteer needs.
 *
 * <p>Two things make this less trivial than it looks, and both only bite once the browser
 * is on a different machine from the tests:
 *
 * <ol>
 * <li>Chrome's DevTools HTTP endpoint refuses any request whose {@code Host} header is
 *     neither {@code localhost} nor a bare IP address. It is a DNS-rebinding guard, and it
 *     means {@code http://grid-node-3:9222/json/version} is rejected while the same request
 *     to the node's IP succeeds. So the hostname is resolved here and the IP is used.</li>
 * <li>The {@code webSocketDebuggerUrl} Chrome hands back is written from Chrome's own point
 *     of view -- typically {@code ws://localhost:9222/...} -- which is unreachable from
 *     anywhere else. Only the path is worth keeping; the authority is replaced with the one
 *     that actually worked.</li>
 * </ol>
 *
 * <p>This is also why the lookup happens in Java rather than letting Puppeteer's
 * {@code browserURL} option do it: doing it here means a failure to reach Chrome is
 * reported before a Node process is started, with a message that says what to check.
 */
public final class ChromeEndpoint {

	/** Chrome's conventional remote debugging port. */
	public static final int DEFAULT_PORT = 9222;

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private ChromeEndpoint() {
	}

	/**
	 * Resolves a browser-level WebSocket debugger URL.
	 *
	 * @param chromeUrl either a DevTools HTTP endpoint ({@code http://grid-node-3:9222},
	 *                  {@code grid-node-3:9222} or {@code 10.0.4.7}) or an already-known
	 *                  WebSocket URL ({@code ws://...}), such as Selenium's {@code se:cdp}
	 *                  capability, which is passed through untouched
	 * @param timeout   how long to allow for the lookup
	 * @return the WebSocket URL to hand to Puppeteer
	 * @throws Pa11yException if Chrome cannot be reached or gives no debugger URL
	 */
	public static String resolveWebSocketUrl(String chromeUrl, Duration timeout) {
		if (chromeUrl == null || chromeUrl.isBlank()) {
			throw new Pa11yException(
					"No Chrome debugger address configured. Set one with "
					+ "Pa11yRunner.builder().chromeDebuggerUrl(\"http://<grid-node>:9222\"), "
					+ "the pa11y.chrome.url system property, or the PA11Y_CHROME_URL environment variable.");
		}

		String trimmed = chromeUrl.trim();
		if (trimmed.startsWith("ws://") || trimmed.startsWith("wss://")) {
			// Already a debugger URL -- Selenium's se:cdp capability, most likely. Nothing to look up.
			return trimmed;
		}

		String authority = reachableAuthority(trimmed);
		String versionUrl = "http://" + authority + "/json/version";

		JsonNode version = fetchVersion(versionUrl, timeout, trimmed);
		JsonNode debuggerUrl = version.get("webSocketDebuggerUrl");
		if (debuggerUrl == null || debuggerUrl.asText().isBlank()) {
			throw new Pa11yException(
					"Chrome at " + versionUrl + " returned no webSocketDebuggerUrl. "
					+ "That endpoint is answering, but it is probably not a Chrome DevTools port.");
		}

		return withAuthority(debuggerUrl.asText(), authority);
	}

	/**
	 * Works out the {@code host:port} to actually call, with the host as a literal address
	 * because that is what Chrome's Host header check will accept.
	 *
	 * <p>The authority is split by hand rather than read from {@link URI#getHost()}, which
	 * returns {@code null} for anything it considers an invalid hostname -- underscores, most
	 * notably, and internal Grid nodes are quite often called things like {@code grid_node_3}.
	 * Rejecting those would be pedantry: the name resolves, so it is usable.
	 *
	 * @param chromeUrl the configured endpoint
	 * @return {@code host:port}
	 */
	private static String reachableAuthority(String chromeUrl) {
		String withScheme = chromeUrl.contains("://") ? chromeUrl : "http://" + chromeUrl;
		URI uri;
		try {
			uri = new URI(withScheme);
		} catch (URISyntaxException e) {
			throw new Pa11yException("Chrome debugger address is not a valid URL: " + chromeUrl, e);
		}

		String authority = uri.getRawAuthority();
		if (authority == null || authority.isBlank()) {
			throw new Pa11yException("Chrome debugger address has no host: " + chromeUrl);
		}

		int userInfo = authority.lastIndexOf('@');
		if (userInfo >= 0) {
			authority = authority.substring(userInfo + 1);
		}

		String host;
		int port = DEFAULT_PORT;
		if (authority.startsWith("[")) {
			int close = authority.indexOf(']');
			if (close < 0) {
				throw new Pa11yException("Chrome debugger address has an unterminated IPv6 host: " + chromeUrl);
			}
			host = authority.substring(0, close + 1);
			String remainder = authority.substring(close + 1);
			if (remainder.startsWith(":")) {
				port = port(remainder.substring(1), chromeUrl);
			}
		} else {
			int colon = authority.lastIndexOf(':');
			if (colon >= 0) {
				host = authority.substring(0, colon);
				port = port(authority.substring(colon + 1), chromeUrl);
			} else {
				host = authority;
			}
		}

		if (host.isBlank()) {
			throw new Pa11yException("Chrome debugger address has no host: " + chromeUrl);
		}
		return literalAddress(host) + ":" + port;
	}

	/**
	 * @param text      the port as written
	 * @param chromeUrl the whole address, for the error message
	 * @return the port number
	 */
	private static int port(String text, String chromeUrl) {
		if (text.isBlank()) {
			return DEFAULT_PORT;
		}
		try {
			int port = Integer.parseInt(text);
			if (port < 1 || port > 65535) {
				throw new NumberFormatException(text);
			}
			return port;
		} catch (NumberFormatException e) {
			throw new Pa11yException("Chrome debugger address has an invalid port: " + chromeUrl, e);
		}
	}

	/**
	 * @param host a hostname or address
	 * @return an IP literal, or {@code localhost} unchanged since Chrome allows that by name
	 */
	private static String literalAddress(String host) {
		if (host.equalsIgnoreCase("localhost") || isIpLiteral(host)) {
			return host;
		}
		try {
			String address = InetAddress.getByName(host).getHostAddress();
			// Strip any scope id (fe80::1%eth0) and bracket IPv6 for use in an authority.
			int scope = address.indexOf('%');
			if (scope >= 0) {
				address = address.substring(0, scope);
			}
			return address.indexOf(':') >= 0 ? "[" + address + "]" : address;
		} catch (UnknownHostException e) {
			throw new Pa11yException(
					"Could not resolve the Chrome host '" + host + "'. "
					+ "Check the Grid node's hostname is reachable from the machine running the tests.", e);
		}
	}

	/**
	 * @param host a hostname or address
	 * @return {@code true} if it is already an IP address rather than a name
	 */
	private static boolean isIpLiteral(String host) {
		if (host.startsWith("[") || host.indexOf(':') >= 0) {
			return true;
		}
		return host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
	}

	/**
	 * @param versionUrl  the /json/version endpoint
	 * @param timeout     how long to allow
	 * @param configured  the address as the caller wrote it, for the error message
	 * @return the parsed response
	 */
	private static JsonNode fetchVersion(String versionUrl, Duration timeout, String configured) {
		HttpClient client = HttpClient.newBuilder()
				.connectTimeout(timeout)
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();

		HttpRequest request = HttpRequest.newBuilder(URI.create(versionUrl))
				.timeout(timeout)
				.GET()
				.build();

		HttpResponse<String> response;
		try {
			response = client.send(request, HttpResponse.BodyHandlers.ofString());
		} catch (IOException e) {
			throw new Pa11yException(unreachableAdvice(configured, versionUrl, describe(e)), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new Pa11yException("Interrupted while contacting Chrome at " + versionUrl, e);
		}

		if (response.statusCode() != 200) {
			throw new Pa11yException(
					"Chrome at " + versionUrl + " answered with HTTP " + response.statusCode() + ". "
					+ "A 403 here is usually Chrome's Host header check, which means the debugger is "
					+ "being addressed by a name it will not accept.");
		}

		try {
			return MAPPER.readTree(response.body());
		} catch (IOException e) {
			throw new Pa11yException("Chrome at " + versionUrl + " returned a response that is not JSON.", e);
		}
	}

	/**
	 * A connection refused arrives as a {@link java.net.ConnectException} with no message at
	 * all, which would otherwise read as "... : null" in the one place someone is looking
	 * for a reason.
	 *
	 * @param failure the underlying error
	 * @return something worth printing
	 */
	private static String describe(Exception failure) {
		String message = failure.getMessage();
		if (message != null && !message.isBlank()) {
			return message;
		}
		String name = failure.getClass().getSimpleName();
		return name.isEmpty() ? failure.getClass().getName() : name;
	}

	/**
	 * @param configured  the address as the caller wrote it
	 * @param versionUrl  the endpoint that was tried
	 * @param detail      the underlying error
	 * @return a message that names the usual causes
	 */
	private static String unreachableAdvice(String configured, String versionUrl, String detail) {
		return """
				Could not reach Chrome's debugger at %s (configured as %s): %s

				Chrome on the Grid node needs to have been started with all of:
				  --remote-debugging-port=9222
				  --remote-debugging-address=0.0.0.0   (without this it only listens on loopback)
				  --remote-allow-origins=*             (Chrome 111+ rejects the WebSocket without it)

				and the port has to be open from the machine running the tests."""
				.formatted(versionUrl, configured, detail);
	}

	/**
	 * Swaps the authority of a WebSocket URL, keeping its path.
	 *
	 * @param webSocketUrl what Chrome reported
	 * @param authority    the {@code host:port} that is actually reachable
	 * @return the rewritten URL
	 */
	private static String withAuthority(String webSocketUrl, String authority) {
		URI uri;
		try {
			uri = new URI(webSocketUrl);
		} catch (URISyntaxException e) {
			throw new Pa11yException("Chrome reported an unusable webSocketDebuggerUrl: " + webSocketUrl, e);
		}
		String path = uri.getRawPath() == null ? "" : uri.getRawPath();
		String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
		return "ws://" + authority + path + query;
	}
}
