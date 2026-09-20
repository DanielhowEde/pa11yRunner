package com.automation.pa11y.internal;

import com.automation.pa11y.Pa11yException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the endpoint lookup against a stand-in for Chrome's DevTools HTTP endpoint,
 * built on the JDK's own HTTP server so that no mocking library is needed.
 */
class ChromeEndpointTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private HttpServer server;

	@AfterEach
	void stopServer() {
		if (server != null) {
			server.stop(0);
		}
	}

	@Test
	@DisplayName("rewrites Chrome's loopback debugger URL to the address that actually reached it")
	void rewritesAuthority() throws IOException {
		// Chrome always describes itself as localhost, whoever is asking. That answer is
		// useless to a client on another machine, so only the path survives.
		int port = serve(200, """
				{"Browser":"Chrome/131.0.0.0",
				 "webSocketDebuggerUrl":"ws://localhost:9222/devtools/browser/8f3c-1a2b"}""");

		String resolved = ChromeEndpoint.resolveWebSocketUrl("http://127.0.0.1:" + port, TIMEOUT);

		assertEquals("ws://127.0.0.1:" + port + "/devtools/browser/8f3c-1a2b", resolved);
	}

	@Test
	@DisplayName("accepts host:port without a scheme")
	void acceptsBareHostAndPort() throws IOException {
		int port = serve(200, """
				{"webSocketDebuggerUrl":"ws://localhost:9222/devtools/browser/abc"}""");

		String resolved = ChromeEndpoint.resolveWebSocketUrl("127.0.0.1:" + port, TIMEOUT);

		assertEquals("ws://127.0.0.1:" + port + "/devtools/browser/abc", resolved);
	}

	@Test
	@DisplayName("passes a ws:// endpoint straight through, so Selenium's se:cdp capability can be used as-is")
	void passesWebSocketUrlThrough() {
		String endpoint = "ws://grid-node-3:9222/devtools/browser/already-known";

		assertEquals(endpoint, ChromeEndpoint.resolveWebSocketUrl(endpoint, TIMEOUT));
	}

	@Test
	@DisplayName("rejects a response with no debugger URL rather than passing an empty endpoint to Node")
	void rejectsResponseWithoutDebuggerUrl() throws IOException {
		int port = serve(200, "{\"Browser\":\"Chrome/131.0.0.0\"}");

		Pa11yException thrown = assertThrows(Pa11yException.class,
				() -> ChromeEndpoint.resolveWebSocketUrl("http://127.0.0.1:" + port, TIMEOUT));

		assertTrue(thrown.getMessage().contains("no webSocketDebuggerUrl"), thrown.getMessage());
	}

	@Test
	@DisplayName("explains a 403, which is what Chrome's Host header check looks like")
	void explainsForbidden() throws IOException {
		int port = serve(403, "Host header is specified and is not an IP address or localhost.");

		Pa11yException thrown = assertThrows(Pa11yException.class,
				() -> ChromeEndpoint.resolveWebSocketUrl("http://127.0.0.1:" + port, TIMEOUT));

		assertTrue(thrown.getMessage().contains("Host header check"), thrown.getMessage());
	}

	@Test
	@DisplayName("names the Chrome flags when nothing is listening")
	void advisesOnUnreachableChrome() {
		// Port 1 is reserved and nothing will be on it.
		Pa11yException thrown = assertThrows(Pa11yException.class,
				() -> ChromeEndpoint.resolveWebSocketUrl("http://127.0.0.1:1", TIMEOUT));

		assertTrue(thrown.getMessage().contains("--remote-debugging-address=0.0.0.0"), thrown.getMessage());
		assertTrue(thrown.getMessage().contains("--remote-allow-origins"), thrown.getMessage());
	}

	@Test
	@DisplayName("says how to configure an endpoint when none was given")
	void rejectsMissingEndpoint() {
		Pa11yException thrown = assertThrows(Pa11yException.class,
				() -> ChromeEndpoint.resolveWebSocketUrl(null, TIMEOUT));

		assertTrue(thrown.getMessage().contains("PA11Y_CHROME_URL"), thrown.getMessage());
	}

	@Test
	@DisplayName("rejects an address with no host")
	void rejectsAddressWithoutHost() {
		assertThrows(Pa11yException.class, () -> ChromeEndpoint.resolveWebSocketUrl("http://", TIMEOUT));
	}

	@Test
	@DisplayName("accepts a hostname with an underscore, which URI.getHost() refuses but Grid nodes often have")
	void acceptsUnderscoreInHostname() {
		// It will not resolve here, and that is fine -- the assertion is that it got as far as
		// trying, rather than being turned away as a malformed address.
		Pa11yException thrown = assertThrows(Pa11yException.class,
				() -> ChromeEndpoint.resolveWebSocketUrl("http://grid_node_3.invalid:9222", TIMEOUT));

		assertFalse(thrown.getMessage().contains("has no host"), thrown.getMessage());
	}

	@Test
	@DisplayName("rejects a port that is not a port")
	void rejectsInvalidPort() {
		assertThrows(Pa11yException.class,
				() -> ChromeEndpoint.resolveWebSocketUrl("http://grid-node-3:not-a-port", TIMEOUT));
		assertThrows(Pa11yException.class,
				() -> ChromeEndpoint.resolveWebSocketUrl("http://grid-node-3:99999", TIMEOUT));
	}

	/**
	 * Starts a one-endpoint stand-in for Chrome on a free port.
	 *
	 * @param status the status to return from /json/version
	 * @param body   the body to return
	 * @return the port it is listening on
	 * @throws IOException if the server cannot start
	 */
	private int serve(int status, String body) throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/json/version", exchange -> {
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(status, bytes.length);
			try (OutputStream response = exchange.getResponseBody()) {
				response.write(bytes);
			}
		});
		server.start();
		return server.getAddress().getPort();
	}
}
