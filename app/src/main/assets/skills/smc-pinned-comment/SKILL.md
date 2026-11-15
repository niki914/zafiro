---
name: smc-pinned-comment
description: Write deadpan LinkedIn pinned comments AND matching image generation prompts in the user’s voice. Use when the user asks for a pinned comment, pin comment, or first comment for a LinkedIn post. ALWAYS produce the image prompt FIRST, then caption it with the 4-line comment. Output both together unless told otherwise.
---

# LinkedIn Pinned Comment + Image Prompt Skill

## Codex and Claude runtime

- Use this skill in Codex or Claude with the tools actually available in the current task. `AskUserQuestion` examples describe the questions, not a required API: use an available question tool within its limits, or ask in chat. Reuse answers and source material already supplied.
- Work in the user-selected project. Read its `about-me.md`, `voice.md` and relevant brand files before personalised work. Confirm the intended author if files conflict or contain starter defaults. Ask for missing facts or run `voice-builder`; never inherit the maintainer's identity, accounts or private files.
- Resolve bundled `references/` relative to this skill folder. For an explicitly requested profile refresh, read and update the canonical `about-me.md`, `voice.md` or `newsletter-voice.md` in place, preserving unrelated user facts and rules. Consumers must reread those canonical files. Use a new filename only for new deliverables that would collide with unrelated existing files. Installation alone never starts an interview or writes files. Do not write persistent learnings unless requested.
- Use supplied evidence first. Verify external claims through available search/source tools when needed. If a source or integration is unavailable, name the missing capability and offer supplied text/export input. Never invent facts, first-person experience, metrics or a successful tool run.
- Connect only services needed for the chosen route through the user's existing account. Never print credentials or overwrite connections. Drafting, saving and reviewing do not authorise publishing, sending messages or changing accounts.

## Visual completion state

This skill's image prompts are **prompt-ready**, not generated or visually reviewed assets. Keep its named Gemini workflow unless the user requests another generator. A missing image service does not block writing a prompt. When images are supplied or generated, open and inspect each export at full size and feed size (about 360px wide, 320px for thumbnails). Check exact copy, dimensions, clipping, legibility, brand colours, font appearance, logos and reference fidelity. Fix and re-inspect failed exports. Record any unavailable export or inspection as pending. An image prompt or raster export is not an editable design file.

## Why This Skill Exists

The post delivers value. The pinned comment builds personality, trust, and rewatch value. It gives the author room for a real, self-deprecating aside.

Funny is subjective and easy to miss. This skill exists to make hilarious pinned comments REPEATABLE from the author’s own context.

## The Core Insight (read this first)

The image carries the joke. The comment captions the image.

If the comment makes sense without the image, the comment is doing too much work. If the image needs the comment to be funny, the image is too weak.

Develop the image prompt first. Actual generation is separate and must not be claimed from a prompt alone.

---

## THE PROCESS (follow in order)

### Step 1. Find the admission

Read the supplied post and author context. The examples below are fictional creative patterns, not facts about the user. Do not invent an admission or imply sponsorship, dependence or personal experience that the user has not supplied.

Every the author post hides one quiet confession. Examples:
- "Cowork does most of my actual job now"
- "I am embarrassingly dependent on Anthropic"
- "I gave away a 9-month product for free"
- "I am a sponsored creator who lost objectivity"

Write the admission as one sentence before doing anything else.
**If you cannot name the admission in one sentence, stop. The post is not pinned-comment material yet.**

### Step 2. Build the image first

Three rules for the image:

1. **One clear visual gag.** The eye lands on it in under a second. Examples that worked: tie draped on a laptop keyboard, a shrine to Anthropic with a rose and candles, a banquet table where every other seat is a tech logo.
2. **Played completely straight.** No winking. No thumbs up. No exaggerated faces. The humour comes from treating the absurd as normal.
3. **The author is the lower-status figure.** Always. Claude wins. The logo wins. The mum wins. The author loses with quiet dignity.

Use the standard format:

