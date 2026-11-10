# Approval list

**Status 2026-11-10 (owner approved "everything, as long as nothing breaks"):** shipped = tools `wait`, `device_info`, `clipboard`; 10 extra text actions; 21 bundled skills (account skills minus Claude-only/desktop-script ones). NOT shipped (needs real work or has real risk): take_over UI, gestures, http_request, schedule_task, send SMS/email, notification/contacts/location, MCP OAuth, node/ffmpeg (see CHANGELOG). Original list below.

(Original text) Nothing here is added until the owner approves it

Rule (owner, 2026-11-10): research and list; the owner approves; then it is built. Evidence tags: VERIFIED (read from the source
this session) / UNVERIFIED. Licenses were read from each repo's LICENSE file; SKILL.md contents were NOT inspected unless noted.

## A. Repos suggested by the owner

| Repo | What it is | License (read) | Runs on the phone? | Proposed use | Verdict |
|---|---|---|---|---|---|
| Panniantong/Agent-Reach | `agent-reach` CLI: lets an agent search/read X, Reddit, YouTube, Bilibili, LinkedIn, Instagram, Facebook, GitHub, web via upstream tools | MIT | Partly. Python parts could run on Chaquopy 3.11, but it leans on a managed browser, login copying from desktop Chrome, yt-dlp and other CLIs | Take the idea + its channel list; implement a few channels as Zafiro Python tools (web read via Jina Reader, YouTube transcript, Reddit/GitHub read). Do not bundle the whole thing | Partial adopt (ideas + selected channels) |
| garrytan/gstack | Markdown slash-command skills that turn a coding agent into a role team (review, QA, ship, security) | MIT | No: needs Bun, git, desktop browser | Zafiro is not a coding agent on a desktop. A few planning/review skills (office-hours, plan-review, retro) could be adapted as plain skills | Low value for the phone; optional 3-4 skills |
| charlie947/social-media-skills | 17 skills: LinkedIn post/hook/voice, newsletter voice, reels script, thumbnail, content matrix, analytics | MIT | Text skills: yes (an LLM just reads them). Some need Apify, Gemini, a browser | Good fit for your "run social media" goal. Proposed as default skills, deletable | Adopt (text skills first) |
| google/artemis | MCP server + CLI that drives a real Android phone over ADB (observe-act loop, traces, diagnostics) | Apache-2.0 | No: needs a Python 3.12+ host with ADB/scrcpy. Cannot run on the phone it controls | Not for the phone. Possible later: use it from a PC to test Zafiro itself | Skip for now |
| latent-spaces/brag | Skill that makes a short launch video; renders through Hyperframes | MIT | No: Node 22+, FFmpeg, headless Chrome | Cloud/PC only | Skip on-device |
| remotion-dev/remotion | Programmatic video with web tech | Remotion License (source-available: free for individuals/non-profits/companies up to 3 employees; otherwise paid) | No: Node + headless browser | Licence risk if you ever grow past 3 people. Do not bundle | Skip (licence) |
| heygen-com/hyperframes | HTML to MP4 renderer + 9 skills (hyperframes, media-use, slideshow, reverse-any-video...) | Apache-2.0 | No: Node 22+, FFmpeg, Chrome | Your Claude account already has a HyperFrames MCP (hosted rendering). Use that route (needs OAuth, see MCP list), not the local renderer | Via hosted MCP only |
| datalab-to/chandra | Chandra OCR 2: image/PDF to Markdown/HTML/JSON | Apache-2.0 code; model weights use modified OpenRAIL-M | No: needs a GPU model server | Could be called as a remote service if you host it. Zafiro already has `view_image` with a vision model, which covers most phone OCR | Skip |
| zhw2590582/ArtPlayer | HTML5 video player JS library | MIT | WebView only; it is a UI component, not an agent ability | Only useful if Zafiro gets an in-app video preview of generated videos | Skip unless you want a preview player |

Honest summary: of these nine, only **social-media-skills** (and a slice of **Agent-Reach**) give Zafiro new abilities that work
on a phone. Most of the rest are desktop video/render tooling; the useful route to them is a hosted MCP, not bundling.

## B. New abilities (built-in tools). Tick the ones you approve

Today Zafiro has: screenshot, view image, memory, screen operation, find/launch apps, notify, shell/Python, web, MCP, skills.
All items below are PROPOSED (not in the app). "Unattended" = what a scheduled run may do without asking.

