/**
 * The browser is always remote: this scanner attaches to a Chrome that Selenium Grid is
 * already running, and never launches one. Puppeteer's bundled Chromium would be a ~700MB
 * download on every runner that nothing would ever execute.
 *
 * This is the file Puppeteer 20+ actually reads. The older `puppeteer_skip_download` npm
 * config is ignored, and npm now warns about it.
 *
 * If you ever want the local-launch fallback, delete this file and reinstall.
 */
module.exports = {
	skipDownload: true
};
