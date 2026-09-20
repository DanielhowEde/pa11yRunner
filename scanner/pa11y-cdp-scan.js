'use strict';

/**
 * Runs Pa11y against a page inside a Chrome that is already running somewhere else --
 * typically a Selenium Grid node started with:
 *
 *   --remote-debugging-port=9222 --remote-debugging-address=0.0.0.0 --remote-allow-origins=*
 *
 * Pa11y normally launches its own Chromium. Here it is handed a browser instead, so the scan
 * happens in the browser the test suite is already driving.
 *
 * Two modes:
 *
 *   current page  the suite has already navigated, so the open tab is scanned where it is.
 *                 Nothing is reloaded, so whatever state the test built up is what gets
 *                 measured. The tab belongs to WebDriver, so anything Pa11y changes about it
 *                 is put back afterwards.
 *
 *   url           a new tab is opened, navigated and closed again. The suite's own tab is
 *                 never touched.
 *
 * Contract with the Java side (com.automation.pa11y.internal.NodeScanner):
 *
 *   stdin   one JSON request object
 *   file    one JSON response object, written to request.outputFile
 *   stdout  diagnostics only, never parsed
 *   stderr  diagnostics only, never parsed
 *
 * The response goes to a file rather than stdout deliberately. Puppeteer, Pa11y's runners and
 * anything they transitively load are free to write to stdout, and a harness that parses
 * stdout breaks the first time one of them does.
 */

const fs = require('fs');
const path = require('path');

/** Exit codes the Java side distinguishes. A scan that ran and found 500 errors exits 0. */
const EXIT_OK = 0;
const EXIT_BAD_REQUEST = 2;
const EXIT_NO_RESPONSE = 3;

/** Targets that are never the page under test. */
const NOT_A_PAGE = ['about:blank', 'devtools://', 'chrome://', 'chrome-extension://', 'edge://'];

main();

async function main() {
	let request;
	try {
		request = await readRequest();
	} catch (error) {
		// No outputFile is known yet, so this is the one failure that can only be reported
		// through stderr and the exit code.
		process.stderr.write('pa11y-cdp-scan: unreadable request: ' + error.message + '\n');
		process.exit(EXIT_BAD_REQUEST);
		return;
	}

	const startedAt = Date.now();
	let browser = null;
	let borrowedPage = null;
	let response;

	try {
		const pa11y = requireFrom(request.modulesDir, 'pa11y');
		const puppeteer = requireFrom(request.modulesDir, 'puppeteer');
		browser = await connectToBrowser(puppeteer, request);

		const target = await resolveTarget(browser, request);
		borrowedPage = target.page;

		const results = await pa11y(target.url, await pa11yOptions(request, browser, target));
		response = completed(results, startedAt);
	} catch (error) {
		response = failed(error, startedAt);
	} finally {
		if (borrowedPage) {
			await handBack(borrowedPage, request);
		}
		// disconnect(), never close(). The browser belongs to Grid and outlives this scan;
		// closing it would kill the WebDriver session that asked for the scan.
		if (browser) {
			try {
				await browser.disconnect();
			} catch (error) {
				log(request, 'disconnect failed (ignored): ' + error.message);
			}
		}
	}

	try {
		fs.writeFileSync(request.outputFile, JSON.stringify(response, null, 2), 'utf8');
	} catch (error) {
		process.stderr.write('pa11y-cdp-scan: could not write ' + request.outputFile + ': ' + error.message + '\n');
		process.exit(EXIT_NO_RESPONSE);
		return;
	}

	process.exit(EXIT_OK);
}

/**
 * Reads the JSON request from stdin.
 * @returns {Promise<Object>} the parsed request
 */
function readRequest() {
	return new Promise((resolve, reject) => {
		let raw = '';
		process.stdin.setEncoding('utf8');
		process.stdin.on('data', chunk => {
			raw += chunk;
		});
		process.stdin.on('error', reject);
		process.stdin.on('end', () => {
			try {
				const request = JSON.parse(raw);
				if (!request.outputFile) {
					throw new Error('request.outputFile is required');
				}
				if (!request.modulesDir) {
					throw new Error('request.modulesDir is required');
				}
				if (!request.scanCurrentPage && !request.url) {
					throw new Error('request.url is required unless request.scanCurrentPage is true');
				}
				resolve(request);
			} catch (error) {
				reject(error);
			}
		});
	});
}

/**
 * Loads a module from the scanner's node_modules by absolute path.
 *
 * This script is extracted from the jar into a temp directory so that it can never drift
 * from the Java that drives it, which puts it outside the normal resolution path. Rather
 * than lean on NODE_PATH, the Java side says exactly which node_modules to use.
 *
 * @param {string} modulesDir - absolute path to a node_modules directory
 * @param {string} name - module to load
 * @returns {*} the module
 */
