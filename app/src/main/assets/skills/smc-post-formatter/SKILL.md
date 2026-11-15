---
name: smc-post-formatter
description: >
  Turn a topic into a ready-to-publish LinkedIn post using PAS, AIDA, BAB, STAR, or SLAY frameworks. Up to 20 nonblank lines, mobile-formatted with blank lines between sentences. Use this skill whenever the user says "format this as a post", "turn this into a LinkedIn post", "write it as PAS" or any named framework, or wants a properly structured post from a topic. Different from post-writer: post-formatter applies a strict framework. post-writer drafts in the user's voice without framework constraints.
---

# Post Formatter

## Codex and Claude runtime

- Use this skill in Codex or Claude with the tools actually available in the current task. `AskUserQuestion` examples describe the questions, not a required API: use an available question tool within its limits, or ask in chat. Reuse answers and source material already supplied.
- Work in the user-selected project. Read its `about-me.md`, `voice.md` and relevant brand files before personalised work. Confirm the intended author if files conflict or contain starter defaults. Ask for missing facts or run `voice-builder`; never inherit the maintainer's identity, accounts or private files.
- Resolve bundled `references/` relative to this skill folder. For an explicitly requested profile refresh, read and update the canonical `about-me.md`, `voice.md` or `newsletter-voice.md` in place, preserving unrelated user facts and rules. Consumers must reread those canonical files. Use a new filename only for new deliverables that would collide with unrelated existing files. Installation alone never starts an interview or writes files. Do not write persistent learnings unless requested.
- Use supplied evidence first. Verify external claims through available search/source tools when needed. If a source or integration is unavailable, name the missing capability and offer supplied text/export input. Never invent facts, first-person experience, metrics or a successful tool run.
- Connect only services needed for the chosen route through the user's existing account. Never print credentials or overwrite connections. Drafting, saving and reviewing do not authorise publishing, sending messages or changing accounts.

## CRITICAL: Auto-start on load

When this skill triggers, go straight to Step 1. Do not summarise. Start input gathering immediately.

## Step 1. Gather inputs

Call AskUserQuestion:

```json
[
  {
    "question": "What topic do you want to post about?",
    "header": "Topic",
    "multiSelect": false,
    "options": [
      {"label": "I will type the topic", "description": "Single sentence describing the subject"},
      {"label": "Paste a context dump", "description": "Notes, stats, transcripts to turn into a post"}
    ]
  },
  {
    "question": "Which framework?",
    "header": "Framework",
    "multiSelect": false,
    "options": [
      {"label": "PAS", "description": "Problem, Agitation, Solution"},
      {"label": "AIDA", "description": "Attention, Interest, Desire, Action"},
      {"label": "BAB", "description": "Before, After, Bridge"},
      {"label": "STAR", "description": "Situation, Task, Action, Result"},
      {"label": "SLAY", "description": "Story, Lesson, Actionable advice, You"},
      {"label": "Pick for me", "description": "Recommend the best framework based on the topic"}
    ]
  }
]
```

Ask one follow-up:

> Anything else I should know? Facts, stats, tone notes, or who this is for.

Wait for response.

## Step 2. Write the post

Apply these global rules to every output:

- Maximum 20 nonblank lines. Target 150 to 200 words where the line limits allow; do not pad to a word minimum. User-supplied copy and required facts take precedence over the target.
- Blank line after every line
- Most lines: one sentence, 55 characters or fewer
- Up to 4 lines may be mini-paragraphs (2 to 3 sentences, 110 characters or fewer)
- Grade 6 words. Zero adverbs, zero jargon, zero fluff
- No em dashes
- No questions unless the hook itself is a question
- No emojis except checkmarks for numbered lists (1. 2. 3.) and the recycle symbol in the CTA
- Rule of Three: use at most two trios per post
- Vary sentence starts. Do not over-use "I"

## Step 3. Structure

- **Line 1 (Hook)**: Plain text for pasting into LinkedIn. 50 characters or fewer.
- **Line 2 (Twist / Contrast)**: 50 characters or fewer. Opposes or surprises the hook.
- **Lines 3 to 18 (Core)**: The chosen framework, split across 3 to 5 lines per stage. Keep the actual number and order of required steps or items; do not force a trio. Use arrows to show flow where useful.

Framework maps:

- **PAS**: Problem -> Agitation -> Solution
- **AIDA**: Attention -> Interest -> Desire -> Action
- **BAB**: Before -> After -> Bridge
- **STAR**: Situation -> Task -> Action -> Result
- **SLAY**: Story -> Lesson -> Actionable advice -> You

- **Final 1 to 2 lines (Wrap and CTA)**: close the lesson within the 20 nonblank line total. Use the author’s closing style. A repost CTA is optional when appropriate.

## Step 4. Output

Output the finished post inside a code block. Keep any next-step question outside the paste-ready copy.

## Step 5. Offer the next move

After the post, ask:

> Want a matching graphic (graphic-designer skill) or want me to score it against your post history (post-scorer skill)?

## Rules

- The code block contains only the finished post.
- Enforce line length, word count, and lines count limits. Count them.
- Never use em dashes.
- British English unless voice.md specifies otherwise.
- If the user has voice.md in the project, tune tone and rhythm to match it.
- Preserve required item coverage. Count nonblank lines and characters in the exact final copy.
