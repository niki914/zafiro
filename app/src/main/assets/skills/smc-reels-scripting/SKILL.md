---
name: smc-reels-scripting
description: >
  Turn a reference Instagram Reel into a script for your own Reel, tuned to your voice and repurposed from your newsletter content. Takes a Reel URL or Notion reference link, uses Apify to scrape the video, sends it to Gemini 2.5 Flash for full transcript + hook + structure analysis, then writes a new script applying the same patterns to your newsletter topic. Use this skill whenever the user says "script a reel", "reels scripting", "turn this into a reel", pastes an Instagram Reel URL, or references their Notion outlier reels database. The URL-to-video-analysis route needs APIFY_API_TOKEN and GOOGLE_AI_API_KEY; supplied input can skip the corresponding stages.
---

# Reels Scripting

## Codex and Claude runtime

- Use this skill in Codex or Claude with the tools actually available in the current task. `AskUserQuestion` examples describe the questions, not a required API: use an available question tool within its limits, or ask in chat. Reuse answers and source material already supplied.
- Work in the user-selected project. Read its `about-me.md`, `voice.md` and relevant brand files before personalised work. Confirm the intended author if files conflict or contain starter defaults. Ask for missing facts or run `voice-builder`; never inherit the maintainer's identity, accounts or private files.
- Resolve bundled `references/` relative to this skill folder. For an explicitly requested profile refresh, read and update the canonical `about-me.md`, `voice.md` or `newsletter-voice.md` in place, preserving unrelated user facts and rules. Consumers must reread those canonical files. Use a new filename only for new deliverables that would collide with unrelated existing files. Installation alone never starts an interview or writes files. Do not write persistent learnings unless requested.
- Use supplied evidence first. Verify external claims through available search/source tools when needed. If a source or integration is unavailable, name the missing capability and offer supplied text/export input. Never invent facts, first-person experience, metrics or a successful tool run.
- Connect only services needed for the chosen route through the user's existing account. Never print credentials or overwrite connections. Drafting, saving and reviewing do not authorise publishing, sending messages or changing accounts.

## CRITICAL: Auto-start on load

When this skill triggers, go straight to Step 1. Do not summarise.

## Prerequisites

The full Apify + Gemini video-analysis route needs:

- `APIFY_API_TOKEN` environment variable (Instagram scraping)
- `GOOGLE_AI_API_KEY` environment variable (Gemini 2.5 Flash video analysis)
- Node.js 18+ and the `apify-client` and `@google/generative-ai` packages

Check only the capabilities needed for the chosen route. For URL extraction and Gemini video analysis, verify the user's existing credentials are present without printing them, then verify the provider and model are available. Missing credentials, packages, a private video or an unavailable model leave that stage pending. Explain what is missing; do not modify account configuration or silently substitute a provider.

A supplied video can skip Apify. A supplied transcript/analysis can skip Steps 3 and 4 and support a labelled transcript-based script in Step 5, but is not a Gemini video-analysis result. Reuse installed packages; check current official SDK and actor documentation before generating a helper script, and get the user's approval before incurring provider costs.

## Step 1. Get the reference

Ask:

> Paste the reference Reel URL or Notion link. This is the outlier Reel you want to reverse-engineer the format from.

Wait for the URL.

If the user pastes a Notion link, read it through an available authorised Notion connector, locate the Instagram Reel URL on the page, and extract it. If no Reel URL is found on the Notion page, ask the user to paste the Reel URL directly.

## Step 2. Get the newsletter topic

Ask:

> What's the topic from your newsletter you want to repurpose into this Reel? Paste the relevant newsletter section, or type the core idea in a sentence.

Wait for the topic. Read newsletter-voice.md, voice.md, and about-me.md from the project if they exist, so the script matches the user's voice.

## Step 3. Scrape and download the Reel

Use a new `outputs/reels/<slug>/` folder in the selected project. Reuse an existing suitable helper if present, otherwise write a small task-specific Node.js script there that:

1. Uses `apify-client` and the existing `apify/instagram-reel-scraper` route, after checking its current documented schema, for the single requested Reel. Never scrape comments, replies or comment threads, including via `deepScrape` or `numComments`. Verify the provider supports post/video-only collection before calling it; otherwise request a video upload. Do not guess alternate actor inputs.
2. Extracts `videoUrl` from the returned item.
3. Downloads the video to `outputs/reels/<slug>/downloads/{username}_{shortCode}.mp4`.
4. Saves raw scrape data to `outputs/reels/<slug>/reel_data_{shortCode}.json`.

Run the script. Confirm file size and metadata (views, likes, aggregate comment count if available, caption first 200 chars) before continuing.

