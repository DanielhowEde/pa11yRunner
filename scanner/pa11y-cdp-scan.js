'use strict';

/**
 * Runs Pa11y against a page inside a Chrome that is already running somewhere else --
 * typically a Selenium Grid node started with:
 *
 *   --remote-debugging-port=9222 --remote-debugging-address=0.0.0.0 --remote-allow-origins=*
 *
 * Pa11y normally launches its own Chromium. Here it is handed a browser instead, so the
 * scan happens in the same browser (and therefore the same cookie jar) that the test suite
 * is already driving.
 *
 * Contract with the Java side (com.automation.pa11y.NodeScanner):
 *
 *   stdin   one JSON request object
 *   file    one JSON response object, written to request.outputFile
 *   stdout  diagnostics only, never parsed
 *   stderr  diagnostics only, never parsed
 *
 * The response goes to a file rather than stdout deliberately. Puppeteer, pa11y's runners
 * and anything they transitively load are free to write to stdout, and a harness that
 * parses stdout breaks the first time one of them does.
 */

const fs = require('fs');
const path = require('path');

/** Exit codes the Java side distinguishes. A scan that ran and found 500 errors exits 0. */
const EXIT_OK = 0;
const EXIT_BAD_REQUEST = 2;
const EXIT_NO_RESPONSE = 3;

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
	let response;

	try {
		const pa11y = requireFrom(request.modulesDir, 'pa11y');
		const puppeteer = requireFrom(request.modulesDir, 'puppeteer');
		browser = await connectToBrowser(puppeteer, request);
		const results = await pa11y(request.url, pa11yOptions(request, browser));
		response = completed(results, startedAt);
	} catch (error) {
		response = failed(error, startedAt);
	} finally {
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
 * Builds the options passed to pa11y.
 *
 * Only "browser" is passed, never "page". Given a browser and no page, pa11y opens its own
 * tab and closes that tab when it is done -- so the scan never touches the tab WebDriver is
 * sitting on, and no tab is left behind.
 *
 * @param {Object} request - the scan request
 * @param {Object} browser - the connected browser
 * @returns {Object} pa11y options
 */
function pa11yOptions(request, browser) {
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

	// Left unset rather than set to null, because pa11y's own defaults are the right
	// fallback and node.extend treats an explicit null as a value.
	if (request.rootElement) {
		options.rootElement = request.rootElement;
	}
	if (request.hideElements) {
		options.hideElements = request.hideElements;
	}
	if (request.userAgent) {
		options.userAgent = request.userAgent;
	}
	if (request.postData) {
		options.postData = request.postData;
	}
	if (request.screenCapture) {
		options.screenCapture = request.screenCapture;
	}
	if (request.viewport) {
		options.viewport = request.viewport;
	}
	if (Array.isArray(request.actions) && request.actions.length > 0) {
		options.actions = request.actions;
	}

	return options;
}

/**
 * @param {Object} results - pa11y's results
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
 * A scan that could not run at all. Distinct from a scan that ran and found problems --
 * see the note on failure vs violations in the README.
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
 * Turns a Puppeteer or pa11y error into a failure kind the Java side can switch on.
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
