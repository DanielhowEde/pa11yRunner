# pa11y-runner

Runs [Pa11y](https://pa11y.org/) against a web page **in the Chrome your Selenium Grid is already
driving**, then consolidates a run's worth of pages into one HTML report.

Pa11y is Node software and there is no Java binding, so this is a small Java library over a small
Node script. What makes it different from the usual wrapper is that it never launches a browser. It
attaches to the one on the Grid node over CDP and scans the tab your test is already on:

```
Your test harness                             Selenium Grid node
 |                                             |
 |-- Selenium navigates to the page ---------> Chrome
 |                                              |  the page under test
 '-- pa11y-runner scan --name checkout          |
       '-- node scanner --- CDP :9222 ----------'
             attaches, scans the open tab in place, hands it back
```

Three things follow from that, and they are the whole reason for this design:

- **Nothing is reloaded.** The page is measured in the state your test left it, so an app that has
  been clicked into a particular state is scanned in that state.
- **The scan is signed in.** It is the same browser, so the session your test established is simply
  there. No second login, no cookie replay.
- **No second browser.** Nothing has to install Chromium next to your tests.

---

## The run

Three commands, matching the three moments in a suite:

```bash
# once, before the suite starts
java -jar pa11y-runner-1.0.0-all.jar clean

# from your test, each time it has navigated to a page worth checking
java -jar pa11y-runner-1.0.0-all.jar scan --chrome grid-node-3:9222 --name checkout

# once, after the suite finishes
java -jar pa11y-runner-1.0.0-all.jar report --out accessibility-report.html
```

`scan` needs no URL, and **it does not navigate**. Your test has already done that. The scanner
attaches over the debug port, finds the page that is open and measures the DOM as it stands —
no `goto`, no reload, nothing fetched.

That is the difference between measuring your application and measuring its start page. It has been
proved end to end against a real Selenium Grid, in the way a reload would have made impossible: a
page that is **clean when served**, a WebDriver session that injects an unlabelled input and an
`alt`-less image into the live DOM and changes the `<title>`, then a scan. The scan reported the
runtime title and exactly those three injected defects. A reload would have fetched the clean page
and found nothing. Afterwards the session was still usable and its DOM still intact.

Pass `--url` only if you want a *different* page loaded in a separate tab.

From Java, that middle command is:

```java
new ProcessBuilder(
        "java", "-jar", "pa11y-runner-1.0.0-all.jar", "scan",
        "--chrome", gridNodeHost + ":9222",
        "--name", "checkout")
    .inheritIO()
    .start()
    .waitFor();
```

Settings can come from the environment instead, which is usually tidier when every test needs them:

```bash
PA11Y_CHROME_URL=http://grid-node-3:9222
PA11Y_REPORTS_DIR=target/pa11y-reports
PA11Y_SCANNER_DIR=/opt/pa11y/scanner
```

With those set, the scan is just `... scan --name checkout`.

### What each command does

| Command | What it does |
|---|---|
| `clean` | Empties the reports directory. Only removes files this tool wrote — a stray config or fixture in the same folder is left alone unless you pass `--all`. |
| `scan` | Scans the open page and saves it as `<name>.json`. Rescanning the same name replaces the earlier result rather than doubling it. |
| `report` | Reads every saved page and writes one self-contained HTML file. |

`pa11y-runner help <command>` explains any of them in full.

### Exit codes

| Code | Meaning |
|------|---------|
| `0`  | the command did what was asked |
| `1`  | errors were found, **and** you passed `--fail-on-error` |
| `2`  | the arguments were wrong |
| `3`  | the command could not be carried out |

A scan that ran exits `0` whatever it found. At scan time the question is whether the scan worked,
not whether the page is perfect — otherwise every page with a known issue would fail your build
step halfway through the suite. Add `--fail-on-error` to `scan` or `report` when you do want
findings to fail something.

`report` exits `3` when there are no saved pages to combine, because that means the scans did not
run rather than that the site is clean.

---

## What the consolidated report shows

The totals are the least interesting part. The report's job is to split the findings in two:

- **Shared across pages** — rules that failed on more than one page. Twenty pages reporting the
  same unlabelled search box is one defect in a shared header, not twenty. These come first, most
  widespread first, because the top of that list is where one fix clears the most findings.
- **Specific to one page** — rules that failed on exactly one page, which are that page's own.

Two findings count as the same problem when they share a **rule code**. Matching on the CSS
selector too would be stricter, but selectors differ between pages almost by definition and
everything would come out unique.

Each rule shows which pages it fired on, how many times on each, the offending elements, and an
example of the markup. The file is self-contained — no stylesheet, script or font is fetched from
anywhere — so it can be attached to a build, emailed, or opened from a share years later.

A run of one page says so plainly rather than pretending it found nothing shared.

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
Puppeteer's bundled Chromium, which is a ~700MB download that nothing here would ever launch — the
browser is always the remote one.

**2. Build:**

```bash
mvn clean package
```

That produces `target/pa11y-runner-1.0.0-all.jar` (self-contained, Jackson relocated, runnable as
the CLI) and `target/pa11y-runner-1.0.0.jar` (a normal Maven dependency).

**3. Open Chrome's debugger on the Grid node** — see [Setting up Chrome](#setting-up-chrome).

---

## Connecting to the Grid's Chrome

**With Selenium Grid, use the `se:cdp` capability.** Not a fixed `:9222`. This is the part that
surprises people, so it is worth being precise about why.

When Grid starts a browser it goes through chromedriver, and **chromedriver binds the DevTools port
to loopback whatever you ask for**. You can pass `--remote-debugging-address=0.0.0.0` on the
`ChromeOptions`, watch it appear in Chrome's command line, and still find 9222 answering only from
inside the node. Nothing is misconfigured; chromedriver simply overrides it. Verified against
`selenium/standalone-chrome`: `curl localhost:9222/json/version` inside the container works,
the same request to the container's own IP does not.

Grid's own CDP endpoint has no such problem — it proxies through the port you already talk to:

```java
String sessionId = ((RemoteWebDriver) driver).getSessionId().toString();
String cdp = "ws://" + gridHost + ":4444/session/" + sessionId + "/se/cdp";
```

then `--chrome "<that URL>"`. A `ws://` or `wss://` URL is passed straight through untouched.

Build the URL from your own Grid address as above rather than reading the `se:cdp` capability
directly. Grid fills that capability in with whatever address *it* thinks it has — on a standalone
container that is the container's internal IP, which your test machine cannot route to. The session
id is the only part you actually need from the driver.

This also removes the multiple-sessions-per-node problem for free: the endpoint is per session, so
nothing has to be a fixed port and nothing can attach to the wrong browser.

### Chrome you launched yourself

If the browser is **not** started by chromedriver — a Chrome you run directly on a machine, or a
container built to expose it — then the flags do work and `--chrome host:9222` is the simpler route:

```
--remote-debugging-port=9222
--remote-debugging-address=0.0.0.0    without this Chrome listens on loopback only
--remote-allow-origins=*              Chrome 111+ rejects the WebSocket without it
```

with port 9222 reachable from wherever the tests run. Both routes are tested end to end, including
from a different machine.

### What the scan does to the tab it borrows

Pa11y sets the viewport and user agent through CDP, and those are *overrides* that outlive the
scan. Since the tab belongs to your WebDriver session and your test carries on using it, the
scanner hands it back as it found it: the emulation overrides are cleared and the injected runner
is removed. It reads the tab's existing size and user agent first and passes those to Pa11y, so
there is no moment where your test's window is a different shape.

If you would rather nothing touched the tab at all, pass `--url` and the scan happens in a separate
tab that is opened and closed around it.

---

## Using it as a library

If you would rather call it in-process than shell out:

```xml
<dependency>
    <groupId>com.automation</groupId>
    <artifactId>pa11y-runner</artifactId>
    <version>1.0.0</version>
    <scope>test</scope>
</dependency>
```

```java
// once, in a base class or @BeforeAll
Pa11yRunner pa11y = Pa11yRunner.builder()
        .chromeDebuggerUrl("http://grid-node-3:9222")
        .build();

// in a test, once it has navigated
ScanResult result = pa11y.scanCurrentPage();
assertTrue(result.errors().isEmpty(), result::describeErrors);
```

`describeErrors()` is built for exactly that assertion message:

```
3 accessibility error(s) on https://example.com/checkout
  - [ERROR] Img element missing an alt attribute. (WCAG2AA...H37) at #basket > img
  - [ERROR] This form field should be labelled in some way. (WCAG2AA...F68) at #promo-code
  - [ERROR] Anchor element found with a valid href attribute, but no link content has been supplied. (WCAG2AA...NoContent) at footer > a:nth-child(3)
```

To save a page for the combined report from Java rather than through the CLI:

```java
new ReportStore(Path.of("target/pa11y-reports")).write(PageReport.of("checkout", request, result));
```

### Scan options

Available on both the CLI and the builder. There is no option for request headers, viewport size,
user agent, POST body or screenshots: all of those only ever applied when loading a URL, and the
normal path scans the tab that is already open, where the browser's own values are what matter.

```java
ScanResult result = pa11y.scan(ScanRequest.currentPage()
        .standard(Standard.WCAG2AA)                 // WCAG2A / WCAG2AA (default) / WCAG2AAA
        .includeWarnings(true)                      // errors only by default
        .waitAfterLoad(Duration.ofMillis(500))      // for content rendered late
        .rootElement("#main")                       // ignore the shared header's known problems
        .hideElements(".third-party-widget")
        .ignore("WCAG2AA.Principle1.Guideline1_4.1_4_3.G18.Fail")
        .timeout(Duration.ofSeconds(90))
        .actions("click element #cookie-accept")    // Pa11y actions, run before the scan
        .build());
```

Define the policy once and point it at each page so every screen is judged by the same rules:

```java
private static final ScanRequest POLICY = ScanRequest.currentPage()
        .rootElement("#main")
        .ignore("WCAG2AA.Principle1.Guideline1_4.1_4_3.G18.Fail")
        .build();

pa11y.scan(POLICY.toBuilder("https://example.com/basket").build());  // same policy, a given URL
```

---

## Failure is not the same as violations

A page with 200 errors produced a **successful scan**. A scanner that could not reach the page
produced **no scan at all**. Those are not the same result and the tool refuses to blur them:

- a scan that ran returns a `ScanResult`, however bad the page is
- a scan that could not run throws `ScanFailedException`, and the CLI exits `3`

This matters more than it sounds. If an unreachable browser quietly returned an empty result, every
Grid outage would look like a page with no accessibility problems, and the build would go green.

`ScanFailedException.kind()` says which it was: `CHROME_UNREACHABLE`, `NAVIGATION_FAILED`,
`TIMEOUT`, `NO_PAGE_OPEN`, `AMBIGUOUS_PAGE`, `SCANNER_NOT_INSTALLED`, `SCANNER_PROCESS_FAILED` or
`SCAN_ERROR`.

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
25 classes; the pinned 3.6.2 reads both.

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

Worth running once against the Grid node itself. The failure modes this tool exists to handle only
appear when the browser is on a different machine from the tests.

---

## Troubleshooting

**`Could not reach Chrome's debugger ... ConnectException`**
Nothing is listening, or the port is firewalled. Check from the test machine:
`curl http://grid-node-3:9222/json/version`.

**HTTP 403 from `/json/version`**
Chrome's DNS-rebinding guard: it refuses any request whose `Host` header is neither `localhost` nor
a bare IP. The tool already resolves hostnames to IPs to avoid this, so a 403 usually means
something in between — a proxy or ingress — is rewriting the `Host` header.

**The WebSocket connection is rejected although `/json/version` works**
Chrome 111+ needs `--remote-allow-origins=*`.

**`NO_PAGE_OPEN`**
Nothing was open to scan. The test should navigate before asking for a scan, or pass `--url`.

**`AMBIGUOUS_PAGE`**
Several tabs are open and none is clearly the active one, so the scanner will not guess which one
you meant. Pass `--url`.

**`Could not find an installed Pa11y scanner`**
`npm ci` has not been run in `scanner/`, or it was run somewhere the tests cannot see. The message
lists every directory that was tried. A directory you configure explicitly is never silently
replaced by another one — a wrong path fails rather than quietly scanning with a different copy of
Pa11y.

**`Could not start 'node'`**
Node 22.13+ or 24+ has to be on the PATH of the process running the tests. Name it explicitly with
`-Dpa11y.node.executable=/usr/local/bin/node` if it is somewhere unusual.

**`report` says there is nothing to combine**
The scans wrote somewhere else. `scan` and `report` have to agree on `--reports-dir`; setting
`PA11Y_REPORTS_DIR` once for the whole run is the reliable way.

**Every page reports the same twenty issues**
That is the report doing its job — look at "Shared across pages". They are probably in a header or
footer. Narrow the scans with `--root-element "#main"`, or drop specific rules with `--ignore`.

**Seeing what the scanner saw**
`--debug` makes the scanner log its progress, including which tab it picked and its URL; the log
is attached to the error when a scan fails.

---

## How it fits together

| | |
|---|---|
| `Pa11yCli` | the three subcommands |
| `Pa11yRunner` | the entry point; resolves the debugger URL and runs a scan |
| `ScanRequest` | what to scan and how — the open tab, or a URL |
| `ScanResult` / `Issue` | what a completed scan found |
| `ScanFailedException` | a scan that did not run, with a `FailureKind` |
| `report/ReportStore` | reads and writes the per-page JSON |
| `report/CombinedReport` | works out what is shared and what is not |
| `report/HtmlReport` | renders it |
| `internal/ChromeEndpoint` | turns `host:9222` into a usable WebSocket URL |
| `internal/NodeScanner` | runs the Node process and reads back its result |
| `scanner/pa11y-cdp-scan.js` | attaches to Chrome and runs Pa11y |

Three details are deliberate and easy to undo by accident:

- The scanner script is **shipped inside the jar** and unpacked at runtime, so it can never drift
  from the Java that drives it. `scanner/` is only needed for `node_modules`.
- The result comes back from Node **in a file, not on stdout**. Puppeteer and Pa11y's runners are
  entitled to print whatever they like, and a parser reading stdout breaks the first time one does.
- One JSON file **per page**, not one shared file appended to. Parallel tests cannot corrupt each
  other's output, and a crashed run still leaves the pages that did finish.
