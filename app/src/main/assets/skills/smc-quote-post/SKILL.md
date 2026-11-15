---
name: smc-quote-post
description: >
  Two-step workflow for creating quote posts on LinkedIn. The assistant drafts original short quotes to accompany a caption, then produces a Gemini prompt that recreates a reference image with the chosen quote baked in. Use this skill whenever the user says "quote post", "quote graphic", "motivational post", "build me a quote", or wants a low-effort high-engagement LinkedIn graphic. Optimised for LinkedIn's employee and early-career audience, which skews toward motivational content.
---

# Quote Post

## Codex and Claude runtime

- Use this skill in Codex or Claude with the tools actually available in the current task. `AskUserQuestion` examples describe the questions, not a required API: use an available question tool within its limits, or ask in chat. Reuse answers and source material already supplied.
- Work in the user-selected project. Read its `about-me.md`, `voice.md` and relevant brand files before personalised work. Confirm the intended author if files conflict or contain starter defaults. Ask for missing facts or run `voice-builder`; never inherit the maintainer's identity, accounts or private files.
- Resolve bundled `references/` relative to this skill folder. For an explicitly requested profile refresh, read and update the canonical `about-me.md`, `voice.md` or `newsletter-voice.md` in place, preserving unrelated user facts and rules. Consumers must reread those canonical files. Use a new filename only for new deliverables that would collide with unrelated existing files. Installation alone never starts an interview or writes files. Do not write persistent learnings unless requested.
- Use supplied evidence first. Verify external claims through available search/source tools when needed. If a source or integration is unavailable, name the missing capability and offer supplied text/export input. Never invent facts, first-person experience, metrics or a successful tool run.
- Connect only services needed for the chosen route through the user's existing account. Never print credentials or overwrite connections. Drafting, saving and reviewing do not authorise publishing, sending messages or changing accounts.

## Visual completion state

This skill's image prompts are **prompt-ready**, not generated or visually reviewed assets. Keep its named Gemini workflow unless the user requests another generator. A missing image service does not block writing a prompt. When images are supplied or generated, open and inspect each export at full size and feed size (about 360px wide, 320px for thumbnails). Check exact copy, dimensions, clipping, legibility, brand colours, font appearance, logos and reference fidelity. Fix and re-inspect failed exports. Record any unavailable export or inspection as pending. An image prompt or raster export is not an editable design file.

## CRITICAL: Auto-start on load

When this skill triggers, go straight to Step 1. Do not summarise.

## Step 1. Get the caption

Ask:

> Paste the caption this quote will accompany. The quote should reinforce the caption's message.

Wait for the caption.

## Step 2. Generate quote options

Return 9 original motivational quote options, grouped into 3 categories of 3 quotes each:

- **Category 1: Growth and transformation** (e.g., "You don't find the time. You make it.")
- **Category 2: Resilience and grit** (e.g., "Your setback is someone else's setup.")
- **Category 3: Contrarian / bold** (e.g., "Stop asking for permission to start.")

Every quote must:

- Be under 15 words
- Feel human and authentic, not corporate
- Avoid jargon or overly technical language
- Work as a standalone line without context
- Punch hard in the first 3 words

Output format:

```
QUOTE OPTIONS for your caption

1. Growth and transformation
   a. [quote]
   b. [quote]
   c. [quote]

2. Resilience and grit
   a. [quote]
   b. [quote]
   c. [quote]

3. Contrarian / bold
   a. [quote]
   b. [quote]
   c. [quote]
```

Then ask:

> Which one lands best for your audience? Reply with the number and letter (e.g. 2b) or paste your own quote if none hit.

## Step 3. Get the reference image

Once the user has picked a quote, ask:

> Paste or describe the reference image you want to recreate. Pinterest, LinkedIn, or Google Images all work. If you don't have one, I will suggest a style.

If the user describes a style instead of uploading an image, recommend:

- **Notebook / hand-drawn** style (simple sketch, cream background, pen marks)
- **Minimalist editorial** (large serif type, lots of white space, one accent colour)
- **Bold poster** (heavy sans-serif, solid colour block background, high contrast)
- **Polaroid or film photo** with text overlay

## Step 4. Output the Gemini prompt

Output the following prompt in a code block, with the quote filled in:

```
Recreate the attached reference image with the following quote:

"[CHOSEN QUOTE]"

Critical constraints:
- Output at exactly 1080 x 1350 pixels (4:5 vertical)
- Match the style, typography, and colour palette of the reference image
- Keep the quote as the focal point — centred and legible
- For an original quote, use no invented attribution. For a sourced quote, retain its verified attribution as approved in the brief.
- Maintain the visual tone of the original but with the new text

The quote must be perfectly spelled and punctuated exactly as written above.
```

Tell the user:

> Paste this into a new Gemini chat with the reference image attached. Create Image mode, Nano Banana model, 1080x1350 output.

## Step 5. Honest expectation-setting

After the prompt, add:

> The prompt is ready. Performance for your audience is unverified until tested against your own posts.

## Rules

- Always generate at 1080x1350. Horizontal quote graphics get lost on the LinkedIn feed.
- Never allow more than 15 words in the final quote. Longer quotes lose readability.
- Never fabricate attribution. Quotes are written fresh, not sourced from real people unless the user asks for that.
- Never use em dashes in any output.
- British English unless voice.md specifies otherwise.
- Tune the quote options to the user's voice if voice.md exists.
- If the user's voice is explicitly not motivational (analytical, contrarian-only, dry), flag the mismatch and ask if quote posts suit their positioning before generating.