function requireFrom(modulesDir, name) {
	const location = path.join(modulesDir, name);
	try {
		return require(location);
	} catch (error) {
		throw new ScanError(
			'SCANNER_NOT_INSTALLED',
			'Could not load "' + name + '" from ' + modulesDir +
			'. Run "npm ci" in the scanner directory. (' + error.message + ')'
		);
	}
}

/**
 * Attaches to the already-running Chrome.
 * @param {Object} puppeteer - the puppeteer module
 * @param {Object} request - the scan request
 * @returns {Promise<Object>} a connected browser
 */
async function connectToBrowser(puppeteer, request) {
	log(request, 'connecting to ' + request.browserWSEndpoint);
	try {
		return await puppeteer.connect({
			browserWSEndpoint: request.browserWSEndpoint,
			// null: adopt each tab's own size rather than resizing anything. The window is
			// shared with a live WebDriver session and is not ours to reshape.
			defaultViewport: null,
			protocolTimeout: request.protocolTimeout
		});
	} catch (error) {
		throw new ScanError(
			'CHROME_UNREACHABLE',
			'Could not attach to Chrome at ' + request.browserWSEndpoint + '. ' + error.message
		);
	}
}

/**
 * Decides what is going to be scanned.
 *
 * @param {Object} browser - the connected browser
 * @param {Object} request - the scan request
 * @returns {Promise<Object>} {page, url}; page is null when a new tab should be opened
 */
async function resolveTarget(browser, request) {
	if (!request.scanCurrentPage) {
		// Pa11y is given a browser and no page, so it opens its own tab and closes it again.
		return { page: null, url: request.url };
	}

	const open = await browser.pages();
	const candidates = open.filter(page => isRealPage(page.url()));

	if (candidates.length === 0) {
		throw new ScanError(
			'NO_PAGE_OPEN',
			'Chrome has no page open to scan. The test should navigate before asking for a ' +
			'scan, or pass a URL to scan instead.'
		);
	}

	if (candidates.length === 1) {
		log(request, 'scanning the open page: ' + candidates[0].url());
		return { page: candidates[0], url: candidates[0].url() };
	}

	// More than one tab. The visible one is the one the test is looking at.
	const visible = [];
	for (const page of candidates) {
		try {
			if (await page.evaluate(() => document.visibilityState === 'visible')) {
				visible.push(page);
			}
		} catch (error) {
			log(request, 'could not check visibility of ' + page.url() + ': ' + error.message);
		}
	}

	if (visible.length === 1) {
		log(request, 'scanning the visible page: ' + visible[0].url());
		return { page: visible[0], url: visible[0].url() };
	}

	throw new ScanError(
		'AMBIGUOUS_PAGE',
		'Chrome has ' + candidates.length + ' pages open and none is clearly the active one: ' +
		candidates.map(page => page.url()).join(', ') +
		'. Pass the URL to scan so there is no guessing.'
	);
}

/**
 * @param {string} url - a target's URL
 * @returns {boolean} whether it could be the page under test
 */
function isRealPage(url) {
	return Boolean(url) && !NOT_A_PAGE.some(prefix => url === prefix || url.startsWith(prefix));
}

/**
 * Builds the options passed to Pa11y.
 *
 * @param {Object} request - the scan request
 * @param {Object} browser - the connected browser
 * @param {Object} target - what is being scanned
 * @returns {Promise<Object>} Pa11y options
 */
async function pa11yOptions(request, browser, target) {
	const options = {
		browser: browser,
		standard: request.standard,
		runners: request.runners,
		includeWarnings: request.includeWarnings,
		includeNotices: request.includeNotices,
		timeout: request.timeout,
		wait: request.wait,
		ignore: request.ignore || [],
		headers: request.headers || {},
		method: request.method || 'GET',
		log: {
			debug: message => log(request, message),
			error: message => process.stderr.write('pa11y error: ' + message + '\n'),
			info: message => log(request, message)
		}
	};

	if (target.page) {
		options.page = target.page;
		// Nothing is reloaded: the page is measured as the test left it.
		options.ignoreUrl = true;
		// Pa11y always calls setViewport and setUserAgent. Handing it the values the tab
		// already has makes both no-ops, so a live WebDriver session does not suddenly find
		// itself in a different window size or pretending to be something else.
		options.viewport = await currentViewport(target.page, request);
		options.userAgent = request.userAgent || await currentUserAgent(target.page, request);
	} else {
		options.viewport = request.viewport;
		if (request.userAgent) {
			options.userAgent = request.userAgent;
		}
	}

	// Left unset rather than set to null, because Pa11y's own defaults are the right fallback
	// and node.extend treats an explicit null as a value.
	if (request.rootElement) {
		options.rootElement = request.rootElement;
	}
	if (request.hideElements) {
		options.hideElements = request.hideElements;
	}
	if (request.postData) {
		options.postData = request.postData;
	}
	if (request.screenCapture) {
		options.screenCapture = request.screenCapture;
	}
	if (Array.isArray(request.actions) && request.actions.length > 0) {
		options.actions = request.actions;
	}

	return options;
}