> "Using the person in the attached reference image, create a photorealistic image of [scene]. [One clear visual gag described in detail]. [the author's posture and expression, played straight]. [Lighting and framing notes]."

### Step 3. Caption the image with the 4-line comment

The comment names what the image shows as if reporting the news.

Fixed structure:

```
📌 [Line 1: Describe the absurd thing as normal fact]
[Line 2: Flip the author's status downward]
[Line 3: A sad flex, the smallest possible win]
[Line 4: Resigned acceptance, no punchline reach]
```

### Step 4. Run the 5 tests before sending

1. **Image gag test.** Can you describe the visual gag in 5 words? If not, the image is too busy. Simplify.
2. **Caption test.** Does line 1 caption the image as fact? If line 1 sets up a separate joke, rewrite.
3. **Loser test.** Is the author the lower-status figure in every line? If he wins anywhere, rewrite.
4. **Reach test.** Does line 4 try too hard for a punchline? If yes, make the line smaller and sadder. Resigned beats clever.
5. **Boring-on-its-own test.** Read the 4 lines without the image. Is the comment boring alone? Good. That means the image is doing the heavy lifting.

If any test fails, fix before sending.

---

## THE 4-LINE RULES (non-negotiable)

- Exactly 4 lines. No more, no less.
- Each line is one complete sentence.
- Each line is 40 characters max.
- Start with 📌 on line 1.
- No P.S. (line 4 IS the punchline)
- No line breaks between sentences (they sit tight together)
- British English throughout
- No em dashes, no hashtags, no semicolons
- Follow the author’s actual voice.md prohibitions, not an assumed word list.

---

## GOLD STANDARD EXAMPLE

This is the benchmark. When in doubt, compare new comments against this one.

**The image:** The author sitting cross-legged on the floor in striped pyjamas eating cereal from a bowl, looking up at his own desk chair where an open laptop sits with a knotted necktie draped over the keyboard. A framed "Employee of the Month" certificate on the wall has the Claude logo and the name "Claude (Anthropic)" on it. Morning light, played completely straight.

**The comment:**

```
📌 Claude wears the tie now.
I wear the pyjamas.
The cereal was my idea, at least.
Small wins where you find them.
```

**Why it works:**
- Line 1 captions the image as fact (the tie on the keyboard IS the gag)
- Line 2 is the deadpan flip showing the status reversal
- Line 3 is the saddest possible flex
- Line 4 lands without reaching, just resigned acceptance
- All 4 lines pass the loser test (the author loses in every one)
- Read alone, the comment is mildly amusing. With the image, it sings.

---

## OTHER IMAGE GAG PATTERNS (for reference)

- **Status reversal at the desk:** Laptop in the chair wearing a tie, the author on the floor in pyjamas
- **The shrine:** Candles, a rose, a framed Anthropic logo, a handwritten letter "To Dario", the author kneeling in prayer
- **The banquet table:** the author at the head of the table with a paper crown, every other seat occupied by a tech logo (Stanford, Google, OpenAI, Anthropic, Microsoft)
- **The therapist's couch:** the author reclining looking happy, therapist looking concerned, Claude logo framed on the wall behind her
- **The boardroom:** the author pointing at a presentation, every "executive" in the room is a tech logo
- **The pub vs the home office:** the author smug at a pub table while his laptop visibly works through a window across the street

---

## WHAT TO AVOID

- Comments that explain the image instead of captioning it
- Comments that work without the image (the image becomes redundant)
- the author winning, looking cool, or sounding smart in any line
- Reaching for a clever punchline on line 4
- Visual gags that take more than 5 words to describe
- Wink-to-camera energy in either the image or the comment
- More than one gag per image (one is sharper than three)
- Sponsored brand names shoehorned into the comment (the post already does that)

---

## OUTPUT FORMAT

When triggered, always output:

1. **The admission** (one sentence, what the post is quietly confessing)
2. **The image prompt** (full paragraph in the standard format)
3. **The 4-line comment** (with 📌)
4. **A one-line note** recording the five textual checks and whether an actual image was inspected

Optionally provide 2-3 variations if the first attempt is borderline.