A single Reel is a reference, not a verified outlier. Call it an outlier only when supplied or explicitly requested comparison data establishes its performance relative to the same creator’s recent posts; cite the sample, window and multiple. Do not invent that baseline.

## Step 4. Analyse with Gemini 2.5 Flash

Extend the Node script (or run a second pass) that:

1. Reads the downloaded `.mp4` as base64.
2. Calls `genAI.getGenerativeModel({ model: 'gemini-2.5-flash' })`.
3. Sends the video with this exact prompt:

```
I'm studying this Reel to write my own script in a similar style for my audience of [AUDIENCE FROM about-me.md].

## Full Transcript
- Transcribe EVERY word with timestamps

## Hook
- Exact first words spoken
- Word count of the hook
- What makes it stop the scroll?

## Language Patterns
- Average sentence length
- You/your vs I/me ratio
- Transitions between points
- Where are the 'just' minimisers?

## Structure
- Total duration
- Section breakdown with timings
- What's the before/after moment?
- What's the CTA?

## One key insight
- The single most important technique to learn from this Reel
```

Save the analysis to `outputs/reels/<slug>/analysis_reference_{shortCode}.md`.

## Step 5. Write the new Reel script

Using the analysis from Step 4, the newsletter topic from Step 2, and the user's voice files, write a new Reel script to `outputs/reels/<slug>/reel-[slug].md`.

Apply these rules (non-negotiable):

### Hook
- Never open with "I". Use "this", "you", a fact, or a name drop.
- Proven formats: "This changed... forever" / negative flip ("X is useless unless...") / capability statement.
- Hook creates curiosity or pattern interrupt within 3 seconds.
- Mirror the hook's word count and structure from the reference analysis.

### Body
- British English. Short sentences. No em dashes, no semicolons.
- Use "you" and "just" conversationally ("you just drop in...").
- Never merge three or more staccato fragments. Combine into one flowing sentence.
- Never state the conclusion. Let the facts do the work.
- Use a comment CTA only if the user has a real deliverable and a confirmed working automation. Otherwise use a relevant next action that makes no delivery promise.

### Comment trigger (only when supported)
- Single caps word only (SCRIPT, WIKI, PROMPTS, VIDEO).
- Must directly relate to what is being promised.
- No quotes, no "below", no trailing punctuation.

### CTA
- "Comment [WORD] and I'll send you [specific thing]"
- Short. No "the link to my full" padding.

### Duration and structure
- Target 30 to 45 seconds total.
- 2 key points maximum, not 3.
- Caption mirrors the script. Update both together.

### Script file structure

```
# Reel: [title]

## Reference analysis
- URL: [reel url]
- Views: [number]
- Key technique: [from Gemini analysis]

## Duration target
30-45 seconds

## Hook (0-3s)
[Exact words]

## Point 1 ([start]-[end]s)
[Exact words]

## Point 2 ([start]-[end]s)
[Exact words]

## CTA ([start]-[end]s)
[Exact next action, with a comment promise only when delivery is confirmed]

---

## Caption
[Mirror the script, formatted for Instagram]

## Comment trigger (only if configured)
[WORD or not applicable]

## Deliverable (only if promised)
[The real available resource or not applicable]

---

## Visual notes
[Cuts, B-roll ideas, text overlays]
```

## Step 6. QA loop

Review in the running assistant: source accuracy (30), user voice (25), hook and structure (20), spoken duration and clarity (15), caption/CTA consistency (10). Cite evidence for each score. Fix actual violations and re-score, up to three passes. Gate is 95/100; if unresolved, report a draft with the specific blockers rather than inflate the score or claim it is ready. Time a spoken read when available; otherwise label duration estimated. Claude is not required for this review in Codex.

Common violations to check:
- Opens with "I"
- Staccato fragments of three or more
- States the conclusion
- Multi-word or stylised comment trigger
- Duration over 45 seconds when read aloud
- 3 points instead of 2
- Caption does not mirror script

## Step 7. Hand off the script

Deliver the reviewed script and matching caption in the project, with reference source, analysis method and any unresolved checks. The user can record it or explicitly request their own available production workflow. This public skill does not ship an avatar, editing or publishing pipeline.

## Rules

- Never skip the 95/100 QA gate.
- Always read voice.md and about-me.md before writing. Voice match is non-negotiable.
- Never invent metrics from the reference Reel. Use only verified supplied or fetched metadata, and mark absent metrics unavailable.
- British English. No em dashes. No semicolons.
- Every script includes the matching caption; include a comment trigger only when the promised delivery works.
- If reference access fails, report the failed stage and offer supplied video/transcript input. Do not fabricate video analysis.
- Gemini 2.5 Flash is the model. Do not substitute without the user's approval.