/**
 * @param {Object} page - the page being scanned
 * @param {Object} request - the scan request
 * @returns {Promise<Object>} the tab's current size
 */
async function currentViewport(page, request) {
	try {
		const size = await page.evaluate(() => ({
			width: window.innerWidth,
			height: window.innerHeight
		}));
		if (size && size.width > 0 && size.height > 0) {
			return size;
		}
	} catch (error) {
		log(request, 'could not read the viewport, falling back to the requested one: ' + error.message);
	}
	return request.viewport;
}

/**
 * @param {Object} page - the page being scanned
 * @param {Object} request - the scan request
 * @returns {Promise<string|undefined>} the tab's current user agent
 */
async function currentUserAgent(page, request) {
	try {
		return await page.evaluate(() => navigator.userAgent);
	} catch (error) {
		log(request, 'could not read the user agent: ' + error.message);
		return undefined;
	}
}

/**
 * Returns a borrowed tab to the state WebDriver left it in.
 *
 * Pa11y sets the viewport and user agent through CDP, and those are *overrides*: they stay
 * in force after the scan even when the value set was the one already there. Clearing them
 * matters because the test carries on using this tab.
 *
 * @param {Object} page - the tab that was scanned
 * @param {Object} request - the scan request
 * @returns {Promise<void>} once the tab has been put back
 */
async function handBack(page, request) {
	let client = null;
	try {
		client = await page.createCDPSession();
		await client.send('Emulation.clearDeviceMetricsOverride');
		await client.send('Network.setUserAgentOverride', { userAgent: '' });
	} catch (error) {
		log(request, 'could not clear emulation overrides (ignored): ' + error.message);
	} finally {
		if (client) {
			try {
				await client.detach();
			} catch (error) {
				log(request, 'could not detach the CDP session (ignored): ' + error.message);
			}
		}
	}

	try {
		await page.evaluate(() => {
			delete window.__pa11y;
		});
	} catch (error) {
		log(request, 'could not remove the injected runner (ignored): ' + error.message);
	}
}

/**
 * @param {Object} results - Pa11y's results
 * @param {number} startedAt - epoch millis
 * @returns {Object} the response for a scan that ran
 */
function completed(results, startedAt) {
	return {
		status: 'COMPLETED',
		documentTitle: results.documentTitle || '',
		pageUrl: results.pageUrl || '',
		durationMillis: Date.now() - startedAt,
		issues: (results.issues || []).map(issue => ({
			code: issue.code || '',
			type: issue.type || '',
			typeCode: typeof issue.typeCode === 'number' ? issue.typeCode : -1,
			message: issue.message || '',
			context: issue.context || '',
			selector: issue.selector || '',
			runner: issue.runner || '',
			runnerExtras: issue.runnerExtras || {}
		}))
	};
}

/**
 * A scan that could not run at all. Distinct from a scan that ran and found problems -- see
 * the note on failure vs violations in the README.
 *
 * @param {Error} error - what went wrong
 * @param {number} startedAt - epoch millis
 * @returns {Object} the response for a scan that did not run
 */
function failed(error, startedAt) {
	return {
		status: 'FAILED',
		failureKind: error instanceof ScanError ? error.kind : classify(error),
		message: error.message || String(error),
		documentTitle: '',
		pageUrl: '',
		durationMillis: Date.now() - startedAt,
		issues: []
	};
}

/**
 * Turns a Puppeteer or Pa11y error into a failure kind the Java side can switch on.
 * @param {Error} error - what went wrong
 * @returns {string} a failure kind
 */
function classify(error) {
	const message = (error && error.message) || '';
	if (/net::ERR_|ERR_NAME_NOT_RESOLVED|ERR_CONNECTION/.test(message)) {
		return 'NAVIGATION_FAILED';
	}
	if (/timed out|[Tt]imeout/.test(message)) {
		return 'TIMEOUT';
	}
	if (/Target closed|Session closed|Protocol error|WebSocket/.test(message)) {
		return 'CHROME_UNREACHABLE';
	}
	return 'SCAN_ERROR';
}

/** An error that already knows how it should be reported. */
class ScanError extends Error {
	/**
	 * @param {string} kind - the failure kind
	 * @param {string} message - human-readable detail
	 */
	constructor(kind, message) {
		super(message);
		this.kind = kind;
	}
}

/**
 * @param {Object} request - the scan request
 * @param {string} message - the line to write
 */
function log(request, message) {
	if (request && request.debug) {
		process.stderr.write('pa11y-cdp-scan: ' + message + '\n');
	}
}
