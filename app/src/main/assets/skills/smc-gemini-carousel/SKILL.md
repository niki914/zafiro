---
name: smc-gemini-carousel
description: >
  Generate a branded slide-by-slide LinkedIn carousel using Gemini. Takes source content, builds a design brief, waits for approval, then outputs per-slide image generation prompts. 1080x1350 vertical format. Use this skill whenever the user says "carousel", "build a carousel", "turn this into a carousel", "gemini carousel", or wants multi-slide LinkedIn content. Always includes an approval gate between brief and image generation.
---

# Gemini Carousel

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

## Step 1. Gather inputs

Ask:

> Paste the content you want in the carousel. A post, section of a newsletter, research notes, or a framework all work.

Wait for the content, then call AskUserQuestion:

```json
[
  {
    "question": "Brand style?",
    "header": "Style",
    "multiSelect": false,
    "options": [
      {"label": "Pull from brand-kit.md", "description": "Use the colours and typography in my project brand file"},
      {"label": "I will type brand colours", "description": "I will paste hex codes and font preferences"},
      {"label": "Suggest for me", "description": "Pick a palette and typography based on the content"}
    ]
  },
  {
    "question": "Number of slides?",
    "header": "Slides",
    "multiSelect": false,
    "options": [
      {"label": "6 slides", "description": "Concise, fast read"},
      {"label": "8 slides", "description": "Standard carousel length"},
      {"label": "10 slides", "description": "Deep-dive carousel"}
    ]
  }
]
```

## Step 2. Build the design brief

Analyse the content and produce a slide-by-slide brief with:

- **Slide 1 (Cover)**: hook, large bold text, visual direction
- **Slides 2 to N-1 (Body)**: one idea per slide, concise copy and a specific illustration or diagram that explains it. Preserve all required items and source qualifications; propose more slides if needed.
- **Slide N (Ending)**: useful conclusion or next action, with a CTA only for a real user-approved offer or link

For each slide include:

- Slide number
- Headline (max 8 words)
- Body text (max 15 words)
- Visual suggestion (icon, colour block, illustration, diagram)

Tell the user:

> Here is the design brief. Tell me what to change, or say "generate" when you are happy.

Wait for approval. Do not proceed until the user explicitly approves.

## Step 3. Output per-slide prompts

Once approved, output one Gemini image generation prompt per slide, each in its own code block, numbered clearly.

Every prompt follows this structure:

```
Act as an expert graphic designer. Create a LinkedIn carousel slide at 1080x1350 pixels (4:5 aspect ratio).

Brand style:
- Primary colour: [HEX]
- Secondary colour: [HEX]
- Accent colour: [HEX]
- Typography: [bold industrial headline font, clean geometric body font]
- Aesthetic: modern, authoritative, high contrast

Slide [N of M]: [slide purpose]

Content:
- Headline: "[headline text]"
- Body: "[body text]"
- Visual element: [specific visual suggestion]

Layout instructions:
- [Headline placement and size]
- [Body placement and size]
- [Visual placement]
- [Background treatment]

Constraints:
- Vertical 4:5 aspect ratio at exactly 1080x1350 pixels
- No watermarks, no logos unless specified above
- Maintain visual consistency with the other slides in the set
```

Tell the user:

> Paste each prompt into a new Gemini chat with Create Image enabled and Nano Banana selected. Generate slides one at a time for maximum control over consistency.

## Step 4. Offer one-shot alternative

After the per-slide prompts, offer:

> Want a single combined prompt that generates the full carousel in one shot? Faster but less visual consistency. Say "combine" and I will rewrite.

## Rules

- Always gate on user approval of the brief before outputting image prompts.
- 1080x1350 pixels per slide. No other aspect ratio.
- Target 15 words of body text per slide, but never remove a required fact to hit the target. Split the content during briefing when it needs more space.
- Keep the brand style identical across every slide prompt so the set looks like one carousel.
- Cover slide (1) and CTA slide (last) must be visually distinct from body slides.
- Never use em dashes.
- British English unless voice.md specifies otherwise.
- If brand-kit.md exists in the project, read it and use its exact hex codes and typography choices.
