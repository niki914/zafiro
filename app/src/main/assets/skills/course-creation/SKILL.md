---
name: course-creation
description: |
  Design, structure, and generate complete courses — from a single lesson to multi-module curricula. Use this skill whenever the user mentions: creating a course, curriculum design, lesson plans, module outlines, quizzes, learning objectives, course syllabi, workshop design, SkillUp content, training programs, teaching digital skills, or building educational content for any format (WhatsApp, LMS, PDF, video script). Triggers even if the user just says "I want to teach X" or "help me create content about Y for beginners." This is the go-to skill for ANY instructional design task, no matter how large or small.
---

# Course Creation Skill

Guides Claude through designing and producing complete, platform-ready course content — from initial concept to exportable materials.

---

## Phase 0 — Intake (always start here)

Before generating anything, capture the four variables that shape every decision:

1. **Subject** — What topic/skill is being taught?
2. **Audience** — Who is the learner? (age, background, existing knowledge, goal)
3. **Format** — Where does this course live? (WhatsApp channel, LMS, PDF, video script, live workshop, etc.)
4. **Scope** — Single lesson, a week of content, or a full multi-module course?

If any of these are ambiguous, ask. Do not proceed with assumptions on audience or format — wrong assumptions produce content that lands with zero people.

---

## Phase 1 — Course Blueprint

Produce a blueprint before writing a single word of content. The blueprint prevents scope creep and exposes structural problems early.

### Blueprint Structure

```
COURSE: [Title]
TARGET LEARNER: [one-sentence profile]
GOAL: [What will learner be able to DO after completing this?]
FORMAT: [WhatsApp / PDF / Video / LMS / etc.]
ESTIMATED LENGTH: [total lessons, total time]

MODULE 1: [Name]
  Lesson 1.1 — [Title] | Objective: [verb + outcome]
  Lesson 1.2 — [Title] | Objective: [verb + outcome]
  Assessment: [quiz / project / reflection]

MODULE 2: [Name]
  ...

PREREQUISITES: [what learner must already know]
DELIVERABLE: [what learner walks away with]
```

### Objective Quality Rules
- Use **Bloom's Taxonomy verbs** — not "understand" but: identify, apply, create, evaluate, compare, build, explain, demonstrate
- One objective per lesson. One skill per objective.
- If you can't write a testable objective, the lesson is too vague to teach

### Blueprint Anti-Patterns (flag these and refuse to proceed)
- Modules covering fundamentally different audiences in the same course
- First lesson that requires knowledge the course never teaches
- No clear end deliverable for the learner
- More than 6 lessons per module without a natural checkpoint

---

## Phase 2 — Lesson Generation

Generate individual lessons using this structure. Never skip sections.

### Lesson Template

```markdown
## Lesson [X.X] — [Title]

**Learning Objective:** By the end, the learner will be able to [Bloom's verb + measurable outcome].

**Hook (30-60 seconds):**
[Start with a question, problem, or real scenario. NOT "Today we'll learn about X." 
Lead with WHY this matters to THIS specific learner.]

**Core Content:**
[Chunked into 2-4 logical blocks. Each block: concept → example → implication.
No block longer than ~200 words for digital formats, ~400 for video scripts.]

**Key Points (3-5 bullets):**
- ...

**Worked Example:**
[One concrete, complete walkthrough. Show, don't tell.]

**Practice / Action Step:**
[One specific thing the learner does NOW — not "explore," not "think about." 
Something they can complete in 5 minutes.]

**Summary:**
[3 sentences max. Reinforce the objective.]
```

### Format-Specific Adjustments

| Format | Max lesson length | Tone | Key constraint |
|--------|------------------|------|----------------|
| WhatsApp channel | 300 words | Conversational, punchy | No headers. Short paragraphs. One point per message. |
| PDF / Doc | 600-1000 words | Clear, structured | Use headers. Include visuals placeholders. |
| Video script | 700-1200 words | Spoken, natural | Write for ear not eye. Pause cues. No bullet walls. |
| Live workshop | Facilitator notes | Interactive | Include discussion prompts. Time each block. |
| LMS (Canvas, Moodle) | 500-800 words | Semi-formal | Separate into pages. End each page with a check. |

