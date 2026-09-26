#!/opt/node/bin/node
// termfold-browser: lets agents use TermFold's preview browser.
//
//   termfold-browser open http://localhost:5173     termfold-browser snapshot
//   termfold-browser click 12                       termfold-browser fill 15 "hi@example.com"
//   termfold-browser screenshot                     termfold-browser console --errors
//   termfold-browser mcp                            (an MCP server on stdio, for agents)
//
// It talks to the TermFold app over loopback, with the key the app writes to
// ~/.termfold/browser.json. Installed by TermFold; changes here are overwritten.
'use strict';
const fs = require('fs');
const http = require('http');
const path = require('path');
const os = require('os');

const VERSION = '1';

function config() {
  const file = path.join(os.homedir() || '/root', '.termfold', 'browser.json');
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (e) {
    throw new Error('The TermFold browser is not available (no ' + file + '). Is the TermFold app open?');
  }
}

function call(action, args) {
  const { port, token } = config();
  // The agent's working folder: screenshots and relative uploads go to its project.
  const body = Buffer.from(JSON.stringify({ token, action, args: Object.assign({ cwd: process.cwd() }, args || {}) }));
  return new Promise((resolve, reject) => {
    const req = http.request(
      { host: '127.0.0.1', port, path: '/v1', method: 'POST', headers: { 'Content-Type': 'application/json', 'Content-Length': body.length } },
      (res) => {
        const chunks = [];
        res.on('data', (c) => chunks.push(c));
        res.on('end', () => {
          try {
            resolve(JSON.parse(Buffer.concat(chunks).toString('utf8')));
          } catch (e) {
            reject(new Error('Unreadable answer from TermFold.'));
          }
        });
      },
    );
    req.setTimeout(120000, () => req.destroy(new Error('TermFold did not answer in time.')));
    req.on('error', (e) => reject(new Error('Could not reach the TermFold browser (' + e.message + '). Is the TermFold app open?')));
    req.end(body);
  });
}

// ---- Tools -------------------------------------------------------------------------------------

const REF = { type: 'integer', description: 'Element number from browser_snapshot, e.g. 12.' };
const TEXT_TARGET = { type: 'string', description: 'Visible text or label of the element, used when no ref is given.' };
const SELECTOR = { type: 'string', description: 'CSS selector, used when no ref is given.' };

const TOOLS = [
  { name: 'browser_open', action: 'open', description: 'Open a page in the TermFold browser the user can see: a URL, a local port like 5173 (localhost), or a project file path like ./index.html or /root/projects/app/index.html. Waits for it to load.', props: { url: { type: 'string', description: 'URL, port or file path.' } }, required: ['url'] },
  { name: 'browser_snapshot', action: 'snapshot', description: 'Read the current page: title, URL, headings and a numbered list of every button, link, field, checkbox and dropdown with its label and value. Use the numbers with the other tools. Run it again after the page changes.', props: {} },
  { name: 'browser_click', action: 'click', description: 'Tap an element with a real touch (it is scrolled into view first). Give ref, or text, or selector, or x/y in CSS pixels.', props: { ref: REF, text: TEXT_TARGET, selector: SELECTOR, x: { type: 'number' }, y: { type: 'number' } } },
  { name: 'browser_fill', action: 'fill', description: 'Replace the contents of a text field (input, textarea or editable element) with text. Set submit to press Enter afterwards. Refuses password fields on real websites.', props: { ref: REF, selector: SELECTOR, text: { type: 'string', description: 'What to type.' }, submit: { type: 'boolean' } }, required: ['text'] },
  { name: 'browser_type', action: 'type', description: 'Type text with real key presses into whatever is focused (after a click).', props: { text: { type: 'string' } }, required: ['text'] },
  { name: 'browser_press', action: 'press', description: 'Press a key or combination: Enter, Tab, Escape, Backspace, ArrowDown, PageDown, Control+A, Shift+Tab...', props: { key: { type: 'string' } }, required: ['key'] },
  { name: 'browser_select', action: 'select', description: 'Choose an option in a dropdown (select element) by its label or value.', props: { ref: REF, selector: SELECTOR, value: { type: 'string' } }, required: ['value'] },
  { name: 'browser_check', action: 'check', description: 'Tick or untick a checkbox, radio button or switch.', props: { ref: REF, text: TEXT_TARGET, selector: SELECTOR, checked: { type: 'boolean', description: 'true to tick (default), false to untick.' } } },
  { name: 'browser_hover', action: 'hover', description: 'Move the pointer over an element to show hover menus or tooltips.', props: { ref: REF, text: TEXT_TARGET, selector: SELECTOR } },
  { name: 'browser_scroll', action: 'scroll', description: 'Scroll the page with a real swipe: direction up/down (amount in screens, default 0.8) or top/bottom; or give ref/text/selector to bring an element into view.', props: { direction: { type: 'string', enum: ['up', 'down', 'top', 'bottom'] }, amount: { type: 'number' }, ref: REF, text: TEXT_TARGET, selector: SELECTOR } },
  { name: 'browser_wait', action: 'wait', description: 'Wait until some text or a CSS selector appears on the page (or the page finishes loading).', props: { text: { type: 'string' }, selector: SELECTOR, timeout: { type: 'number', description: 'Seconds, default 10.' } } },
  { name: 'browser_screenshot', action: 'screenshot', description: 'Take a screenshot of what the browser shows and look at it. It is not kept; set save only when the user wants it kept in the project (screenshots/).', props: { save: { type: 'boolean' } }, image: true },
  { name: 'browser_text', action: 'text', description: 'Get all readable text on the page.', props: {} },
  { name: 'browser_console', action: 'console', description: 'Read the page console: errors, warnings and console.log output since the page loaded.', props: { errors_only: { type: 'boolean' } } },
  { name: 'browser_network', action: 'network', description: 'List the requests the page made, with failures (HTTP 404/500, network errors) marked.', props: { failed_only: { type: 'boolean' } } },
  { name: 'browser_eval', action: 'eval', description: 'Run JavaScript in the page and return the result (JSON). For reading state, localStorage, computed styles...', props: { script: { type: 'string' } }, required: ['script'] },
  { name: 'browser_navigate', action: null, description: 'Go back, forward, or reload the page.', props: { action: { type: 'string', enum: ['back', 'forward', 'reload'] } }, required: ['action'] },
  { name: 'browser_viewport', action: 'viewport', description: 'Lay the page out at phone width, desktop width, or fit to the pane, to check responsive design.', props: { mode: { type: 'string', enum: ['phone', 'desktop', 'fit'] } }, required: ['mode'] },
  { name: 'browser_upload', action: 'upload', description: 'Choose a file from the Linux environment for a file field (<input type=file>).', props: { ref: REF, selector: SELECTOR, text: TEXT_TARGET, path: { type: 'string', description: 'File path, absolute or relative to the project.' } }, required: ['path'] },
];

