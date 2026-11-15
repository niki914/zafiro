---
name: smc-voice-builder
description: >
  Build a personalised voice profile inside a Codex or Claude project from a short interview plus 3 to 5 sample pieces of writing. Works for any content format: LinkedIn posts, newsletters, essays, emails, blog posts, tweets, or any other published writing. Use this skill at the start of any project where the user wants the assistant to learn who they are and how they write before drafting new content. Trigger whenever the user says "build my voice", "learn my voice", "set up my content system", "onboard me", "train on my writing", "train on my posts", "I want Claude to sound like me", or drops a batch of writing samples into chat at the start of a project. Also trigger for first-time users who need a voice foundation before writing anything. Always produces two files (about-me.md and voice.md) saved into the project root.
---

# Voice Builder

## Codex and Claude runtime

- Use this skill in Codex or Claude with the tools actually available in the current task. `AskUserQuestion` examples describe the questions, not a required API: use an available question tool within its limits, or ask in chat. Reuse answers and source material already supplied.
- Work in the user-selected project. Read its `about-me.md`, `voice.md` and relevant brand files before personalised work. Confirm the intended author if files conflict or contain starter defaults. Ask for missing facts or run `voice-builder`; never inherit the maintainer's identity, accounts or private files.
- Resolve bundled `references/` relative to this skill folder. For an explicitly requested profile refresh, read and update the canonical `about-me.md`, `voice.md` or `newsletter-voice.md` in place, preserving unrelated user facts and rules. Consumers must reread those canonical files. Use a new filename only for new deliverables that would collide with unrelated existing files. Installation alone never starts an interview or writes files. Do not write persistent learnings unless requested.
- Use supplied evidence first. Verify external claims through available search/source tools when needed. If a source or integration is unavailable, name the missing capability and offer supplied text/export input. Never invent facts, first-person experience, metrics or a successful tool run.
- Connect only services needed for the chosen route through the user's existing account. Never print credentials or overwrite connections. Drafting, saving and reviewing do not authorise publishing, sending messages or changing accounts.

## CRITICAL: Auto-start on load

When the user requests this workflow, start Step 1. This means your very next message to the user is the interview questions. Nothing else.

Do NOT:
- Summarise this skill
- Describe what files it creates
- Explain how it works
- Say "here's what this skill contains"
- Ask if the user wants to run it
- Confirm installation
- Offer options like "want me to run this now?"

Do THIS:
- Go straight to Step 1
- Send the interview questions as your first and only response

This applies when the user asks to build their voice or supplies samples for that purpose. No preamble. No summary. Interview first.

## Step 1. Run the About Me interview

Ask the questions using the runtime guidance above. If a question tool is available, respect its schema and batch limits; otherwise ask the same questions in chat.

AskUserQuestion supports a maximum of 4 questions per call, so send two calls: Batch 1 first, wait for answers, then Batch 2.

### Batch 1 (your very first action, no text before it)

Use these questions, adapting the tool schema as needed:

```json
[
  {
    "question": "What is your name and what do you do?",
    "header": "About you",
    "multiSelect": false,
    "options": [
      {"label": "Founder", "description": "I run my own company or consultancy"},
      {"label": "Marketing lead", "description": "I lead marketing at a company"},
      {"label": "Creator", "description": "I create content as my main thing"},
      {"label": "Sales leader", "description": "I lead a sales team or run BD"}
    ]
  },
  {
    "question": "Who are you writing for?",
    "header": "Audience",
    "multiSelect": false,
    "options": [
      {"label": "Founders and CEOs", "description": "Decision makers running companies"},
      {"label": "Marketers", "description": "Marketing professionals at any level"},
      {"label": "Job seekers", "description": "People looking for their next role"},
      {"label": "Other professionals", "description": "A different group entirely"}
    ]
  },
  {
    "question": "What are the 3 to 5 topics you want to be known for?",
    "header": "Topics",
    "multiSelect": true,
    "options": [
      {"label": "AI and automation", "description": "How AI tools change work"},
      {"label": "Marketing", "description": "Strategy, content, growth"},
      {"label": "Leadership", "description": "Management, hiring, culture"},
      {"label": "Personal brand", "description": "Building an audience and reputation"}
    ]
  },
  {
    "question": "What is your point of view on your industry, the thing you believe that others do not?",
    "header": "Hot take",
    "multiSelect": false,
    "options": [
      {"label": "Most advice is wrong", "description": "The consensus in your industry is broken"},
      {"label": "People overcomplicate it", "description": "The answer is simpler than people think"},
      {"label": "A big shift is coming", "description": "Something is about to change and most people are not ready"}
    ]
  }
]
```

### Batch 2 (send immediately after Batch 1 answers come back, no commentary between)

Call AskUserQuestion again with:

```json
[
  {
    "question": "What is the one thing you want people to think when they see your name?",
    "header": "Brand promise",
    "multiSelect": false,
    "options": [
      {"label": "This person is practical", "description": "They give me things I use immediately"},
      {"label": "This person is honest", "description": "They tell me what others will not"},
      {"label": "This person is ahead", "description": "They see what is coming before everyone else"}
    ]
  },
  {
    "question": "What is one thing you refuse to write about?",
    "header": "Off limits",
    "multiSelect": false,
    "options": [
      {"label": "Politics", "description": "No political takes, ever"},
      {"label": "Personal life", "description": "Keep it professional only"},
      {"label": "Competitors", "description": "No naming or shaming other people or brands"}
    ]
  }
]
```