---

## Phase 3 — Assessments

### Quiz Generation Rules
- Minimum 5 questions per module
- Question types: MCQ, True/False, Short Answer, Apply-a-concept scenario
- Each question must target an explicit lesson objective — no trivia
- Avoid "trick questions" unless the course is specifically testing judgment under ambiguity
- Always include an answer key with **explanations**, not just correct answers

### Quiz Template (MCQ)
```
Q[N]: [Stem — describes a situation or asks to apply knowledge]
A) [Plausible wrong — common misconception]
B) [Correct answer]
C) [Plausible wrong — partially correct but incomplete]
D) [Plausible wrong — off-topic but sounds relevant]

Correct: B
Why: [1-2 sentences explaining the reasoning, not just restating the answer]
```

### Project/Assignment Design
A good practical assignment must:
1. Have a clear deliverable (not "explore X" — "build Y that does Z")
2. Be completable in the stated time without external resources
3. Map directly to the module's stated objective
4. Include grading criteria or a self-assessment rubric

---

## Phase 4 — Supplementary Materials

Generate on request or when scope warrants it:

- **Glossary** — define every domain term used in the course, plain language first, then precise definition
- **FAQ** — anticipate top 5 questions per module; answer them as if you're the instructor in office hours, not a textbook
- **Learning Path** — if multiple skill levels exist, map which modules are optional vs. core, and where learners with background can skip in
- **Course Description (external-facing)** — for listing on a platform or WhatsApp bio; 50-100 words; lead with outcome, not topics
- **Content Audit** — if the user provides existing course material, evaluate against: clarity of objectives, audience match, cognitive load per lesson, assessment alignment, format suitability

---

## Phase 5 — Platform Export

### WhatsApp Channel Format
Break content into **daily message units**. Each unit:
- Opens with a hook (1-2 lines)
- Delivers ONE concept
- Ends with a mini-action or question
- Uses simple language (KCSE leaver-friendly by default unless told otherwise)
- Avoids jargon without immediate explanation
- Maximum 300 words per message

### Structured Document Format
When creating PDF/Doc exports, produce content ready for `docx` skill handoff:
- Proper heading hierarchy (H1 = Course, H2 = Module, H3 = Lesson)
- Include placeholder tags: `[IMAGE: description]`, `[TABLE: description]`
- Page break indicators between lessons

### Video Script Format
- Mark camera directions in `[brackets]`
- Write full sentences as spoken (contractions, natural rhythm)
- Add `[PAUSE]` markers at concept transitions
- Each script block = one edit point

---

## Workflow Decision Tree

```
User wants to create a course?
├── Has clear topic + audience? 
│   ├── YES → Phase 1 Blueprint → confirm → Phase 2-3
│   └── NO → Run Phase 0 intake first
│
├── Wants a single lesson?
│   └── Skip to Phase 2 directly
│
├── Has existing content to improve?
│   └── Run Content Audit (Phase 4), then suggest Phase 1 reblueprint
│
└── Wants quizzes only?
    └── Confirm which lessons/objectives, then Phase 3
```

---

## Quality Gates

Before handing off any output, verify:

- [ ] Every lesson has a testable, Bloom's-verb objective
- [ ] No lesson tries to teach more than one core concept
- [ ] Hook does NOT start with "Today we will learn"
- [ ] Every quiz question maps to a stated lesson objective
- [ ] Format constraints are respected (word count, tone, structure)
- [ ] Action step is specific and completable without external help
- [ ] Course has a clear, singular end outcome for the learner

---

## Reference Files

- `references/blooms-taxonomy.md` — full verb bank organized by cognitive level
- `references/content-audit-rubric.md` — structured rubric for auditing existing course content
- `references/platform-specs.md` — detailed format requirements for WhatsApp, Canvas, Udemy, PDF
- `templates/lesson-template.md` — blank lesson template ready to fill
- `templates/quiz-template.md` — blank quiz template with answer key structure
- `templates/course-blueprint.md` — blank blueprint for new courses
