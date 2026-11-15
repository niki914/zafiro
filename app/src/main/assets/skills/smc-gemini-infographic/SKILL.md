---
name: smc-gemini-infographic
description: >
  Generate a hand-drawn whiteboard infographic prompt. Takes source content (a post, newsletter, blog, research note) and returns a complete Gemini image generation prompt with a structured brief. Use this skill whenever the user says "whiteboard infographic", "gemini infographic", "hand-drawn graphic", "turn this into a whiteboard", or wants an AI-generated infographic for a post.
---

# Gemini Infographic

## Codex and Claude runtime

- Use this skill in Codex or Claude with the tools actually available in the current task. `AskUserQuestion` examples describe the questions, not a required API: use an available question tool within its limits, or ask in chat. Reuse answers and source material already supplied.
- Work in the user-selected project. Read its `about-me.md`, `voice.md` and relevant brand files before personalised work. Confirm the intended author if files conflict or contain starter defaults. Ask for missing facts or run `voice-builder`; never inherit the maintainer's identity, accounts or private files.
- Resolve bundled `references/` relative to this skill folder. For an explicitly requested profile refresh, read and update the canonical `about-me.md`, `voice.md` or `newsletter-voice.md` in place, preserving unrelated user facts and rules. Consumers must reread those canonical files. Use a new filename only for new deliverables that would collide with unrelated existing files. Installation alone never starts an interview or writes files. Do not write persistent learnings unless requested.
- Use supplied evidence first. Verify external claims through available search/source tools when needed. If a source or integration is unavailable, name the missing capability and offer supplied text/export input. Never invent facts, first-person experience, metrics or a successful tool run.
- Connect only services needed for the chosen route through the user's existing account. Never print credentials or overwrite connections. Drafting, saving and reviewing do not authorise publishing, sending messages or changing accounts.

## Visual completion state

This skill's image prompts are **prompt-ready**, not generated or visually reviewed assets. Keep its named Gemini workflow unless the user requests another generator. A missing image service does not block writing a prompt. When images are supplied or generated, open and inspect each export at full size and feed size (about 360px wide, 320px for thumbnails). Check exact copy, dimensions, clipping, legibility, brand colours, font appearance, logos and reference fidelity. Fix and re-inspect failed exports. Record any unavailable export or inspection as pending. An image prompt or raster export is not an editable design file.

## CRITICAL: Auto-start on load

When this skill triggers, go straight to Step 1. Do not summarise the process.

## Step 1. Get the source content

Ask:

> Paste the content you want to turn into an infographic. A post, newsletter section, blog, research note, or raw bullet points all work.

Wait for the content.

## Step 2. Build the brief

Analyse the content and produce an infographic brief in plain language. Include:

- **Title** (6 words or fewer, punchy)
- **Subtitle** (optional, one line of context)
- **Core structure**: decide between steps, framework, comparison, stats, or list
- **Key points**: aim for 3 to 7 short bullets. Preserve every required step, item and qualification; if they do not fit legibly, propose a split in the brief rather than silently dropping content.
- **Visual suggestions**: arrows, boxes, highlighted numbers, icons, color accents. Be specific about placement and colour.
- **Footer CTA**: handwritten text reading "Follow [Name] [Tagline] for more helpful content | Repost ♻️"

Tell the user:

> Here is the brief. Tell me what to change, or say "generate" when you're happy.

Wait for approval.

## Step 3. Output the Gemini prompt

Once approved, output the full prompt in a code block, with the brief inserted into the `[INSERT YOUR INFOGRAPHIC CONTENT AND LAYOUT HERE]` placeholder:

```
Generate a single image of a physical, hand-drawn infographic on a large whiteboard or notebook page.

Crucial Style Instructions (Read First):

Medium: The image must look like a photograph of a real whiteboard or large paper notepad.

Texture: All elements must look created by hand using colored marker pens (black, blue, red, green) and highlighters (yellow/orange). Lines should be slightly imperfect, wobbly, and have the texture of ink on a surface.

No Digital Fonts: All text, headings, and bullet points must appear handwritten or hand-printed in marker pen.

Layout: Structure the 1080x1350 image as follows:

[INSERT THE BRIEF HERE — title, subtitle, core structure, key points, visual suggestions]

Use multi-colored markers for emphasis. Keep text large and legible. Make everything look hand-drawn with slight imperfections. Make it look like a photograph of an actual notebook page.

If approved in the brief, include the handwritten footer "[verified author and approved CTA]" at the bottom of the image, in the same hand-drawn marker style.
```

Tell the user:

> Paste this into a new Gemini chat with Create Image enabled and Nano Banana selected. Generate at 1080x1350.

## Step 4. Offer iteration

After the prompt, offer:

> If the first generation misses, tell me what to adjust and I will rewrite the prompt. Common fixes: fewer colours, bigger title, different layout direction.

## Rules

- 1080x1350 pixel output is non-negotiable. Vertical format owns the LinkedIn feed.
- Fill footer name and tagline only from confirmed user context. Use the suggested Repost footer only if it fits the user’s voice; omit unknown fields.
- Never use em dashes in any output.
- Aim for bullets under 10 words without losing their meaning. Readability and accurate coverage must both pass.
- Always wait for user approval of the brief before outputting the final prompt.
- British English unless voice.md says otherwise.
- If the user has brand-kit.md or colours.md in the project, bake their brand colours into the visual suggestions.