| # | Ability | Why it helps | Permission / risk | Unattended default |
|---|---|---|---|---|
| 1 | `take_over` (ask a human, then continue) | AutoGLM-style hand-off for login, CAPTCHA, 2FA, payment. Maps to the NEEDS_HUMAN state I just built | none | notify + wait, then NEEDS_HUMAN |
| 2 | `wait` (seconds, or until a screen change) | Apps need time to load; avoids burning tool calls | none | allowed |
| 3 | Gestures: long-press, double-tap, swipe, back/home (if not already in screen operation) | Needed to use "any app" | accessibility (already used) | allowed within budget |
| 4 | `clipboard` read/write | Move text between apps | none (Android 12 shows a toast on read) | allowed |
| 5 | `device_info` (battery, network, storage, time, zone) | Tasks that react to phone state | none | allowed |
| 6 | `http_request` (GET/POST, size/time limits, deny-list for private IPs) | Call any API without writing Python | internet; can leak data | ask / deny for POST |
| 7 | `schedule_task` (agent creates/edits its own tasks) | "Remind me / do X every Monday" from chat | none; can create runaway work, so a per-day cap | ask |
| 8 | `text_to_speech` | Spoken reports | none | allowed |
| 9 | Alarm / timer / calendar-event / share / open-URL via system intents | Real phone actions the user confirms | none (user taps confirm) | allowed |
| 10 | Compose SMS / email (opens the composer, does NOT send) | Drafts for you to send | none | allowed (draft only) |
| 11 | Send SMS / email / post | Outward-facing; irreversible | SMS permission etc. | **Deny** |
| 12 | Read notifications | React to messages, OTP-free 2FA reading is NOT proposed | special access, privacy-sensitive | later |
| 13 | Contacts / calendar / location read | Personal assistant tasks | runtime permissions, privacy | later |
| 14 | File read/write in a shared "Zafiro" folder | Hand files to/from other apps | scoped storage | allowed in folder |

My recommendation to start: 1, 2, 3, 4, 5, 8, 9, 10 (low risk, high value). 6 and 7 next. 11-13 only when you ask.

## C. Text actions (selected-text / screen menu)
Today: Explain, Screen, Translate. Proposed: Summarize, Improve writing, Fix grammar, Make shorter, Make longer, Friendlier tone,
Formal tone, Reply to this, Extract action items, Create a scheduled task from this. (Prompts only; no permissions.)

## D. Default skills (deletable, as you asked)
From your suggestions (licenses read, MIT): the 17 `charlie947/social-media-skills` (voice-builder, post-writer, hook-generator,
reels-scripting, youtube-thumbnail, content-matrix, niche-research, ...), plus 3-4 planning skills adapted from `garrytan/gstack`.
From your Claude account (names only; I have not read their contents here, and ownership/licence must be confirmed before
bundling, because some may be Anthropic-provided and not yours to redistribute): logo-design, brand-guidelines, deep-research,
stop-slop, course-creation, pptx-beautiful, theme-factory, skill-creator, mcp-builder, internal-comms, doc-coauthoring,
morning, i-have-adhd. Anthropic's own docx/pdf/pptx/xlsx skills are source-available, NOT open: I will not bundle them.
Skills that need a desktop (Node/Bun/Chrome/ffmpeg) will not work on the phone and are excluded.

## E. MCP servers
Zafiro's MCP client supports HTTP with static headers only. **No OAuth yet**, so every connector in your Claude account
(Gmail, Calendar, Drive, Sheets, Notion, Supabase, Vercel, Higgsfield, HyperFrames, Metricool, BlogSEO, Vibe Prospecting,
AnimatedWebs) cannot be used from Zafiro until MCP-OAuth is built (roadmap phase 4, about a week of work, needed for all of them).
Usable now without OAuth (URLs from memory, **UNVERIFIED: I will test each endpoint before adding**): DeepWiki (public repo docs,
no key), Context7 (library docs, optional key), GitHub MCP (personal access token header), Exa/Brave search (API key header).
Rule I will apply: read-only first; any MCP tool that sends, posts, deletes or spends money defaults to Ask/Deny when unattended.

## F. AutoGLM / "Ruto-GLM"
AutoGLM (Open-AutoGLM, Apache-2.0) = screenshot -> vision model -> one action per step (tap, swipe, type, long-press, double-tap,
launch app, back/home, wait, take_over, note, interact) with confirmation for sensitive actions. Zafiro already has the
screenshot/vision/tap/type/launch loop; the gaps are items 1-3 above (take_over, wait, gestures) and a "sensitive screen"
confirmation. I still do not have a link for "Ruto-GLM": please send it and I will compare feature by feature instead of guessing.
