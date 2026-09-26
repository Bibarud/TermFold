---
name: termfold-browser
description: Use TermFold's built-in browser to open, look at, click through and test web pages — local dev servers (localhost), HTML files in the project, or any site. Use it after building or changing anything that renders in a browser, to check it really works, to reproduce a UI bug, or when the user asks to see, test or screenshot a page.
---

# TermFold browser

TermFold (the Android app this Linux environment runs in) has a real browser beside the chat.
You can drive it; the user watches you do it. Use it to check your web work instead of guessing.

With MCP tools available (`browser_open`, `browser_snapshot`, `browser_click`, ...) use those.
Otherwise use the `termfold-browser` command in the shell:

```sh
termfold-browser open 5173                 # a dev server on localhost:5173 (or a URL, or ./index.html)
termfold-browser snapshot                  # numbered buttons, links, fields: [12] button "Sign up"
termfold-browser click 12                  # tap by number (or: click "Sign up")
termfold-browser fill 15 "ada@example.com" # replace a field's text; add --submit to press Enter
termfold-browser select 18 "India"         # choose in a dropdown
termfold-browser check 21                  # tick a checkbox (--off to untick)
termfold-browser press Enter               # keys: Tab, Escape, ArrowDown, Control+A ...
termfold-browser scroll down               # or: up, top, bottom, or an element number
termfold-browser wait "Order confirmed"    # wait for text to appear
termfold-browser screenshot                # temporary file under /tmp; prints the path (--save keeps it in the project)
termfold-browser console --errors          # JavaScript errors
termfold-browser network --failed          # failed requests (404, 500, CORS...)
termfold-browser viewport phone            # phone | desktop | fit
termfold-browser eval "document.title"     # run JavaScript in the page
```

## How to work

1. Start the dev server in the background first (`npm run dev > /tmp/dev.log 2>&1 &`), then
   `open` its port. Static sites can be opened as files directly (`open ./index.html`); they
   reload by themselves when files change.
2. `snapshot` before acting and again after the page changes; element numbers come from it.
3. After each change you make, look: `screenshot` (then read the image file) and
   `console --errors`. Fix what is wrong and check again. Screenshots are temporary;
   use `--save` only when the user wants one kept in the project.
4. Test like a user: click through the flow, fill forms with realistic test data, submit, and
   check the result. Try `viewport phone` for layouts.
5. Tell the user what you checked and what you saw.

## Rules

- For testing, use obvious test values, not real card numbers or personal data.
- You may type a password the user gives you. When it is for a real website, tell the user it
  passed through you (the AI) and recommend they change it after this session.
- Do not try to solve CAPTCHAs.
- If a command says the user paused agent control, stop and ask them.
- Screenshots are of the visible area; scroll to see more.
