---
name: toolbox
description: Which tool to use for what: web, files, dates, images, scheduling, screen. Load when unsure how to do something.
license: MIT
---

# Toolbox
- **Find information:** `web_search` (no key) then `web_fetch` on the best results; `rss_read` for a blog/Substack/subreddit feed (Substack: https://NAME.substack.com/feed). If a site blocks you, use an MCP search server if one is on, or the screen.
- **Dates:** never guess. `datetime` (now, add, diff, next_weekday) in the owner's zone.
- **Files the owner can open:** `save_file` (save, append, read, delete, list). Always show the returned link. Plans and reports: Markdown or HTML.
- **Images:** `make_card` makes a quote/announcement card PNG (square 1080x1080, portrait 1080x1350, pin 1000x1500). Photos and video need an MCP such as Higgsfield or fal.ai if the owner switched one on.
- **Scheduling:** `schedule` creates follow-ups and recurring jobs (min 15 minutes) with a self-contained prompt; `board` is for handoffs between agents, `schedule` is for later.
- **Phone:** `screen_routine` first, `screen_operation` for one-offs, `open`/`open_uri`/`launch_app` to start apps, `notify` to tell the owner, `take_over` when a human is needed.
- **Data and code:** `http_request` for APIs, `execute_python` for calculations and file processing, `memory` for lasting lessons.
- **Skills:** `load_skill` only when needed; `create_skill` to save a repeatable method.
Prefer the cheapest tool that gives evidence. Report what you actually saw.