function resolveUrl(url) {
  if (!url) return url;
  // Relative file paths are relative to where the agent is working.
  if (url.startsWith('./') || url.startsWith('../') || (!url.includes('://') && !url.startsWith('/') && !url.startsWith('~') && /\.(html?|svg)$/i.test(url))) {
    return path.resolve(process.cwd(), url);
  }
  return url;
}

async function runTool(tool, args) {
  args = Object.assign({}, args || {});
  let action = tool.action;
  if (tool.name === 'browser_navigate') action = args.action;
  if (action === 'open') args.url = resolveUrl(args.url);
  if (tool.image) args.image = true;
  return call(action, args);
}

// ---- MCP server --------------------------------------------------------------------------------

function mcp() {
  const send = (msg) => process.stdout.write(JSON.stringify(msg) + '\n');
  const rl = require('readline').createInterface({ input: process.stdin });
  rl.on('line', async (line) => {
    if (!line.trim()) return;
    let msg;
    try {
      msg = JSON.parse(line);
    } catch (e) {
      return;
    }
    const { id, method, params } = msg;
    if (id === undefined) return; // notifications
    try {
      if (method === 'initialize') {
        send({ jsonrpc: '2.0', id, result: {
          protocolVersion: (params && params.protocolVersion) || '2025-06-18',
          capabilities: { tools: {} },
          serverInfo: { name: 'termfold-browser', version: VERSION },
          instructions: 'The TermFold browser is a real browser on the user\'s screen. Open a page, read it with browser_snapshot, act on elements by their number, and check your work with browser_screenshot and browser_console.',
        } });
      } else if (method === 'ping') {
        send({ jsonrpc: '2.0', id, result: {} });
      } else if (method === 'tools/list') {
        send({ jsonrpc: '2.0', id, result: { tools: TOOLS.map((t) => ({
          name: t.name,
          description: t.description,
          inputSchema: { type: 'object', properties: t.props, required: t.required || [] },
        })) } });
      } else if (method === 'tools/call') {
        const tool = TOOLS.find((t) => t.name === params.name);
        if (!tool) throw new Error('Unknown tool ' + params.name);
        const res = await runTool(tool, params.arguments);
        const content = [];
        if (res.error) content.push({ type: 'text', text: res.error });
        if (res.text) content.push({ type: 'text', text: res.text });
        if (res.image) content.push({ type: 'image', data: res.image, mimeType: res.mime || 'image/jpeg' });
        if (!content.length) content.push({ type: 'text', text: 'Done.' });
        send({ jsonrpc: '2.0', id, result: { content, isError: !!res.error } });
      } else {
        send({ jsonrpc: '2.0', id, error: { code: -32601, message: 'Method not found: ' + method } });
      }
    } catch (e) {
      if (method === 'tools/call') send({ jsonrpc: '2.0', id, result: { content: [{ type: 'text', text: e.message }], isError: true } });
      else send({ jsonrpc: '2.0', id, error: { code: -32603, message: e.message } });
    }
  });
}

