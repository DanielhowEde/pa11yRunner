# pa11y-runner

Runs [Pa11y](https://pa11y.org/) against a web page **in the Chrome your Selenium Grid is already
driving**, and hands the results to a Java test harness as plain objects.

Pa11y is Node software and there is no Java binding, so this is a small Java library over a small
Node script. What makes it different from the usual wrapper is that it never launches a browser.
It attaches to the one on the Grid node over CDP:

```
Java test harness (wherever your tests run)        Selenium Grid node
 |                                                  |
 |-- Selenium drives the app ---------------------> Chrome
 |                                                   |  tab 1: the page under test
 |                                                   |
 '-- pa11y.scan(url)                                 |
       '-- node pa11y-cdp-scan.js --- CDP :9222 -----'
             Puppeteer attaches, Pa11y scans in
             tab 2, then closes tab 2 and disconnects
```

Three things follow from that, and they are the whole reason for this design:

- **No second browser.** Nothing has to install Chromium next to your tests.
- **The scan is signed in.** The new tab shares the Grid browser's profile, so the cookies your
  test established are already there. No second login, no session replay.
- **Your test's tab is untouched.** Pa11y opens its own tab and closes it again, and the runner
  disconnects rather than closing the browser. The WebDriver session survives the scan.

---

## Quick start

**1. Install the scanner's Node dependencies, once, on the machine that runs the tests:**

```bash
cd scanner
npm ci
```

Node **22.13+ or 24+** is required there (Pa11y 10's own floor). Note this is the *test* machine,
not the Grid node — the Grid node only needs Chrome.

That install takes a few seconds and downloads **no browser**. `scanner/.puppeteerrc.cjs` turns off
Puppeteer's bundled Chromium, which is a ~700MB download that nothing here would ever launch —
the browser is always the remote one.

**2. Build:**

```bash
mvn clean package
```

That produces `target/pa11y-runner-1.0.0.jar` (a normal dependency) and
`target/pa11y-runner-1.0.0-all.jar` (self-contained, Jackson relocated, runnable as a CLI).

**3. Depend on it:**

```xml
<dependency>
    <groupId>com.automation</groupId>
    <artifactId>pa11y-runner</artifactId>
    <version>1.0.0</version>
    <scope>test</scope>
</dependency>
```

**4. Open Chrome's debugger on the Grid node** — see [Setting up Chrome](#setting-up-chrome).

---

## Using it from a test

```java
// once, in a base class or @BeforeAll
Pa11yRunner pa11y = Pa11yRunner.builder()
        .chromeDebuggerUrl("http://grid-node-3:9222")
        .build();

// in a test, once the page under test is on screen
ScanResult result = pa11y.scan(driver.getCurrentUrl());
assertTrue(result.errors().isEmpty(), result::describeErrors);
```

`describeErrors()` is built for exactly that assertion message:

```
3 accessibility error(s) on https://example.com/checkout
  - [ERROR] Img element missing an alt attribute. (WCAG2AA...H37) at #basket > img
  - [ERROR] This form field should be labelled in some way. (WCAG2AA...F68) at #promo-code
  - [ERROR] Anchor element found with a valid href attribute, but no link content has been supplied. (WCAG2AA...NoContent) at footer > a:nth-child(3)
```

Configuration can also come from the environment, which is usually easier in CI:

```bash
PA11Y_CHROME_URL=http://grid-node-3:9222     # or -Dpa11y.chrome.url=...
PA11Y_SCANNER_DIR=/opt/pa11y/scanner         # or -Dpa11y.scanner.dir=...
```

With `PA11Y_CHROME_URL` set, `Pa11yRunner.builder().build()` needs nothing else.

### Scan options

```java
ScanResult result = pa11y.scan(ScanRequest.forUrl(url)
        .standard(Standard.WCAG2AA)                 // WCAG2A / WCAG2AA (default) / WCAG2AAA
        .engines(ScanEngine.HTMLCS, ScanEngine.AXE) // htmlcs by default; both merges the findings
        .includeWarnings(true)                      // errors only by default
        .waitAfterLoad(Duration.ofMillis(500))      // for content rendered after load
        .rootElement("#main")                       // ignore the shared header's known problems
        .hideElements(".third-party-widget")
        .ignore("WCAG2AA.Principle1.Guideline1_4.1_4_3.G18.Fail")
        .header("X-Environment", "staging")
        .timeout(Duration.ofSeconds(90))
        .viewport(375, 812)                         // scan the mobile layout
        .actions("click element #cookie-accept")    // Pa11y actions, run before the scan
        .build());
```

Define the policy once and point it at each page, so every screen is judged by the same rules:

```java
private static final ScanRequest POLICY = ScanRequest.forUrl("about:blank")
        .engines(ScanEngine.HTMLCS, ScanEngine.AXE)
        .rootElement("#main")
        .ignore("WCAG2AA.Principle1.Guideline1_4.1_4_3.G18.Fail")
        .build();

ScanResult result = pa11y.scan(POLICY.toBuilder(driver.getCurrentUrl()).build());
```

### Reading the results

```java
result.errors();          // List<Issue> -- definite failures
result.warnings();        // empty unless includeWarnings(true)
result.hasErrors();
result.countsByCode();    // rule -> count, most frequent first
result.duration();
result.pageUrl();         // after redirects; not necessarily what you asked for

for (Issue issue : result.errors()) {
    issue.selector();     // a CSS selector -- usable with By.cssSelector to go look at it
    issue.context();      // the offending HTML
    issue.engine();       // "htmlcs" or "axe"
}
```

---

## Failure is not the same as violations

A page with 200 errors produced a **successful scan**. A scanner that could not reach the page
produced **no scan at all**. Those are not the same result and the library refuses to blur them:

- a scan that ran returns a `ScanResult`, however bad the page is
- a scan that could not run throws `ScanFailedException`

This matters more than it sounds. If an unreachable browser quietly returned an empty result, every
Grid outage would look like a page with no accessibility problems, and the build would go green.

```java
try {
    ScanResult result = pa11y.scan(url);
    assertTrue(result.errors().isEmpty(), result::describeErrors);
} catch (ScanFailedException e) {
    // e.kind() is CHROME_UNREACHABLE, NAVIGATION_FAILED, TIMEOUT,
    // SCANNER_NOT_INSTALLED, SCANNER_PROCESS_FAILED or SCAN_ERROR
    throw e;   // an infrastructure problem, not an accessibility one
}
```

Both exceptions are unchecked, so the default behaviour is a loud failure at the call site rather
than a `try/catch` that ends up swallowing the problem.

---

## Setting up Chrome

The Grid node's Chrome needs **all three** of these flags:

```
--remote-debugging-port=9222
--remote-debugging-address=0.0.0.0    without this Chrome listens on loopback only
--remote-allow-origins=*              Chrome 111+ rejects the WebSocket without it
```

and port 9222 has to be reachable from wherever the tests run.

If your suite creates the session, they go on the `ChromeOptions`:

```java
ChromeOptions options = new ChromeOptions();
options.addArguments(
        "--remote-debugging-port=9222",
        "--remote-debugging-address=0.0.0.0",
        "--remote-allow-origins=*");
WebDriver driver = new RemoteWebDriver(gridUrl, options);
```

### If a node runs more than one session at a time

A fixed port is a fixed port: two Chromes on one node cannot both have 9222, and the second will
fail to bind or the scan will attach to the wrong browser. Two ways out.

**One session per node.** Simplest, and what the flags above assume.

**Or use Selenium's own CDP endpoint.** Selenium 4 publishes a per-session `se:cdp` capability that
the Grid router proxies to the right browser, so no fixed port is involved:

```java
String cdp = String.valueOf(((RemoteWebDriver) driver).getCapabilities().getCapability("se:cdp"));

Pa11yRunner pa11y = Pa11yRunner.builder()
        .chromeDebuggerUrl(cdp)   // a ws:// URL is passed straight through
        .build();
```

`chromeDebuggerUrl` takes `ws://`/`wss://` URLs untouched, so this needs no change to the library.
Worth knowing: the direct `:9222` route is what has been tested end to end here, including from a
different machine. The `se:cdp` route is supported by the same code path but is worth a one-off
check against your own Grid before relying on it, since Grid's proxying differs by version and
`se:cdp` is absent when `--enable-managed-downloads` or certain Grid configurations are in play.

### What "the same session" does and does not give you

The scan opens a **new tab in the same browser**, so anything held in the browser profile comes
with it: cookies, and `localStorage` for the same origin. That covers ordinary authentication.

It does **not** carry in-memory state. The new tab loads the URL from scratch, so a single-page app
that has built up state through clicking will be scanned in its freshly-loaded condition, not its
current one. If that distinction matters for a screen, drive it there with `actions(...)` or scan a
URL that reconstructs the state.

---

## Command line

For a harness that would rather run a process than take the jar on its classpath:

```bash
java -jar pa11y-runner-1.0.0-all.jar \
  --url https://example.com/checkout \
  --chrome http://grid-node-3:9222 \
  --scanner-dir /opt/pa11y/scanner \
  --include-warnings \
  --out result.json
```

The exit code carries the outcome, so nothing has to be parsed to act on it:

| Code | Meaning |
|------|---------|
| `0`  | the scan ran and found no errors |
| `1`  | the scan ran and found errors |
| `2`  | the arguments were wrong |
| `3`  | the scan could not be run at all |

`--help` lists every option.

---

## Moving to Java 25

Change one property in `pom.xml`:

```xml
<java.release>17</java.release>
```

Nothing here uses a preview feature, an API removed after 17, or anything needing an `add-opens`
flag, so that is the whole migration. The build JDK has to be at least as new as the value.

This has been checked rather than assumed — `mvn -Djava.release=25 clean package` on a Temurin 25
JDK compiles to class file version 69, passes all tests and builds the shaded jar.

One trap, already dealt with: `maven-shade-plugin` reads every class with ASM, so a shade older
than its target Java fails with `Unsupported class file major version`. 3.6.0 could not read Java
25 classes; the pinned 3.6.2 reads both. If a future Java brings that error back, bumping the shade
version is the fix.

---

## Building without a JDK installed

Everything here was built and tested in containers, which is worth knowing if your workstation has
no JDK:

```bash
docker run --rm -v "$PWD:/work" -v "$HOME/.m2:/root/.m2" -w /work \
  maven:3.9-eclipse-temurin-17 mvn -B clean package
```

---

## Running the live tests

`LiveScanTest` is skipped unless `PA11Y_CHROME_URL` is set, so an ordinary build needs neither a
browser nor a network. To run it against a real Chrome:

```bash
PA11Y_CHROME_URL=http://grid-node-3:9222 \
PA11Y_TEST_URL=https://example.com \
  mvn test -Dtest=LiveScanTest
```

Worth running once against the Grid node itself. The failure modes this library exists to handle
only appear when the browser is on a different machine from the tests.

---

## Troubleshooting

**`Could not reach Chrome's debugger ... ConnectException`**
Nothing is listening, or the port is firewalled. Check from the test machine:
`curl http://grid-node-3:9222/json/version`.

**HTTP 403 from `/json/version`**
Chrome's DNS-rebinding guard: it refuses any request whose `Host` header is neither `localhost` nor
a bare IP. The library already resolves hostnames to IPs to avoid this, so a 403 usually means
something in between — a proxy or ingress — is rewriting the `Host` header.

**The WebSocket connection is rejected although `/json/version` works**
Chrome 111+ needs `--remote-allow-origins=*`.

**`Could not find an installed Pa11y scanner`**
`npm ci` has not been run in `scanner/`, or it was run somewhere the tests cannot see. The message
lists every directory that was tried. A directory you configure explicitly is never silently
replaced by another one — a wrong path fails rather than quietly scanning with a different copy of
Pa11y.

**`Could not start 'node'`**
Node 22.13+ or 24+ has to be on the PATH of the process running the tests. Name it explicitly with
`-Dpa11y.node.executable=/usr/local/bin/node` if it is somewhere unusual.

**The scan reports a login page**
The tab is new but the profile is shared, so this normally means the session is cookie-less — an
`Authorization` header rather than a cookie, for instance. Pass it with `.header(...)`.

**Every scan finds the same twenty issues**
They are probably in a shared header or footer. Narrow the scan with `.rootElement("#main")`, or
drop the specific rules with `.ignore(...)`.

**Seeing what the scanner saw**
`.debug(true)` on the builder, or `-Dpa11y.debug=true`, makes the scanner log its progress; the log
is attached to the exception when a scan fails. `.screenCapture(Path.of("scan.png"))` saves a
screenshot of the page as it was scanned.

---

## How it fits together

| | |
|---|---|
| `Pa11yRunner` | the entry point; resolves the debugger URL and runs a scan |
| `ScanRequest` | one page and the options to scan it with |
| `ScanResult` / `Issue` | what a completed scan found |
| `ScanFailedException` | a scan that did not run, with a `FailureKind` |
| `internal/ChromeEndpoint` | turns `host:9222` into a usable WebSocket URL |
| `internal/NodeScanner` | runs the Node process and reads back its result |
| `internal/ScannerLocation` | finds the installed `node_modules` |
| `scanner/pa11y-cdp-scan.js` | attaches to Chrome and runs Pa11y |

Two details in there are deliberate and easy to undo by accident:

- The scanner script is **shipped inside the jar** and unpacked at runtime, so it can never drift
  from the Java that drives it. `scanner/` is only needed for `node_modules`.
- The result comes back **in a file, not on stdout**. Puppeteer and Pa11y's runners are entitled to
  print whatever they like, and a parser reading stdout breaks the first time one of them does.
