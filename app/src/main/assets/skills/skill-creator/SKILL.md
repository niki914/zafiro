---
name: skill-creator
description: Create new skills, modify and improve existing skills, and measure skill performance. Use whenever the user wants to create a skill from scratch, turn a workflow or repeated task into a skill, edit or optimize an existing skill, test a skill with realistic prompts, or tune a skill's description so it triggers reliably — even if they don't say the word "skill".
---

# Skill Creator

A skill for creating new skills and iteratively improving them.

At a high level, the process of creating a skill goes like this:

- Decide what you want the skill to do and roughly how it should do it
- Write a draft of the skill
- Create a few test prompts and run them with the skill in play
- Help the user evaluate the results both qualitatively and quantitatively
- Rewrite the skill based on feedback from the user's evaluation
- Repeat until you're satisfied
- Expand the test set and try again at larger scale

Your job when using this skill is to figure out where the user is in this process and then jump in and help them progress through these stages. Maybe they're like "I want to make a skill for X" — help narrow down what they mean, write a draft, write the test cases, figure out how they want to evaluate, run the prompts, and repeat. Maybe they already have a draft — then go straight to the eval/iterate part of the loop. And if they say "I don't need to run a bunch of evaluations, just vibe with me", do that instead.

## Where skills live here

- Skills live under `/data/data/com.niki914.zafiro/files/skills/`. A skill is a directory `<skills-root>/<id>/` holding a `SKILL.md`; the id may be one or two levels deep (`<repo>/<skill>`).
- If that path ever differs (debug build, fork), don't guess: the skills root is the parent of the `<dir>` that the skills list shows for any skill, and `load_skill` returns a skill's absolute dir.
- Create and edit skills with python (or terminal): write the files directly under the skills root. There is no separate "skill tool" — this is a plain filesystem job.
- A skill is discovered through its frontmatter (`name`, `description`) plus the SKILL.md body. Optional `scripts/`, `references/`, and `assets/` sit next to it.
- Skills are read once at the start of each turn. A skill you write now becomes available from the next turn — tell the user when to expect it.
- Seed skills that ship with the app are protected: never overwrite one, pick a different id.

## Creating a skill

### Capture Intent

Start by understanding the user's intent. The current conversation might already contain a workflow the user wants to capture (e.g., they say "turn this into a skill"). If so, extract answers from the conversation history first — the tools used, the sequence of steps, corrections the user made, input/output formats observed. The user may need to fill the gaps, and should confirm before proceeding to the next step.

1. What should this skill enable the agent to do?
2. When should this skill trigger? (what user phrases/contexts)
3. What's the expected output format?
4. Should we set up test cases to verify the skill works? Skills with objectively verifiable outputs (file transforms, data extraction, code generation, fixed workflow steps) benefit from test cases. Skills with subjective outputs (writing style, art) often don't need them. Suggest the appropriate default based on the skill type, but let the user decide.

### Interview and Research

Proactively ask questions about edge cases, input/output formats, example files, success criteria, and dependencies. Wait to write test prompts until you've got this part ironed out.

Check available MCPs and built-in tools — if useful for research (searching docs, finding similar skills, looking up best practices), research with them. Come prepared with context to reduce burden on the user.

### Write the SKILL.md

Based on the user interview, fill in these components:

- **name**: Skill identifier
- **description**: When to trigger, what it does. This is the primary triggering mechanism — include both what the skill does AND specific contexts for when to use it. All "when to use" info goes here, not in the body. Note: models have a tendency to "undertrigger" skills — to not use them when they'd be useful. To combat this, make the descriptions a little bit "pushy". So for instance, instead of "How to build a simple fast dashboard to display internal company data.", you might write "How to build a simple fast dashboard to display internal company data. Make sure to use this skill whenever the user mentions dashboards, data visualization, internal metrics, or wants to display any kind of company data, even if they don't explicitly ask for a 'dashboard.'"
- **compatibility**: Required tools, dependencies (optional, rarely needed)
- **the rest of the skill :)**

### Skill Writing Guide

#### Anatomy of a Skill

```
skill-name/
├── SKILL.md (required)
│   ├── YAML frontmatter (name, description required)
│   └── Markdown instructions
└── Bundled Resources (optional)
    ├── scripts/    - Executable code for deterministic/repetitive tasks
    ├── references/ - Docs loaded into context as needed
    └── assets/     - Files used in output (templates, icons, fonts)
```

#### Progressive Disclosure

Skills use a three-level loading system:

1. **Metadata** (name + description) - Always in context (~100 words)
2. **SKILL.md body** - In context whenever the skill triggers (<500 lines ideal)
3. **Bundled resources** - As needed (unlimited, scripts can execute without loading)

These word counts are approximate; go longer if needed.

**Key patterns:**