// ---- Command line ------------------------------------------------------------------------------

const HELP = `termfold-browser: use TermFold's browser (the preview the user sees).

  open <url|port|file>          open a page (5173, localhost:3000, ./index.html, https://...)
  snapshot                      numbered list of buttons, links and fields on the page
  click <ref|"text">            tap an element (number from snapshot, or its visible text)
  fill <ref> <text> [--submit]  replace a field's contents (and press Enter)
  type <text>                   type into the focused element
  press <key>                   Enter, Tab, Escape, ArrowDown, Control+A ...
  select <ref> <option>         choose in a dropdown
  check <ref> [--off]           tick (or untick) a checkbox or switch
  hover <ref>                   move the pointer over an element
  scroll <up|down|top|bottom|ref> [amount]
  wait <text> [seconds]         wait for text to appear (--selector <css> for a selector)
  screenshot [--save]           screenshot to a temporary file and print its path (open it to look);
                                --save keeps it in the project's screenshots/ folder instead
  text                          all readable text on the page
  console [--errors]            console messages and errors
  network [--failed]            requests the page made, failures marked
  eval <javascript>             run JavaScript in the page, print the result
  back | forward | reload
  viewport <phone|desktop|fit>
  upload <ref> <file>           choose a file for a file field
  mcp                           run as an MCP server on stdio`;

function target(value) {
  if (value === undefined) return {};
  return /^\d+$/.test(value) ? { ref: parseInt(value, 10) } : { text: value };
}

async function cli(argv) {
  const [cmd, ...rest] = argv;
  const flag = (f) => { const i = rest.indexOf(f); if (i >= 0) { rest.splice(i, 1); return true; } return false; };
  const option = (f) => { const i = rest.indexOf(f); if (i >= 0) { const v = rest[i + 1]; rest.splice(i, 2); return v; } return undefined; };
  let action = cmd;
  let args = {};
  switch (cmd) {
    case undefined: case 'help': case '-h': case '--help':
      console.log(HELP); return 0;
    case 'mcp':
      mcp(); return null;
    case 'open': args = { url: resolveUrl(rest.join(' ')) }; break;
    case 'snapshot': case 'text': case 'back': case 'forward': case 'reload': case 'status': break;
    case 'click': case 'hover': args = target(rest.join(' ') || undefined); break;
    case 'fill': { const submit = flag('--submit'); args = Object.assign(target(rest[0]), { text: rest.slice(1).join(' '), submit }); if (args.text === undefined) args.text = ''; if (!('ref' in args)) { args = { selector: rest[0], text: rest.slice(1).join(' '), submit }; } break; }
    case 'type': args = { text: rest.join(' ') }; break;
    case 'press': args = { key: rest.join('+') }; break;
    case 'select': args = Object.assign(target(rest[0]), { value: rest.slice(1).join(' ') }); break;
    case 'check': { const off = flag('--off'); args = Object.assign(target(rest.join(' ')), { checked: !off }); break; }
    case 'scroll': {
      const first = rest[0] || 'down';
      if (['up', 'down', 'top', 'bottom'].includes(first)) args = { direction: first, amount: rest[1] ? parseFloat(rest[1]) : undefined };
      else args = target(first);
      break;
    }
    case 'wait': { const sel = option('--selector'); args = sel ? { selector: sel } : {}; if (rest[0]) args.text = rest[0]; if (rest[1]) args.timeout = parseFloat(rest[1]); break; }
    case 'screenshot': args = { save: flag('--save') }; break;
    case 'console': args = { errors_only: flag('--errors') }; break;
    case 'network': args = { failed_only: flag('--failed') }; break;
    case 'eval': args = { script: rest.join(' ') }; break;
    case 'viewport': args = { mode: rest[0] }; break;
    case 'upload': args = Object.assign(target(rest[0]), { path: path.resolve(process.cwd(), rest[1] || '') }); break;
    default:
      console.error('Unknown command: ' + cmd + '\n\n' + HELP); return 2;
  }
  const res = await call(action, args);
  if (res.text) console.log(res.text);
  if (res.error) { console.error(res.error); return 1; }
  return 0;
}

const result = cli(process.argv.slice(2));
Promise.resolve(result).then((code) => { if (code !== null && code !== undefined) process.exitCode = code; }).catch((e) => { console.error(e.message); process.exitCode = 1; });
