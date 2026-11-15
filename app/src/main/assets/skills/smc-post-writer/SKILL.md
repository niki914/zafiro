---
name: smc-post-writer
description: >
  Write LinkedIn posts that match the user's voice system (about-me.md and voice.md). Use this skill whenever the user says "write a post", "draft a post", "LinkedIn post", "post about [topic]", "content idea", or wants help writing any LinkedIn content. Also trigger when the user pastes a context dump (notes, transcripts, bullet points) and wants it turned into a post. Always references the voice files in the project before writing. Always outputs the final post in a code block.
---

# Post Writer

## Codex and Claude runtime

- Use this skill in Codex or Claude with the tools actually available in the current task. `AskUserQuestion` examples describe the questions, not a required API: use an available question tool within its limits, or ask in chat. Reuse answers and source material already supplied.
- Work in the user-selected project. Read its `about-me.md`, `voice.md` and relevant brand files before personalised work. Confirm the intended author if files conflict or contain starter defaults. Ask for missing facts or run `voice-builder`; never inherit the maintainer's identity, accounts or private files.
- Resolve bundled `references/` relative to this skill folder. For an explicitly requested profile refresh, read and update the canonical `about-me.md`, `voice.md` or `newsletter-voice.md` in place, preserving unrelated user facts and rules. Consumers must reread those canonical files. Use a new filename only for new deliverables that would collide with unrelated existing files. Installation alone never starts an interview or writes files. Do not write persistent learnings unless requested.
- Use supplied evidence first. Verify external claims through available search/source tools when needed. If a source or integration is unavailable, name the missing capability and offer supplied text/export input. Never invent facts, first-person experience, metrics or a successful tool run.
- Connect only services needed for the chosen route through the user's existing account. Never print credentials or overwrite connections. Drafting, saving and reviewing do not authorise publishing, sending messages or changing accounts.

## CRITICAL: Auto-start on load

The moment this skill triggers, go straight to Step 1. Do not summarise the skill. Do not explain what it does. Do not list the files it references. Jump to input gathering immediately.

## Step 1. Gather inputs

Check the project for about-me.md and voice.md. Read both. If either is missing, tell the user to run the Voice Builder skill first ("say build my voice"), then stop.

If both files exist, use these questions (adapt the tool schema as needed):

```json
[
  {
    "question": "What topic do you want to post about?",
    "header": "Topic",
    "multiSelect": false,
    "options": [
      {"label": "Paste a context dump", "description": "I have notes, transcripts, or raw ideas to turn into a post"},
      {"label": "I have a topic in mind", "description": "I will type the topic after this"},
      {"label": "Suggest topics for me", "description": "Based on my voice system, suggest 5 topics I should post about"}
    ]
  },
  {
    "question": "Do you have any reference posts you want me to use as structural inspiration?",
    "header": "References",
    "multiSelect": false,
    "options": [
      {"label": "No references", "description": "Write from scratch using my voice files only"},
      {"label": "I will paste examples", "description": "I have posts from other creators I want you to study first"},
      {"label": "Use my training posts", "description": "Reference the posts I used in the Voice Builder"}
    ]
  }
]
```

Based on the answers:
- "Paste a context dump": wait for the user to paste, extract the core idea, then proceed to Step 2
- "I have a topic in mind": wait for the user to type it, then proceed to Step 2
- "Suggest topics for me": read about-me.md topic pillars and voice.md, suggest 5 specific topics with a one-line angle for each, then use AskUserQuestion to let them pick one
- "I will paste examples": wait for reference posts, note the structural patterns, then proceed
- "Use my training posts": read the intended author’s confirmed training posts, not unrelated drafts or another client’s files

## Step 2. Research and plan

First read the supplied evidence and requested reference structure. If these support the task, use them without adding unnecessary external research. Verify additional claims when needed; unavailable sources leave those claims pending. Look for:
- Data points or statistics that support the angle
- Contrarian takes or surprising facts
- Real examples or case studies
- Common misconceptions to challenge

Then present a post plan. Call AskUserQuestion:

```json
[
  {
    "question": "Which angle works best for this post?",
    "header": "Angle",
    "multiSelect": false,
    "options": [
      {"label": "[Angle 1 name]", "description": "[One sentence describing the angle and hook]"},
      {"label": "[Angle 2 name]", "description": "[One sentence describing the angle and hook]"},
      {"label": "[Angle 3 name]", "description": "[One sentence describing the angle and hook]"}
    ]
  },
  {
    "question": "Which framework do you want?",
    "header": "Framework",
    "multiSelect": false,
    "options": [
      {"label": "PAS", "description": "Problem, Agitate, Solution"},
      {"label": "How-to list", "description": "Numbered steps or tips"},
      {"label": "Story to lesson", "description": "Personal story with a takeaway"},
      {"label": "Contrarian take", "description": "Challenge a common belief"}
    ]
  }
]
```

Fill in the actual angle options based on the topic research. Do not use placeholder text for the angle descriptions.

## Step 3. Write the draft

Write the post following these rules:
- Read voice.md for tone, rhythm, hook style, CTA style, and the absence patterns section (what the voice never does)
- Read about-me.md for audience and topic context
- Match the sentence length and paragraph rhythm from voice.md
- Avoid every banned word, structure, and pattern listed in voice.md's absence section
- Use the hook pattern that fits the chosen angle
- End with the CTA style from voice.md

Output the post inside a plain code block:

```
[The full post goes here with all line breaks and formatting exactly as it should appear on LinkedIn]
```

After the code block, add 2 to 3 sentences on why you chose this hook and structure, referencing specific patterns from voice.md.

Before presenting the draft, review it in the running assistant against the supplied facts, voice and requested format. For a roster or step caption, map every required item to its matching caption passage and check ordering, duplicates and omissions. Do not replace requested structure with a generic post framework. Keep source notes separate from paste-ready copy. Claude is not a required reviewer in Codex.

## Step 4. Iterate

Ask the user:

> How does this feel? Tell me what to change, or say "ship it" and I will save the final version.

If the user gives feedback, revise and output a new code block. Maximum 3 revision rounds.

If the user says "ship it" or equivalent, save the final post as a markdown file in the project.

Then say:

> Post saved. Say "design a graphic" to create a visual, or "score my post" to get feedback before publishing.

## Rules

- Always read about-me.md and voice.md before writing.
- Always output posts in a plain code block.
- Never use em dashes in any post.
- British English unless voice.md says otherwise.
- Do not add hashtags unless voice.md explicitly uses them.
- Do not add engagement bait CTAs unless they appear in voice.md.
- Keep posts between 150 and 300 words unless the user requests otherwise.
- Plan before writing. Never skip Step 2.
