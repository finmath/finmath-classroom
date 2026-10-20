const {spawn} = require('node:child_process');
const {mkdtempSync, mkdirSync, rmSync, writeFileSync} = require('node:fs');
const {tmpdir} = require('node:os');
const {join, resolve} = require('node:path');
const {pathToFileURL} = require('node:url');

// Node 22+ provides WebSocket; no npm dependencies are needed.
(async () => {
  const [input, output, chrome] = process.argv.slice(2);
  const profile = mkdtempSync(join(tmpdir(), 'classroom-demo-browser-'));
  mkdirSync(output, {recursive: true});
  const browser = spawn(chrome, [
    '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
    '--disable-background-networking', '--disable-component-update', '--disable-sync',
    '--host-resolver-rules=MAP * ~NOTFOUND', '--remote-debugging-port=0',
    `--user-data-dir=${profile}`, 'about:blank'
  ], {stdio: ['ignore', 'ignore', 'pipe']});
  let socket;
  const timer = setTimeout(() => { browser.kill(); process.exitCode = 1; }, 60000);
  try {
    const address = await new Promise((resolve, reject) => {
      let text = '';
      browser.stderr.on('data', chunk => {
        text += chunk.toString();
        const match = text.match(/DevTools listening on (ws:\/\/[^\s]+)/);
        if (match) resolve(match[1]);
      });
      browser.on('error', reject);
      browser.on('exit', code => reject(new Error(`Chrome exited ${code}`)));
    });
    socket = new WebSocket(address);
    await new Promise((resolve, reject) => {
      socket.addEventListener('open', resolve, {once: true});
      socket.addEventListener('error', reject, {once: true});
    });
    let sequence = 0;
    const pending = new Map();
    socket.addEventListener('message', event => {
      const message = JSON.parse(event.data);
      if (message.id && pending.has(message.id)) {
        const {resolve, reject} = pending.get(message.id);
        pending.delete(message.id);
        message.error ? reject(new Error(JSON.stringify(message.error))) : resolve(message.result);
      }
    });
    function command(method, params = {}, sessionId) {
      const id = ++sequence;
      return new Promise((resolve, reject) => {
        pending.set(id, {resolve, reject});
        socket.send(JSON.stringify({id, method, params, sessionId}));
      });
    }
    const {targetId} = await command('Target.createTarget', {url: 'about:blank'});
    const {sessionId} = await command('Target.attachToTarget', {targetId, flatten: true});
    const pageCommand = (method, params) => command(method, params, sessionId);
    await pageCommand('Page.enable');
    await pageCommand('Network.enable');
    await pageCommand('Network.setBlockedURLs', {urls: ['http://*', 'https://*']});
    const width = 1120;
    await pageCommand('Emulation.setDeviceMetricsOverride', {width, height: 1100, deviceScaleFactor: 1, mobile: false});
    for (const [name, expected] of [
      ['demo-registration', 'Connect GitHub account'],
      ['demo-confirmation', 'Accept assignment'],
      ['demo-repository', 'Repository access is active.']
    ]) {
      await pageCommand('Page.navigate', {url: pathToFileURL(resolve(input, `${name}.html`)).href});
      await new Promise(resolve => setTimeout(resolve, 200));
      const result = await pageCommand('Runtime.evaluate', {expression: `({
        text: document.body.innerText,
        width: document.documentElement.scrollWidth,
        height: Math.max(350, Math.ceil(document.body.getBoundingClientRect().bottom + 48))
      })`, returnByValue: true});
      const page = result.result.value;
      if (!page.text.includes(expected) || page.width > width) throw new Error(`Unexpected page layout: ${name}`);
      const screenshot = await pageCommand('Page.captureScreenshot', {
        format: 'png', captureBeyondViewport: true,
        clip: {x: 0, y: 0, width, height: page.height, scale: 1}
      });
      writeFileSync(join(output, `${name}.png`), Buffer.from(screenshot.data, 'base64'));
      console.log(`${name}.png: ${width} x ${page.height}`);
    }
    await command('Browser.close');
  } finally {
    clearTimeout(timer);
    if (socket) socket.close();
    browser.kill();
    await new Promise(resolve => browser.exitCode !== null || browser.signalCode !== null ? resolve() : browser.once('exit', resolve));
    rmSync(profile, {recursive: true, force: true});
  }
})().catch(error => { console.error(error.message); process.exitCode = 1; });