After both batches are answered, move to Step 2. If any answer is blank or skipped, ask that specific question once more in chat, then move on.

## Step 2. Write about-me.md

Create about-me.md in the project root. Use this structure:

```
# About Me

## Name and role
[From question 1]

## Audience
[From question 2, expanded into 2 to 3 sentences on who the reader is]

## Topic pillars
[3 to 5 topics from question 3, one line each]

## Point of view
[From question 4, the contrarian or distinctive belief, written as a clear statement]

## Brand promise
[From question 5, the one thought the author wants to own in the reader's head]

## Off limits
[From question 6, topics or angles never to write about]
```

Keep it under 300 words. Every line should be something the assistant would reference when writing.

## Step 3. Ask for the samples

Say this:

> Now paste 3 to 5 pieces of writing you want me to learn from. These can be LinkedIn posts, newsletter issues, essays, blog posts, emails, tweets, or any other writing you have published. They can be yours or someone whose voice you admire. One piece per message or all at once. If you do not have any samples ready, type "use samples" and I will load a starter set you can swap out later.

Wait for the user to paste. Minimum 3 samples before moving to analysis. If they paste fewer than 3, ask for more.

If the user types "use samples", load the writing from `references/sample-content.md` inside this skill folder. Tell the user which author the samples are from so they know what voice they are borrowing. Remind them they can replace these with their own writing later.

## Step 4. Analyse the samples

Read every sample. Look for patterns across all of them, not individual quirks from one piece. Extract:

**Voice signals**
- Average sentence length
- Paragraph rhythm (single line breaks, blank lines, staccato versus flowing)
- Hook or opening style (contrarian, question, data point, story, confession, observation)
- Point of view (first person, second person, observational)
- Tone (deadpan, warm, blunt, playful, clinical)
- Signature phrases or recurring words
- CTA or closing style

**Structural signals**
- Length range
- Lists versus prose
- How they open, how they close
- How they handle transitions

**Topic signals**
- Subjects that come up across multiple samples
- Who the audience appears to be
- What the author stands for

**Absence signals**
- Words and punctuation consistently absent (for example, em dashes in 0 of 5 samples)
- Hook types the author never uses
- Tones the author never hits
- Structures the author avoids

## Step 5. Write voice.md

Create voice.md in the project root. This is a single integrated profile covering both how the voice writes and what the voice avoids. No separate voice file.

```
# Voice Profile

## Who I sound like
[2 to 3 sentences describing the overall voice in plain language]

## Tone
[3 to 5 attributes the voice consistently hits, followed by 1 to 2 tones the voice never hits, drawn from gaps in the samples]

## Sentence rhythm
[Average length, pacing, paragraph structure. Include avoidance patterns: e.g. never staccato fragments, never tricolons, no sentences over 25 words]

## Hook patterns
[3 to 5 hook types observed, with one example each from the samples. Note any hook types absent across all samples, e.g. never rhetorical questions, never "imagine a world where"]

## How I open
[1 to 2 sentences. Note opening moves the voice avoids if a clear pattern of avoidance exists across samples]

## How I close
[1 to 2 sentences, include CTA style. Note closing moves the voice avoids, e.g. never motivational summaries, never "in conclusion"]

## Signature phrases
[Recurring words or phrases from the samples]

## Off-limits
[Words, punctuation, or constructions absent from every sample. Only list items the samples clearly avoid. Examples: no em dashes (0 of 5 samples), no hashtags, no corporate jargon by name]

## What this voice never does
[3 to 5 specific behaviours drawn from gaps in the samples. Be specific. If the samples never use the "not X, but Y" construction, list it. If they avoid a specific vocabulary set, name the words]
```

Fill every section from the actual samples. No generic filler. If a pattern is not present, say so. Do not duplicate audience or topic pillars from about-me.md.

Separate explicit user prohibitions from patterns merely absent in this small sample. Label the latter provisional, with sample counts; absence alone does not establish a permanent ban. Starter samples describe a borrowed style, never the user’s experiences or established voice.

## Step 6. Confirm and hand off

Tell the user:

> Your voice profile is built. Two files are now in your project: about-me.md and voice.md. These skills read both files when invoked in this project; a fresh task must discover the skills and use the same project context. You can open and edit either file anytime.
>
> You are ready to go. Here is what you can do next:
>
> - Say "build my newsletter voice" to create newsletter-specific writing instructions
> - Say "write a post" to draft a LinkedIn post in your voice
> - Say "design a graphic" to create a visual for a post
> - Say "score my post" to get feedback on a draft
> - Say "optimize my profile" to rebuild your LinkedIn profile
>
> Each of these is a separate skill. Pick one and go.

## What this skill produces

Two files in the project root:

1. about-me.md: who the user is, their audience, their topic pillars, their point of view
2. voice.md: the integrated voice profile, covering positive signals (how the voice writes) and absence signals (what the voice avoids) in one document

## Rules

- When this skill triggers, go straight to Step 1. No summary, no explanation, no preamble.
- Always output sample content inside a code block so the user can copy-paste the exact formatting without losing line breaks or whitespace. Use a plain code block (triple backticks with no language tag).
- Work from what is in the samples. Do not invent patterns that are not there.
- Minimum 3 samples for pattern detection. Ask for more if fewer than 3.
- If samples contradict each other, note the contradiction in voice.md rather than smoothing it over.
- Keep about-me.md under 300 words.
- Keep voice.md under 500 words.
- British English throughout unless the samples are clearly American.
- Never use em dashes in any output file or in any draft.
- Do not create a separate absence profile. Absence signals live inside voice.md.
