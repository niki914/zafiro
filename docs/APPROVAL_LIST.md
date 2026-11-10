# Approval list: nothing here is added until the owner approves it

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

## B. Still to list (research in progress, nothing added)
Abilities/tools, default skills (GitHub + your Claude-account skills), text actions, MCP servers, AutoGLM/Ruto-GLM-style
features. Each will be listed here with licence, risk and a yes/no box for you.
