# pa11y-runner

Runs [Pa11y](https://pa11y.org/) against the page in the Chrome session your Selenium Grid is
already driving. Each scan writes a standalone HTML report to `~/target/pa11yReports/<pagename>.html`.

Pa11y is Node software and there is no Java binding, so this is a small Java library over a small
Node script. What makes it different from the usual wrapper is that it never launches a browser. It
attaches to the one on the Grid node over CDP and scans the tab your test is already on:

```
Your test harness                             Selenium Grid node
 |                                             |
 |-- Selenium navigates to the page ---------> Chrome
 |                                              |  the page under test
 '-- pa11y-runner scan --name checkout          |
       '-- node scanner --- Grid session CDP -->'
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

Run one scan after Selenium has navigated to the page:

```bash
java -jar pa11y-runner-1.0.0-all.jar scan \
  --chrome "ws://grid:4444/session/${SESSION_ID}/se/cdp" \
  --name checkout
```

`scan` needs no URL, and **it does not navigate**. Your test has already done that. The scanner
attaches to the Selenium session's CDP endpoint and measures the open page as it stands — no
`goto`, no reload, nothing fetched. The HTML report is written to
`~/target/pa11yReports/checkout.html` by default.

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
        "--chrome", "ws://" + gridHost + ":4444/session/" + sessionId + "/se/cdp",
        "--name", "checkout")
    .inheritIO()
    .start()
    .waitFor();
```

Settings can come from the environment instead, which is usually tidier when every test needs them:

```bash
PA11Y_CHROME_URL="ws://grid:4444/session/${SESSION_ID}/se/cdp"
PA11Y_REPORTS_DIR=~/target/pa11yReports
PA11Y_SCANNER_DIR=/opt/pa11y/scanner
PA11Y_CONFIG_FILE=/opt/ci/pa11yConfig.json
```

With those set, the scan is just `... scan --name checkout`.

### What each command does

| Command | What it does |
|---|---|
| `scan` | Writes `<name>.html` plus a JSON record used by the optional combined report. Rescanning a name replaces both files. |
| `clean` | Removes this tool's saved JSON and matching HTML reports. |
| `report` | Optional: combines saved pages into one self-contained HTML report. |

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

**3. Use the Selenium Grid CDP endpoint for the browser session** — see
[Connecting to the Grid's Chrome](#connecting-to-the-grids-chrome).

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

Pass that URL as `--chrome` or `PA11Y_CHROME_URL`. A `ws://` or `wss://` URL is passed straight
through untouched.

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

## The config file

A `pa11yConfig.json` in the base directory holds the settings that belong to the **project** rather
than to a run, so every page in a suite is judged by the same policy and nobody has to remember the
`--ignore` flag on page nine.

```json
{
  "standard": "WCAG2AA",
  "includeWarnings": true,
  "rootElement": "#main",
  "hideElements": ".third-party-widget",
  "ignore": ["WCAG2AA.Principle1.Guideline1_4.1_4_3.G18.Fail"],
  "actions": ["click element #cookie-accept"],
  "timeout": 60,
  "wait": 500,
  "reportsDir": "target/pa11yReports",
  "scannerDir": "scanner",
  "nodeExecutable": "node",
  "combinedReportName": "combined-report.html",
  "debug": false
}
```

Every key is optional, and `timeout` is in seconds while `wait` is in milliseconds — the same units
as the flags of those names. With no file at all, nothing behaves differently from before.

### Chrome's debugger URL is deliberately not a key

The Grid CDP address contains the session id, so it is different for every run: it belongs to the
scan, not to the project. It stays on `--chrome`, `PA11Y_CHROME_URL` or `chromeDebuggerUrl(...)`.
Writing `chromeUrl` in the file is an error that explains itself rather than a key that is quietly
ignored, because a stale session id would fail looking exactly like a network problem:

```
'chromeUrl' in /repo/pa11yConfig.json cannot be a stored default: the Selenium Grid CDP address
contains the session id, so it is different for every run. Pass it per scan with --chrome, the
PA11Y_CHROME_URL environment variable, or Pa11yRunner.builder().chromeDebuggerUrl(...).
```

`url`, `name` and `runner` are refused the same way, each saying what to pass instead.

### The rules

**Where it is looked for:** the working directory, then up to five ancestors — the same rule that
finds the scanner's `node_modules`, so *the base directory* means one thing in both places.
`--config <file>`, the `pa11y.config.file` system property or `PA11Y_CONFIG_FILE` names one
explicitly instead, and a named file that is not there is an error rather than a reason to go
looking elsewhere.

**Relative paths resolve against the file, not the working directory.** `"reportsDir":
"target/pa11yReports"` means the same place whichever module the suite was started from.

**Precedence** runs flag → system property → environment variable → config file → built-in default.
The exception is the three switches `debug`, `includeWarnings` and `includeNotices`: a command line
switch can only turn something on, so either source saying `true` wins and nothing turns one back
off.

**`ignore` and `actions` are added to by their flags, not replaced** — the file's own come first, so
a per-page `--action` still runs after the cookie banner the whole project has to click through.

**A misspelled key is an error.** It lists the keys that do exist. Silently ignoring `rootElment`
would mean a whole suite scanned without the root element someone configured, with nothing in the
output to say so — the same reason an unknown command line option is refused. The whole file is
checked before anything runs, so a bad value fails the run even when a flag would have overridden
that key: the file is the project's, and a defect in it is a defect whichever flags one run passed.

**Do not keep it in the reports directory.** `clean --all` deletes every `.json` there.

A broken or missing config file exits `2` (the arguments were wrong), not `3` (the command could not
be carried out). It is a problem with the invocation, not with the browser.

---

## The three-call API

For a Selenium suite driving this in-process, `Pa11yUtility` is the whole surface: clear once,
scan each page, combine at the end.

```java
Pa11yUtility pa11y = new Pa11yUtility();

pa11y.clearPa11y();                                  // once, before the suite

// your framework logs in and navigates, as it already does
existingFramework.openDashboard();
pa11y.scanPa11y(chromeUrl, "Dashboard");

existingFramework.openUsers();
pa11y.scanPa11y(chromeUrl, "UserManagement");

pa11y.combinePa11y();                                // once, after the suite
```

That writes `Dashboard.json`, `UserManagement.json` and `combined-report.html` into
`~/target/pa11yReports/`.

### Where `chromeUrl` comes from

**This library holds no WebDriver and has no Selenium dependency.** The suite owns the session;
all that crosses the boundary is the address of that session's Chrome, which the caller builds:

```java
String sessionId = ((RemoteWebDriver) driver).getSessionId().toString();
String chromeUrl = "ws://" + gridHost + ":4444/session/" + sessionId + "/se/cdp";
```

A direct debugger endpoint (`http://host:9222`) works too, for a Chrome not started by
chromedriver — see [Connecting to the Grid's Chrome](#connecting-to-the-grids-chrome).

It is **not** a page URL and is never navigated to. Because the URL names the session directly,
there is nothing to verify: a wrong or stale address cannot attach to the right browser by
accident, it fails. And since there is no driver here, nothing can call `driver.quit()`.

### What the three calls guarantee

- **`clearPa11y()`** removes only files this tool wrote, never recurses, does not follow symlinks,
  and refuses outright to run against a filesystem root or a home directory.
- **`scanPa11y(chromeUrl, pageName)`** measures the page the browser already has open, in the state
  your test left it. `pageName` must be a plain name — a separator, a drive letter or `..` is
  rejected — and **a repeated name is refused rather than overwriting**, because the symptom of
  overwriting is a report quietly missing a page. Values of `password` and `hidden` inputs are
  redacted out of the saved HTML snippets.
- **`combinePa11y()`** groups by rule code, separates rules on several pages from rules on one, lists
  any scan that did not run, and leaves the per-page files in place.

One instance is safe to share across parallel tests: each scan runs its own Node process and writes
its own file by rename, so a reader never sees a half-written one. Give each shard its own output
directory if two shards must scan the same page name.

### The scanner is Pa11y

Pa11y running HTML CodeSniffer, which is what the `Pa11y` in these names refers to. Rule codes are
HTML CodeSniffer codes, so a code like `WCAG2AA.Principle3.Guideline3_1.3_1_1.H57.2` goes straight
into the `ignore` list of `pa11yConfig.json` and applies as written. There is no second engine and no
translation layer, so an ignore code either applies or is rejected as malformed — it can never be
silently dropped.

---

## Using it as a library

For finer control than the three calls give — a different request per page, or the result object
rather than a file — the underlying API is below.

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
        .chromeDebuggerUrl("ws://" + gridHost + ":4444/session/" + sessionId + "/se/cdp")
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
new ReportStore(Path.of(System.getProperty("user.home"), "target", "pa11yReports"))
        .write(PageReport.of("checkout", request, result));
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

Or keep that policy in [the config file](#the-config-file) instead of in Java, which is the same
thing without a recompile:

```java
// once
Pa11yConfig config = Pa11yConfig.discover();          // or Pa11yConfig.load(Path.of(...))

Pa11yRunner pa11y = Pa11yRunner.builder(config)       // scannerDir, node, debug
        .chromeDebuggerUrl(cdpUrlFor(driver))         // per session, so never from the file
        .build();

// per page
ScanResult result = pa11y.scan(config.currentPage().build());
ScanResult basket = pa11y.scan(config.forUrl("https://example.com/basket").build());
```

`config.currentPage()` and `config.forUrl(url)` hand back an ordinary `ScanRequest.Builder` with the
file's values already in it, so anything one page needs differently is set on it as usual.

---

## Failure is not the same as violations

A page with 200 errors produced a **successful scan**. A scanner that could not reach the page
produced **no scan at all**. Those are not the same result and the tool refuses to blur them:

- a scan that ran returns a `ScanResult`, however bad the page is
- a scan that could not run throws `ScanFailedException`, and the CLI exits `3`
- either way the page is **saved**: a failed scan is recorded as `"status": "failed"` with the
  reason, and the combined report lists it under **Scans that did not run**, above the findings

That last point matters as much as the exception. If a failed scan simply left no file, the page
would be missing from the report, and a report with nine clean pages instead of ten reads like good
news. `CombinedReport.isClean()` is false whenever any page went unmeasured, however few issues the
rest of them had.

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
PA11Y_CHROME_URL="ws://grid:4444/session/${SESSION_ID}/se/cdp" \
PA11Y_TEST_URL=https://example.com \
  mvn test -Dtest=LiveScanTest
```

For Selenium Grid, set `PA11Y_CHROME_URL` to that driver's session-specific CDP WebSocket URL.
Directly exposed Chrome can still use its `http://host:9222` debugger endpoint.

---

## Troubleshooting

**`Could not reach Chrome ...`**
For Grid, verify the external Grid host, port, and WebDriver session ID in the
`ws://<grid-host>:4444/session/<session-id>/se/cdp` URL. For directly exposed Chrome, verify that
the debugger endpoint is reachable from the test machine:
`curl http://localhost:9222/json/version`.

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
