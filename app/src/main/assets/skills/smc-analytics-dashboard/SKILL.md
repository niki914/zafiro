---
name: smc-analytics-dashboard
description: >
  Turn a LinkedIn Analytics export into an interactive dark-themed React dashboard plus a written strategic analysis with 5 data-backed content recommendations. Reads every sheet in the export, builds charts for engagement trend, follower growth, post performance scatter, day-of-week heatmap, and audience breakdown. Use this skill whenever the user says "analyse my linkedin", "linkedin analytics", "build my dashboard", "review my performance", or uploads a LinkedIn Analytics export file. Requires the user's LinkedIn Analytics export (xlsx) as input.
---

# Analytics Dashboard

## Codex and Claude runtime

- Use this skill in Codex or Claude with the tools actually available in the current task. `AskUserQuestion` examples describe the questions, not a required API: use an available question tool within its limits, or ask in chat. Reuse answers and source material already supplied.
- Work in the user-selected project. Read its `about-me.md`, `voice.md` and relevant brand files before personalised work. Confirm the intended author if files conflict or contain starter defaults. Ask for missing facts or run `voice-builder`; never inherit the maintainer's identity, accounts or private files.
- Resolve bundled `references/` relative to this skill folder. For an explicitly requested profile refresh, read and update the canonical `about-me.md`, `voice.md` or `newsletter-voice.md` in place, preserving unrelated user facts and rules. Consumers must reread those canonical files. Use a new filename only for new deliverables that would collide with unrelated existing files. Installation alone never starts an interview or writes files. Do not write persistent learnings unless requested.
- Use supplied evidence first. Verify external claims through available search/source tools when needed. If a source or integration is unavailable, name the missing capability and offer supplied text/export input. Never invent facts, first-person experience, metrics or a successful tool run.
- Connect only services needed for the chosen route through the user's existing account. Never print credentials or overwrite connections. Drafting, saving and reviewing do not authorise publishing, sending messages or changing accounts.

## CRITICAL: Auto-start on load

When this skill triggers, go straight to Step 1.

## Step 1. Get the export file

Ask:

> Upload your LinkedIn Analytics export file (xlsx).
>
> Not sure how to get it? Go to LinkedIn Analytics, set your date range (30, 60, or 90 days works well), and click Export in the top right.

Wait for the file upload.

## Step 2. Parse the data

Read every sheet in the file. Confirm author, reporting window, units and actual column names before calculating. The following sheets are examples, not a guaranteed export schema:

- **DISCOVERY**: overall impressions and reach
- **ENGAGEMENT**: daily impressions and engagements over time
- **TOP POSTS**: top 50 posts, ranked by engagements and by impressions (two tables to merge)
- **FOLLOWERS**: daily new followers plus total count
- **DEMOGRAPHICS**: job titles, locations, industries, seniority, company size, top companies

Top-post tables are selected samples, not the account’s entire posting history. Keep their denominators separate from account-wide metrics; do not infer best posting times from daily aggregates. Clean any messy headers. Merge the two TOP POSTS tables (by engagements and by impressions) into one unified dataset per post. De-duplicate.

## Step 3. Build the interactive dashboard

Use a supported React artifact surface or the selected project’s existing React and Recharts setup. If neither is available, provide the computed analysis and React source with **preview pending**; do not silently install dependencies or claim an interactive dashboard is running. Preview and exercise chart tooltips/resizing before calling it verified. Dark theme (background `#0f1117`), accent colours for charts. Use Recharts for all visualisations.

Include these panels in this order:

### Headline metrics (top row cards)
- Total impressions
- Total reach
- Total new followers
- Average daily impressions
- Average daily engagements
- Overall engagement rate (sum of engagements / sum of impressions, for the same reporting window). Zero or missing denominators are unavailable, not zero.
- Total posts tracked

### Engagement trend (line chart)
- Daily impressions (left y-axis) and engagements (right y-axis) over the full date range
- Highlight the top 3 spike days with markers

### Follower growth (area chart)
- Daily new followers
- 7-day moving average trendline overlaid
- Cumulative follower gain

### Post performance scatter
- X axis: impressions. Y axis: engagements
- Colour-code posts into four quadrants:
  - **Stars**: high reach + high engagement
  - **Viral but shallow**: high reach + low engagement
  - **Niche gold**: low reach + high engagement
  - **Underperformers**: low reach + low engagement
- Hoverable dots showing post URL and date

### Day-of-week heatmap
- Average impressions and engagements by day of week
- Highlight the strongest days

### Audience breakdown (bar charts)
- Job titles
- Industries
- Seniority
- Company size
- Top locations

### Formatting rules
- Format numbers: `67K` not `67000`, `1.2M` not `1200000`
- Total follower count prominent at the top
- Responsive layout (works on laptop and large display)
- Dark background, high contrast chart colours

## Step 4. Written strategic analysis

Below the dashboard, write a concise analysis with these sections:

### Performance Summary
- Trajectory: growing, plateauing, or declining (use trendlines)
- Current engagement rate; compare external benchmarks only with a verified dated source and matching metric definition

### Top Post Patterns
- Analyse top 10 by impressions and top 10 by engagements
- Patterns: posting day, time of month, content themes
- High impressions + low engagement: what does that signal?
- Low impressions + high engagement: what does that signal?

### Audience-Content Fit
- Who the core audience is, based on demographics
- Which content topics and formats would resonate
- Segments to lean into or away from

### Growth Velocity
- Average daily follower growth
- 30, 60, 90 day scenarios at current pace, labelled as extrapolations rather than forecasts
- Acceleration or deceleration trends

### Day and Timing Strategy
- Best days for impressions
- Best days for engagement
- Optimal posting schedule based on the data

### 5 Specific Content Recommendations
Each one includes:
- Content angle or topic
- Why the data supports it
- Which audience segment it targets
- Evidence and a testable hypothesis, without guaranteed impact

## Step 5. Offer the next move

After the analysis:

> Want me to draft one of these 5 recommendations as a full post? Call the post-writer or post-formatter skill with the recommendation number.

## Rules

- Use numbers, not adjectives. "Engagement rate is 2.3%" beats "engagement is healthy".
- Keep the analysis direct. No fluff, no filler.
- Never invent metrics not present in the export.
- Flag data quality issues (missing columns, odd date ranges) instead of silently working around them.
- Never use em dashes.
- British English unless voice.md specifies otherwise.
- Recommend running this monthly. Patterns only surface over time.
