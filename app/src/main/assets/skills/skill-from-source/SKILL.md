---
name: skill-from-source
description: Turns a GitHub repo, a document, a web page or pasted notes into a Zafiro skill and installs it.
license: MIT
---

# Skill from source
Goal: a skill is a short, specific instruction file the agent loads only when needed.
1. Get the source: for a GitHub repo use `http_request` on raw.githubusercontent.com (README, docs, any SKILL.md); for a page use `py_web_read`; for an uploaded document use its text.
2. Check the license (MIT/Apache/CC-BY ok; if unclear or restrictive, write the skill from the ideas in your own words and say so).
3. Distil: when to use it, required inputs, numbered steps, decision rules, what never to do. 80-250 lines at most. Keep examples short.
4. Install with the `create_skill` tool (id in lowercase-with-dashes, description <= 200 chars that says WHEN to use it, body). If the repo already contains skills (folders with SKILL.md) install each with their text, adding an `adapted_from` line.
5. Tell the owner the skill id and how to switch it on (Skills page). Do not enable skills that you did not test.