- Keep SKILL.md under 500 lines; if you're approaching this limit, add another layer of hierarchy along with clear pointers about where to go next.
- Reference files clearly from SKILL.md with guidance on when to read them.
- For large reference files (>300 lines), include a table of contents.

**Domain organization**: when a skill supports multiple domains/frameworks, organize by variant:

```
cloud-deploy/
├── SKILL.md (workflow + selection)
└── references/
    ├── aws.md
    ├── gcp.md
    └── azure.md
```

The model reads only the relevant reference file.

#### Principle of Lack of Surprise

Skills must not contain malware, exploit code, or any content that could compromise system security. A skill's contents should not surprise the user in their intent if described. Don't go along with requests to create misleading skills or skills designed to facilitate unauthorized access, data exfiltration, or other malicious activities. Things like a "roleplay as an XYZ" are OK though.

#### Writing Patterns

Prefer the imperative form in instructions.

**Defining output formats** — do it like this:

```markdown
## Report structure
ALWAYS use this exact template:
# [Title]
## Executive summary
## Key findings
## Recommendations
```

**Examples pattern** — include examples like this (deviate a little when "Input"/"Output" don't fit):

```markdown
## Commit message format
**Example 1:**
Input: Added user authentication with JWT tokens
Output: feat(auth): implement JWT-based authentication
```

### Writing Style

Try to explain to the model why things are important in lieu of heavy-handed musty MUSTs. Use theory of mind and try to make the skill general, not super-narrow to specific examples. Start by writing a draft, then look at it with fresh eyes and improve it.

### Test Cases

After writing the skill draft, come up with 2-3 realistic test prompts — the kind of thing a real user would actually say. Share them with the user: "Here are a few test cases I'd like to try. Do these look right, or do you want to add more?" Then run them.

Save the prompts to `evals/evals.json` next to the skill:

```json
{
  "skill_name": "example-skill",
  "evals": [
    {
      "id": 1,
      "prompt": "User's task prompt",
      "expected_output": "Description of expected result",
      "files": []
    }
  ]
}
```

Don't write assertions yet — just the prompts. Draft assertions while the runs are in progress.

## Running and evaluating test cases

There are no subagents here: you run every test case yourself. Put results in `<skill-name>-workspace/` as a sibling to the skill directory, organized by iteration (`iteration-1/`, `iteration-2/`, ...), with one directory per test case inside.

A skill you just wrote isn't in your context yet, so testing it immediately means reading its files yourself and following them. To get the real "with-skill" experience, run the prompt after the skill becomes available in a fresh turn. Do at least one baseline run without the skill when it's cheap.

While the runs happen, draft quantitative assertions for each test case and explain them to the user. Good assertions are objectively verifiable and have descriptive names. Subjective skills (writing style, design quality) are better judged qualitatively — don't force assertions onto things that need human judgment.

When the runs are done:

1. **Grade each run** against its assertions. For anything checkable programmatically, write and run a python script instead of eyeballing — scripts are faster, more reliable, and reusable across iterations. Save a `grading.json` per run using the fields `text`, `passed`, `evidence`.
2. **Aggregate into a benchmark** with a short python script: pass rate, and time/tokens if captured, per configuration with mean and spread.
3. **Show the user** the qualitative outputs and the numbers in the conversation, and save them to the workspace as files. Skip browser viewers — a concise summary plus the output files is enough.

## Improving the skill

This is the heart of the loop. You ran the test cases, the user reviewed the results, and now you make the skill better.

1. **Generalize from the feedback.** You're building a skill that works across many different prompts, not just the few examples you iterated on. Rather than fiddly overfit changes or oppressively constrictive MUSTs, if there's a stubborn issue, try branching out with different metaphors or patterns.
2. **Keep the prompt lean.** Remove things that aren't pulling their weight. Read the transcripts, not just final outputs — if the skill makes the model waste time on unproductive work, cut the part causing it.
3. **Explain the why.** Try hard to explain the reasoning behind everything you ask the model to do. Modern models are smart; given good context they go beyond rote instructions. If you find yourself writing ALWAYS or NEVER in all caps, that's a yellow flag — reframe and explain the reasoning instead.
4. **Look for repeated work.** If every test run wrote the same helper script, that's a strong signal the skill should bundle it once under `scripts/`.

Then rerun the test cases into a new iteration directory, ask the user to review, and repeat until they're happy or you stop making meaningful progress.

## Description optimization

The description field is the primary mechanism that determines whether a skill is invoked. After creating or improving a skill, offer to optimize it:

1. Draft ~20 realistic trigger queries — a mix of should-trigger and should-not-trigger. Make the negatives near-misses (share keywords but need something else), not obviously irrelevant.
2. Review the set with the user.
3. Run each query against the skill several times and measure the trigger rate; use a python script to loop and report scores rather than doing it by hand.
4. Take the best-scoring description, update the frontmatter, and show the user before/after.
