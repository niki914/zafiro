#!/usr/bin/env python3
"""Generates the bundled workspaces, their playbook skills and screen routines into app/src/main/assets.
Run from the repo root: python3 tools/autonomous/gen_workspaces.py   (output is committed; CI tests parse every file)."""
import json, os, textwrap

A = "app/src/main/assets"
os.makedirs(f"{A}/workspaces", exist_ok=True); os.makedirs(f"{A}/routines", exist_ok=True)
DISABLED = []  # skill ids that start OFF (a workspace turns them on)

def w(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, "w").write(text)

def skill(sid, desc, body):
    DISABLED.append(sid)
    w(f"{A}/skills/{sid}/SKILL.md", f"---\nname: {sid}\ndescription: {desc}\nlicense: MIT\n---\n\n{body.strip()}\n")

# ------------------------------------------------------------------ shared skills
skill("team-protocol", "How the agent team works together: board, handoffs, status lines, evidence. Load first in every team run.", """
# Team protocol
You belong to a workspace team. Work is passed on the shared board, never by memory or guesswork.
1. Start: `board op=resume` (re-opens work a dead run left half done), then `board op=next owner=<your name>`; if nothing is waiting, check `board op=list open_only=true`.
2. Items created by `owner` are replies or instructions from the owner: do them FIRST, exactly as written, and record the result. Hand work on with `board op=add` (title, detail, owner = the next agent, workspace, depth). Give the full context in `detail`: the next agent has not seen your conversation.
3. Finish every item you touch: `board op=update id=<id> status=DONE|FAILED|BLOCKED result=<what happened and where the output is>`. A result is required. Never leave an item DOING.
4. Save long outputs (plans, drafts, reports) with `save_file` (path like `<workspace>/drafts/2026-11-16-note.md`) and put the path in the result.
5. Delegation depth: the director is depth 0. Pass `depth=N` to the `agent` tool when your brief says delegation depth N. Chains stop at 3.
6. Success means evidence. DONE needs a result that names what was produced or done (a file path, an action id, counts). If nothing needed doing, say SKIPPED and why. Never claim an action happened that you did not see on screen or in a tool result.
7. Last line of your reply is exactly one of: `STATUS: DONE`, `STATUS: NEEDS_HUMAN - <what exactly>`, `STATUS: FAILED`.
8. Never bypass a lock screen, CAPTCHA, login, 2FA, consent screen or payment: `take_over` or NEEDS_HUMAN.
""")

skill("toolbox", "Which tool to use for what: web, files, dates, images, scheduling, screen. Load when unsure how to do something.", """
# Toolbox
- **Find information:** `web_search` (no key) then `web_fetch` on the best results; `rss_read` for a blog/Substack/subreddit feed (Substack: https://NAME.substack.com/feed). If a site blocks you, use an MCP search server if one is on, or the screen.
- **Read any public page cleanly:** `web_fetch` on `https://r.jina.ai/<full url>` returns readable text for many pages that plain fetch cannot parse (articles, profiles, videos with transcripts). Public pages only.
- **Scrapers without code:** if the Apify MCP is on, search its actors for the platform (YouTube, TikTok, Instagram, Reddit, LinkedIn) and run one; confirm the actor exists before relying on it.
- **Dates:** never guess. `datetime` (now, add, diff, next_weekday) in the owner's zone.
- **Files the owner can open:** `save_file` (save, append, read, delete, list). Always show the returned link. Plans and reports: Markdown or HTML.
- **Images:** `make_card` makes a quote/announcement card PNG (square 1080x1080, portrait 1080x1350, pin 1000x1500). Photos and video need an MCP such as Higgsfield or fal.ai if the owner switched one on.
- **Scheduling:** `schedule` creates follow-ups and recurring jobs (min 15 minutes) with a self-contained prompt; `board` is for handoffs between agents, `schedule` is for later.
- **Phone:** `screen_routine` first, `screen_operation` for one-offs, `open`/`open_uri`/`launch_app` to start apps, `notify` to tell the owner, `take_over` when a human is needed.
- **Data and code:** `http_request` for APIs, `execute_python` for calculations and file processing, `memory` for lasting lessons.
- **Skills:** `load_skill` only when needed; `create_skill` to save a repeatable method.
Prefer the cheapest tool that gives evidence. Report what you actually saw.
""")

skill("engagement-loop", "The daily engagement loop that builds an audience: find conversations, judge, comment, like, reply, within caps. Use for any engagement task.", """
# Engagement loop
Posting alone does not build an audience; conversation does. Each engagement run does ALL of these, in order, within today's caps:
1. **Own threads first.** Open notifications/activity, collect replies and comments on the owner's own content. Reply to every real one (thank-you-only replies are not enough: add a thought or a question). Own-thread replies and likes need no approval; neither do normal comments and posts.
2. **Find 15-25 relevant posts** (niche keywords from the workspace playbook, people the owner interacted with, accounts in `memory`). Use the platform's collect/scan routine. Record each candidate on the board (title = author + first words).
3. **Judge each**: is there something specific and true the owner can add? If not, skip it. Prefer posts less than a day old with room to be seen.
4. **Draft the comment** (use `reply-and-comment-writer` if available): 1-4 sentences, specific to the post, no pitch, no link, no flattery-only. Hand it to the Reviewer via the board. The Reviewer scores it against `examples list` and the rubric.
5. **Gate**: `engage op=plan` for every outward action (pass `serious=true` only for something irreversible, expensive or legally sensitive). The owner switched this workspace on, so you work alone: AUTO = act now. DENIED = daily cap reached, stop that action type for today. NEEDS_APPROVAL happens only for serious actions (direct messages, sending email, payments, deletions, job applications): leave those for the owner, who sees them as a pop-up and on the Dashboard, and carry on with other work.
6. **Act like a person**: one action at a time, then `engage op=pace` and wait that many seconds (use the `wait` tool). Then `engage op=done id=<id> note=<evidence>`.
7. **Like / restack** a few genuinely good posts (within caps). Never like or follow in bursts.
8. End with counts: replied N, commented N, liked N, drafts waiting N, skipped N and why.
""")

skill("reviewer-rubric", "Scores drafts for quality, truth and fit before anything is posted. Used by the Reviewer agent.", """
# Reviewer rubric
Run `examples op=list platform=<platform>` first. Compare the draft with GOOD and BAD examples and the owner's reasons.
Score 1-5 on each: **Specific** (a concrete detail, not a generality) · **Useful or interesting** to the target reader · **Voice** (sounds like the owner, no AI-isms, no hype) · **True** (every claim verifiable; nothing invented) · **Not an ad** (no hidden pitch, no link dump, no engagement bait) · **Fit** (the platform's norms and length).
Verdict: PASS (all >= 4), REVISE (any 3; say exactly what to change), REJECT (any <= 2, or a false/invented claim, or an ad). Write the verdict, the scores and the fix into the board item result. Never approve your own work. Never soften a REJECT.
""")

skill("owner-corrections", "How to apply a correction from the owner everywhere in one step so the same mistake does not repeat.", """
# Owner corrections
When the owner says something was wrong, bad, off-brand or "do X instead", do all of these in the same turn:
1. `examples op=correct platform=<p> text=<the bad output> reason=<their words>`.
2. Add a one-line standing rule to the workspace rulebook file with `save_file` (path `<workspace>/RULES.md`, keep the old rules, add the new line at the top).
3. Save a lesson with the `memory` tool (specific and reusable).
4. If a skill or agent instruction caused it, say which one so the owner can approve a change.
Reply with what you changed in each place. Do not just apologise.
""")

skill("human-pacing", "Rules for acting on a platform like a person so accounts are not flagged.", """
# Human pacing
Daily caps are in `engage op=today`. Never exceed them. Between actions wait `engage op=pace` seconds (default 20-90 s); between sessions leave hours. Vary what you do (a like, then a comment, then a scroll). Do not act in bursts or at machine-regular times. Stop for the day on any warning, rate-limit message, verification prompt or odd screen: report NEEDS_HUMAN. Stay logged in and in the app; never use other tools to post.
""")

skill("screen-routines", "How to drive the phone screen reliably with screen_routine and verified taps instead of step-by-step tapping.", """
# Screen routines
Prefer `screen_routine` over many `screen_operation` calls: `routine=list` shows what exists; `routine=<id> params={...}` does a whole job in one call with every tap verified (the screen must change; one retry; then a clear failure).
After every call read `on_screen_now`: success is what is on screen, not `ok`. If a routine fails: do NOT repeat it. Read the screen once with `screen_operation read`, then (a) fix the wrong label in the routine file `files/routines/<id>.json` (labels are plain text lists; add the real label), or (b) finish the job with `steps=[...]`, or (c) report FAILED with the screen text. The screen lags: when something should have appeared, use `wait` ops rather than tapping again.
The phone must be awake and unlocked. If the screen is black or shows a lock screen, wake it once (`screen_operation key code=224`), and if it stays locked report NEEDS_HUMAN.
""")

skill("skill-from-source", "Turns a GitHub repo, a document, a web page or pasted notes into a Zafiro skill and installs it.", """
# Skill from source
Goal: a skill is a short, specific instruction file the agent loads only when needed.
1. Get the source: for a GitHub repo use `http_request` on raw.githubusercontent.com (README, docs, any SKILL.md); for a page use `py_web_read`; for an uploaded document use its text.
2. Check the license (MIT/Apache/CC-BY ok; if unclear or restrictive, write the skill from the ideas in your own words and say so).
3. Distil: when to use it, required inputs, numbered steps, decision rules, what never to do. 80-250 lines at most. Keep examples short.
4. Install with the `create_skill` tool (id in lowercase-with-dashes, description <= 200 chars that says WHEN to use it, body). If the repo already contains skills (folders with SKILL.md) install each with their text, adding an `adapted_from` line.
5. Tell the owner the skill id and how to switch it on (Skills page). Do not enable skills that you did not test.
""")

# ------------------------------------------------------------------ platform data
ROLES = ["director", "researcher", "strategist", "writer", "reviewer", "engager", "executor", "analyst"]
ROLE_TITLE = dict(director="Director", researcher="Researcher", strategist="Strategist", writer="Writer", reviewer="Reviewer", engager="Engager", executor="Executor", analyst="Analyst")
SUBSTACK_IDS = dict(director=("growth_director", "Growth Director"), researcher=("audience_researcher", "Audience Researcher"), strategist=("editorial_strategist", "Editorial Strategist"),
                    writer=("content_producer", "Content Producer"), reviewer=("quality_reviewer", "Quality Reviewer"), engager=("community_builder", "Community Builder"),
                    executor=("android_assistant", "Android Execution Assistant"), analyst=("analytics_analyst", "Analytics Analyst"))

TOOLS = dict(
    director="agent,board,engage,examples,save_file,memory,load_skill,notify,device_info,wait,execute_python,http_request,datetime,schedule,web_search",
    researcher="web_search,web_fetch,rss_read,datetime,http_request,load_skill,memory,board,save_file,execute_python",
    strategist="load_skill,memory,board,save_file,examples,datetime,web_search,web_fetch,rss_read",
    writer="load_skill,board,save_file,examples,memory,make_card,datetime",
    reviewer="examples,engage,board,load_skill,memory",
    engager="screen_routine,load_skill,engage,board,agent,memory,wait,web_search,web_fetch",
    executor="screen_routine,screen_operation,screenshot,open,screens,engage,board,wait,take_over,device_info,clipboard,load_skill,open_uri,launch_app",
    analyst="screen_routine,save_file,memory,board,load_skill,execute_python,notify,engage,web_fetch,datetime",
)

P = [
 dict(id="substack", name="Substack", app="com.substack.app", kind="Notes and posts", can_post=True, off=0, enabled_default=True,
      quotas="note=6,post=1,comment=15,reply=40,like=30,restack=10,follow=10,dm=5",
      skills=["social-strategy", "reply-and-comment-writer", "engagement-routine", "email-and-newsletter", "text-post-and-microblog", "content-pillars", "content-calendar", "audience-research", "competitor-analysis", "writing-style-and-tone", "voice-builder", "stop-slop"],
      facts="Substack has two surfaces: Notes (short posts in a feed, replies, restacks, likes) and long posts (newsletter, comments). Audience growth on Substack comes mostly from Notes and from thoughtful comments on other writers' posts and notes, plus recommendations from other publications. Notes that are only links tend to travel less. A restack shares someone's note or post with your followers (add your own thought when you can). The Activity tab shows replies, likes, new subscribers and restacks. The owner's publication: tricreta.substack.com (Android developer; websites, AI features, dashboards, design/video). Content pillars come from the owner's content plan; WhatsApp/M-Pesa/bot topics are at most 1 in 6 pieces.",
      look="Substack",
      labels=dict(home=["Home"], notes=["Notes"], compose=["New note", "Write a note", "Create", "What's on your mind", "Start a note"], field=["What's on your mind", "Say something", "Write a note", "Start writing"], post=["Post", "Publish"], comment=["Comment", "Reply"], send=["Post", "Send", "Reply"], like=["Like"], restack=["Restack"], activity=["Activity", "Notifications", "Inbox"], profile=["Profile"])),
 dict(id="x", name="X (Twitter)", app="com.twitter.android", kind="posts and replies", can_post=True, off=7, enabled_default=False,
      quotas="post=4,comment=15,reply=30,like=30,follow=8,dm=3",
      skills=["x-growth", "thread-writer", "reply-and-comment-writer", "engagement-routine", "hook-writer", "trend-jacking", "social-strategy", "writing-style-and-tone"],
      facts="WARNING: the owner's @tricreta account was permanently suspended (read-only banner seen 2026-11-13). Do not appeal or create accounts unasked. Keep this workspace off until the owner says the account is restored. When active: short posts, threads, replies to larger accounts in the niche are the main growth lever.",
      look="X",
      labels=dict(home=["Home"], notes=["For you", "Following"], compose=["Post", "Compose", "What is happening", "What's happening"], field=["What is happening", "What's happening", "Post text"], post=["Post"], comment=["Reply", "Post your reply"], send=["Reply", "Post"], like=["Like"], restack=["Repost"], activity=["Notifications"], profile=["Profile"])),
 dict(id="linkedin", name="LinkedIn", app="com.linkedin.android", kind="posts, comments and connection messages", can_post=True, off=13, enabled_default=True,
      quotas="post=1,comment=12,reply=25,like=25,follow=5,dm=5",
      skills=["linkedin-growth", "linkedin-post-writer", "linkedin-company-pages", "profile-optimization", "reply-and-comment-writer", "social-selling-and-dm", "engagement-routine", "social-strategy"],
      facts="LinkedIn rewards useful comments on other people's posts and consistent posting with a clear point of view. LinkedIn's terms prohibit third-party automation tools; this workspace only drives the official app on the owner's phone at human pace with low caps. Connection requests and DMs are always drafts for the owner to approve. No fake claims about experience.",
      look="LinkedIn",
      labels=dict(home=["Home"], notes=["Home"], compose=["Start a post", "Post", "Create a post"], field=["What do you want to talk about", "Share your thoughts", "Start a post"], post=["Post"], comment=["Comment"], send=["Post", "Send"], like=["Like"], restack=["Repost"], activity=["Notifications"], profile=["Me", "Profile"])),
 dict(id="instagram", name="Instagram", app="com.instagram.android", kind="reels, carousels, stories and comments", can_post=False, off=19, enabled_default=True,
      quotas="post=1,comment=15,reply=30,like=30,follow=8,dm=4",
      skills=["instagram-growth", "instagram-reels-publishing", "instagram-seo", "reels-script", "carousel-writer", "caption-writer", "hashtag-strategy", "reply-and-comment-writer", "canva", "capcut", "engagement-routine"],
      facts="Instagram growth comes from Reels (reach), carousels (saves and shares), consistent Stories, and real comments on accounts in the niche. Posting media through screen taps is fragile: the team prepares the media, caption and hashtags as files, and the owner (or a verified routine, once proven) publishes. Comments and replies run through the engagement loop.",
      look="Instagram",
      labels=dict(home=["Home"], notes=["Home"], compose=["Create", "New post", "+"], field=["Write a caption", "Add a caption"], post=["Share", "Post"], comment=["Comment", "Add a comment"], send=["Post", "Send"], like=["Like"], restack=["Share"], activity=["Activity", "Notifications"], profile=["Profile"])),
 dict(id="facebook", name="Facebook", app="com.facebook.katana", kind="page posts, group posts and comments", can_post=True, off=25, enabled_default=True,
      quotas="post=2,comment=10,reply=25,like=25,dm=3",
      skills=["facebook-strategy", "facebook-groups", "caption-writer", "reply-and-comment-writer", "community-management", "engagement-routine", "social-strategy"],
      facts="Facebook rewards native posts, video, and active groups. Group posting must follow each group's rules and admin approval. Never spam groups; one genuinely helpful comment beats a drop of links. Page management may be in the separate Pages Manager app (com.facebook.pages.app).",
      look="Facebook",
      labels=dict(home=["Home"], notes=["Home"], compose=["What's on your mind", "Create post", "Write something"], field=["What's on your mind", "Write something"], post=["POST", "Post"], comment=["Comment", "Write a comment"], send=["Send", "Post"], like=["Like"], restack=["Share"], activity=["Notifications"], profile=["Menu", "Profile"])),
 dict(id="tiktok", name="TikTok", app="com.zhiliaoapp.musically", kind="videos and comments", can_post=False, off=31, enabled_default=True,
      quotas="post=1,comment=15,reply=25,like=30,follow=8",
      skills=["tiktok-growth", "tiktok-script", "tiktok-video-publishing", "tiktok-photo-mode", "short-form-video-script", "hook-writer", "capcut", "hashtag-strategy", "trend-jacking", "engagement-routine"],
      facts="TikTok growth depends on the first 2 seconds, watch-through rate and trend/sound fit. The team writes scripts, shot lists, captions and hashtags and queues them as files; video creation uses the owner's tools (CapCut, generators) and the final upload is the owner's tap until a publish routine is verified. Some regions use the package com.ss.android.ugc.trill.",
      look="TikTok",
      labels=dict(home=["Home"], notes=["For You", "Following"], compose=["Create", "+"], field=["Describe your post", "Add description"], post=["Post"], comment=["Add comment", "Comment"], send=["Post", "Send"], like=["Like"], restack=["Share"], activity=["Inbox", "Activity"], profile=["Profile"])),
 dict(id="youtube", name="YouTube", app="com.google.android.youtube", kind="shorts, videos and comment replies", can_post=False, off=37, enabled_default=True,
      quotas="post=1,comment=10,reply=30,like=20",
      skills=["youtube-long-form", "youtube-shorts", "youtube-publishing-and-metadata", "thumbnail-design", "scripting-and-storyboarding", "reply-and-comment-writer", "community-management", "seo-for-youtube" if False else "social-seo"],
      facts="YouTube growth depends on titles, thumbnails and retention. The team plans topics, writes scripts, titles, descriptions, chapters and thumbnail briefs, and replies to comments on the owner's videos. Uploading uses YouTube Studio (com.google.android.apps.youtube.creator); the final publish stays with the owner until a verified routine exists.",
      look="YouTube",
      labels=dict(home=["Home"], notes=["Shorts", "Subscriptions"], compose=["Create", "+"], field=["Add a comment", "Title"], post=["Upload", "Post"], comment=["Comments", "Add a comment"], send=["Send", "Reply"], like=["like this video", "Like"], restack=["Share"], activity=["Notifications"], profile=["You"])),
 dict(id="reddit", name="Reddit", app="com.reddit.frontpage", kind="comments and posts in relevant subreddits", can_post=True, off=43, enabled_default=True,
      quotas="post=1,comment=8,reply=15,like=20",
      skills=["reddit-marketing", "reply-and-comment-writer", "community-management", "engagement-routine", "audience-research"],
      facts="Reddit punishes self-promotion. Contribute first (answers, insight), follow each subreddit's rules (read them before posting), keep promotion under 10%, never post the same text in several subreddits, and expect new or low-karma accounts to be filtered. Reddit has no web fallback from the phone (reddit.com JSON/RSS return 403), so rounds drive the app.",
      look="Reddit",
      labels=dict(home=["Home"], notes=["Home", "Popular"], compose=["Create", "Create post", "+"], field=["Title", "Body text", "Add a comment"], post=["Post"], comment=["Add a comment", "Comment"], send=["Reply", "Comment", "Post"], like=["Upvote"], restack=["Share"], activity=["Inbox"], profile=["Profile"])),
 dict(id="threads", name="Threads", app="com.instagram.barcelona", kind="short posts and replies", can_post=True, off=49, enabled_default=True,
      quotas="post=4,comment=15,reply=30,like=30,follow=8",
      skills=["threads-growth", "threads-post", "text-post-and-microblog", "reply-and-comment-writer", "engagement-routine", "hook-writer"],
      facts="Threads rewards conversational short posts and replies to active accounts. Treat it like a stream of small conversations: ask a real question, add a real detail, reply to every reply.",
      look="Threads",
      labels=dict(home=["Home"], notes=["For you", "Following"], compose=["New thread", "Start a thread", "What's new", "Create"], field=["What's new", "Start a thread"], post=["Post"], comment=["Reply"], send=["Post", "Reply"], like=["Like"], restack=["Repost"], activity=["Activity"], profile=["Profile"])),
 dict(id="pinterest", name="Pinterest", app="com.pinterest", kind="pins and boards", can_post=False, off=55, enabled_default=True,
      quotas="post=5,like=20,follow=10",
      skills=["pinterest-growth", "pinterest-pin-design", "pinterest-seo", "canva", "caption-writer", "social-seo"],
      facts="Pinterest is a search engine: pin titles, descriptions and board names carry keywords; fresh pins to the same URL beat repeats. The team prepares pin images (files), titles, descriptions and target boards; publishing is the owner's tap until a routine is verified.",
      look="Pinterest",
      labels=dict(home=["Home"], notes=["Home"], compose=["Create", "Pin", "+"], field=["Add a title", "Tell everyone what your Pin is about"], post=["Publish", "Save"], comment=["Add a comment"], send=["Post", "Send"], like=["React", "Like"], restack=["Save"], activity=["Notifications", "Updates"], profile=["Saved", "Profile"])),
 dict(id="email", name="Email (Gmail)", app="com.google.android.gm", kind="inbox triage, drafts and follow-ups", can_post=False, off=61, enabled_default=True,
      quotas="reply=30,dm=10",
      skills=["mkt-cold-email", "mkt-emails", "email-and-newsletter", "mkt-email-sequence" if False else "marketing-email-sequence", "social-selling-and-dm", "customer-support-draft-response", "inbox-triage" if False else "productivity-task-management"],
      facts="Email work: triage the inbox, draft replies, follow up on leads and applications, and read notifications. The owner's rule: drafts are typed but NOT sent unless a message type is pre-approved; cold outreach is never bulk. Gmail search on this phone: set_text works in the search field but IME Enter does not submit, tap the keyboard search key. When the phone is locked, `dumpsys notification` via rish can read Gmail notification text.",
      look="Gmail",
      labels=dict(home=["Inbox"], notes=["Inbox", "Primary"], compose=["Compose"], field=["Compose email", "Subject", "To"], post=["Send"], comment=["Reply"], send=["Send"], like=["Star"], restack=["Forward"], activity=["Inbox"], profile=["Search in mail"])),
 dict(id="whatsapp", name="WhatsApp", app="com.whatsapp.w4b", kind="the daily digest and message drafts", can_post=False, off=67, enabled_default=True,
      quotas="dm=10,reply=20",
      skills=["social-selling-and-dm", "customer-support-draft-response", "community-management"],
      facts="WhatsApp Business (com.whatsapp.w4b) is the owner's channel. Daily digest recipe that works when the phone is unlocked: write the report file, open_uri whatsapp://send?phone=<number>&text=<urlencoded>, wait about 10 s, find and tap Send via the accessibility tree, verify the Sent tick. Reports must be well formatted: bold title (*...*), numbered bold sections (What ran / failed and why / done with counts / fixed / plan / needs from you), '- ' bullets, blank lines, newlines as %0A. Lead messages are typed as drafts and NOT sent: the owner reviews and taps send. Never message strangers in bulk.",
      look="WhatsApp",
      labels=dict(home=["Chats"], notes=["Chats"], compose=["New chat", "Message"], field=["Message", "Type a message"], post=["Send"], comment=["Reply"], send=["Send"], like=["React"], restack=["Forward"], activity=["Chats"], profile=["Settings"])),
 dict(id="jobhunt", name="Job hunt", app="com.google.android.gm", kind="applications, outreach drafts and the tracker", can_post=False, off=73, enabled_default=True,
      quotas="post=15,dm=10,reply=20",
      skills=["mkt-cold-email", "social-selling-and-dm", "linkedin-growth", "profile-optimization", "deep-research", "productivity-task-management"],
      facts="Mission: remote and contract work for an Android developer who also does full-stack web and AI automation. Target 15 applications a day Monday to Friday (5 full-stack, 5 automation, 5 mobile) plus outreach drafts. NEVER fabricate experience, clients, metrics or credentials; use only the owner's approved resumes (Drive: Career Search > Resumes). Label project status honestly. Log every application in the tracker (applications/tracker.csv). Applications and outreach go out only as the owner has pre-authorised; otherwise they are drafts.",
      look="Gmail",
      labels=dict(home=["Inbox"], notes=["Inbox"], compose=["Compose"], field=["Compose email", "Subject"], post=["Send"], comment=["Reply"], send=["Send"], like=["Star"], restack=["Forward"], activity=["Inbox"], profile=["Search in mail"])),
]

# ------------------------------------------------------------------ roles
def instr(p, role, ids):
    n, app, kind = p["name"], p["app"], p["kind"]
    ws = p["id"]
    common = (f"You are the {ROLE_TITLE[role]} of the {n} team (workspace \"{ws}\"). Working area: {kind}. FIRST load skill team-protocol and the skill {ws}-playbook; follow them. "
              f"Read the workspace rules in `{ws}/RULES.md` (use `save_file op=list dir={ws}` and execute_python to read it) if it exists: it overrides older notes. "
              f"Success is evidence, not effort. Keep replies short; save long output with save_file.")
    d = dict(
        director=f"MISSION: run the whole {n} operation and keep it moving until the owner stops it. Each run: `board op=resume`; read the board and today's counts (`engage op=today`); decide the next most valuable actions toward audience growth and results; create board items with clear owners; call the specialists with the `agent` tool (depth 0 for you) in this order when work exists: researcher > strategist > writer > reviewer > engager/executor > analyst. Wait for each answer, read its result, and hand the next step on. Run background calls in parallel only for non-screen work. Never do the specialists' work yourself. Stop only when the board has no open items or a daily cap is reached; then write the day's summary and notify the owner if something needs them. Weekly: write the plan as a Markdown file with save_file and send the link.",
        researcher=f"MISSION: find what the {n} audience cares about now: trending topics, questions people ask, accounts and posts worth engaging with, competitors' winning formats. Use py_web_read/http_request and, on screen, the scan routine. Output: a research brief file (save_file) plus board items for the strategist and engager with concrete targets (author, post, why it matters). Cite sources; never invent numbers.",
        strategist=f"MISSION: turn research and results into a plan: content pillars, a week calendar with formats and times, experiments (one change at a time), and engagement targets. Output a plan file (save_file, Markdown or HTML) and board items for the writer. Use `examples op=list` and the analyst's last numbers. The owner reads the plan before big changes.",
        writer=f"MISSION: write {kind} in the owner's voice, from board items. One idea per piece, concrete detail, no hype, no AI-isms, no invented facts. Save each draft with save_file and hand it to the reviewer through the board. Revise exactly what the reviewer asked, at most twice.",
        reviewer=f"MISSION: quality gate. Score every draft with skill reviewer-rubric against the owner's examples. Output PASS/REVISE/REJECT with scores and the exact fix in the board item. Also check platform rules and that nothing is an ad or a false claim. You never post and never rewrite from scratch.",
        engager=f"MISSION: run the engagement loop on {n} (skill engagement-loop): reply to the owner's own threads, find relevant conversations, draft comments, get them reviewed, and have the executor act within caps. Do not tap the screen yourself except for scan/collect routines; delegate actions to the executor one job per call.",
        executor=f"MISSION: operate the {n} app ({app}) on the phone, exactly as asked, one job per call. Load skill screen-routines and human-pacing first. Use screen_routine routines ({ws}.*) rather than step-by-step taps. Before any outward action, make sure it has an `engage op=plan` id with gate AUTO or an owner approval. After acting, `engage op=done` with evidence taken from on_screen_now. If the screen is locked, black, in a foreign app, or a verification appears: report NEEDS_HUMAN. Never loop on a failing step.",
        analyst=f"MISSION: measure and learn. Read {n} stats and results (stats routine or notifications), count actions from the action log (`engage op=today`), save a daily numbers file, compare with the plan, and write 1-3 specific lessons with the memory tool. Propose one experiment. Send the daily digest summary to the director. Never make up metrics: write 'not available' when the screen did not show them.",
    )[role]
    persist = (" PERSISTENCE: do not stop after one job. When an item is done, immediately take the next one (`board op=next`). When the board is empty, create the next three highest-value items toward today's targets (the daily caps in the playbook are your targets: work toward filling them with GOOD work, never with filler), then do them. "
               "You are judged on outcomes per day (posts that landed, real conversations, results measured), not on the number of steps. If you are blocked, switch to another useful job instead of waiting, and report the block once. Keep a one-line progress note on the board item every few steps so the owner can watch you.")
    return common + persist + "\n" + d

def tasks_for(p, ids):
    ws, n, off = p["id"], p["name"], p["off"]
    def t(h, m):
        m = (m + off) % 60; h = (h + (m < off % 60 and 0)) % 24
        return f"{h:02d}:{m:02d}"
    head = f"Workspace \"{ws}\". Load skill team-protocol and {ws}-playbook. "
    T = []
    T.append(dict(key="heartbeat", name=f"{n}: team heartbeat", agent=ids["director"][0], trigger="INTERVAL", intervalMinutes=30, maxSteps=100, maxMinutes=45,
                  prompt=head + "Heartbeat: first run `board op=resume`, then `board op=list open_only=true` and do every item created by owner before anything else (owner replies such as approvals or instructions). Then work through the open board items by calling the right specialists until the board is empty or today's caps are reached. If the board is empty, create the next valuable items (engagement targets, a draft that is due, an overdue follow-up) and run them. Do not start a screen job if one is already running; the screen is shared.",
                  criteria="Open board items were advanced or the board was empty, and the result lists what was done with evidence or says SKIPPED with a reason."))
    T.append(dict(key="prebrief", name=f"{n}: morning plan", agent=ids["director"][0], trigger="DAILY", time=t(5, 30), maxSteps=60, maxMinutes=30,
                  prompt=head + "Morning plan: read yesterday's numbers, lessons and `examples op=list`; check `engage op=today` and the board; write today's plan file (save_file path `" + ws + "/plans/<date>.md`) with 3 priorities; create board items for the researcher, writer and engager; call them.",
                  criteria="A plan file exists for today and board items were created."))
    T.append(dict(key="research", name=f"{n}: research", agent=ids["researcher"][0], trigger="DAILY", time=t(7, 15), maxSteps=60, maxMinutes=30,
                  prompt=head + "Research: find today's best topics, questions and conversation targets. Save a brief file and create board items for the strategist and engager.",
                  criteria="A research brief file exists and board items were created for the next agents."))
    if p["can_post"] or ws in ("instagram", "tiktok", "youtube", "pinterest"):
        T.append(dict(key="produce", name=f"{n}: production", agent=ids["director"][0], trigger="DAILY", time=t(9, 0), maxSteps=80, maxMinutes=40,
                      prompt=head + "Production cycle: take the plan's due pieces, have the writer draft each, the reviewer score it, and queue PASSed drafts for the owner or (if the engage gate is AUTO) for the executor to publish. Save drafts as files.",
                      criteria="At least one reviewed draft file exists, or the run says why nothing was due."))
    for hh, mm in ((10, 0), (14, 30), (19, 50), (21, 30)):
        T.append(dict(key=f"engage{hh}", name=f"{n}: engagement {hh:02d}:{mm:02d}", agent=ids["engager"][0], trigger="DAILY", time=t(hh, mm), maxSteps=100, maxMinutes=45,
                      prompt=head + "Run one full engagement loop (skill engagement-loop) within today's caps. Use the executor for every screen action, one job per call.",
                      criteria="Counts of replies, comments and likes are reported with evidence, or the result says SKIPPED with the reason (cap reached, phone locked, app blocked)."))
    T.append(dict(key="stats", name=f"{n}: daily stats and learning", agent=ids["analyst"][0], trigger="DAILY", time=t(20, 30), maxSteps=60, maxMinutes=30,
                  prompt=head + "Read today's results and the action log, save the numbers file, write lessons, propose one experiment, and pass the summary to the director.",
                  criteria="A numbers file exists for today and at least one lesson was saved (or 'nothing new' is justified)."))
    T.append(dict(key="digest", name=f"{n}: owner digest", agent=ids["director"][0], trigger="DAILY", time=t(21, 5), maxSteps=50, maxMinutes=25,
                  prompt=head + "Write today's digest (what was posted or done, who got replies, what needs the owner, what was learned) as a file, notify the owner, and if the whatsapp workspace is on, send it using its recipe. If nothing happened, say so honestly.",
                  criteria="A digest file exists and the owner was notified."))
    T.append(dict(key="overnight", name=f"{n}: overnight drafting", agent=ids["strategist"][0], trigger="DAILY", time=t(23, 30), maxSteps=60, maxMinutes=40,
                  prompt=head + "Overnight, no screen: prepare tomorrow's drafts and engagement targets from the plan and research, hand them on through the board.",
                  criteria="Drafts or targets were created, or the result says why not."))
    T.append(dict(key="weekly", name=f"{n}: weekly review and plan", agent=ids["director"][0], trigger="WEEKLY", days=["SUN"], time=t(18, 0), maxSteps=80, maxMinutes=45,
                  prompt=head + "Weekly review: what worked, what did not, numbers vs plan, lessons. Write next week's plan as a Markdown file with save_file (`" + ws + "/plans/week-<n>.md`) and show the link. Do not start implementing until the owner says so, except the normal daily tasks.",
                  criteria="A weekly plan file exists and the review names at least one change."))
    return T


PLATFORM_TOOLS = {
 "youtube": "Research: web_search + web_fetch on youtube.com/results?search_query=..., r.jina.ai for a video page, Apify actor 'streamers/youtube-scraper' if the Apify MCP is on. Transcripts of competitors' videos show hooks that work. Publishing: YouTube Studio app via screen routines; thumbnails with make_card.",
 "tiktok": "Research: Apify 'clockworks/tiktok-scraper' (if on) for trending sounds and top videos in the niche; web_fetch via r.jina.ai on tiktok.com/tag/<tag>. Higgsfield/fal.ai MCP for video clips if switched on; CapCut skill for editing steps.",
 "instagram": "Research: Apify 'apify/instagram-scraper' (if on) for top posts and hashtags; make_card for carousel slides; Canva MCP if switched on.",
 "linkedin": "Research: web_search 'site:linkedin.com/posts <topic>', r.jina.ai on public posts; screen routines for comments. Never use third-party automation tools.",
 "reddit": "Research: subreddit rules via web_fetch on https://r.jina.ai/https://www.reddit.com/r/<sub>/about/rules ; top posts via the app routines.",
 "substack": "Research: rss_read on https://<writer>.substack.com/feed for each writer in your niche; web_search for niche topics; Notes and comments via screen routines.",
 "x": "Research: web_search; X is suspended for the owner, keep this workspace off until restored.",
 "facebook": "Research: group rules via the app; Apify 'apify/facebook-posts-scraper' (if on) for page benchmarks.",
 "pinterest": "Research: pinterest search suggestions via the app; make_card for pin images in 1000x1500.",
 "email": "Use Gmail via screen routines; rss_read for newsletters; never bulk-send.",
 "whatsapp": "Use the digest recipe; drafts only for leads.",
 "jobhunt": "Research: web_search for remote roles, r.jina.ai for job pages; log every application in applications/tracker.csv with save_file append.",
}

PLAYBOOK_ENGAGE = {
 "substack": "Notes first: 3-6 Notes a day max. Comments on larger writers' posts in the niche are the best discovery lever: add one specific insight, no pitch. Reply to every comment on the owner's posts. Restack a few good notes with a line of your own.",
}

for p in P:
    ws = p["id"]
    ids = SUBSTACK_IDS if ws == "substack" else {r: (f"{ws}-{r}", f"{p['name'].split(' (')[0]} {ROLE_TITLE[r]}") for r in ROLES}
    agents = [dict(id=ids[r][0], name=ids[r][1], instructions=instr(p, r, ids), tools=TOOLS[r].split(","),
                   skills=["team-protocol", "toolbox", f"{ws}-playbook"] + {"engager": ["engagement-loop", "human-pacing"], "executor": ["screen-routines", "human-pacing"], "reviewer": ["reviewer-rubric"], "director": ["owner-corrections"]}.get(r, []),
                   usesScreen=r in ("executor",)) for r in ROLES]
    # engager also reads the screen for scan routines -> serialised with the executor via usesScreen
    for a in agents:
        if a["id"] == ids["engager"][0]: a["usesScreen"] = False
    skills = ["team-protocol", "toolbox", "engagement-loop", "reviewer-rubric", "owner-corrections", "human-pacing", "screen-routines", f"{ws}-playbook"] + p["skills"]
    d = dict(id=ws, version=1, name=p["name"], platform=ws, app=p["app"], summary=f"A ready-made {p['name']} team ({len(ROLES)} agents, {len(tasks_for(p, ids))} scheduled tasks): {p['kind']}, engagement and daily learning.",
             quotas=p["quotas"], agents=agents, tasks=tasks_for(p, ids), skills=sorted(set(skills)), mcps=[], memory=[p["facts"]])
    w(f"{A}/workspaces/{ws}.json", json.dumps(d, indent=1, ensure_ascii=False) + "\n")

    ENGAGE_TEXT = PLAYBOOK_ENGAGE.get(ws, "Reply to every real reply on the owner's own content first, then add one specific, useful comment on a relevant post, then like a few genuinely good posts. Quality over volume; stop for the day at the caps.")
    skill(f"{ws}-playbook", f"{p['name']} operating playbook: platform facts, formats, engagement tactics, caps and routines. Load when working on {p['name']}.", f"""
# {p['name']} playbook
App package: `{p['app']}`. Area: {p['kind']}.

## What to know
{p['facts']}

## Daily caps (owner-adjustable in the workspace; `engage op=today` shows live counts)
{p['quotas'].replace(',', ', ')}

## Engagement tactics
{ENGAGE_TEXT}

## Research and tools for this platform
{PLATFORM_TOOLS.get(ws, 'web_search and web_fetch.')}

## Screen routines (`screen_routine routine=list`)
`{ws}.open_home`, `{ws}.read_activity`, `{ws}.scan_feed`, `{ws}.comment`, `{ws}.like`""" + (f", `{ws}.post_text`" if p["can_post"] or ws == "substack" else "") + f""".
The labels in these routines are best-effort guesses for the app's English UI. On the first run each routine may fail with a clear reason: read the screen once, add the real label to `files/routines/{ws}.<name>.json`, and record the fix as a lesson. Do not loop.

## Never
Invent facts or results, post ads, spam, bypass locks/CAPTCHAs/verification, exceed caps, or act while the platform shows a warning.
""")

    # ---------------------------------------------------------------- routines
    L = p["labels"]
    def R(name, desc, params, steps):
        w(f"{A}/routines/{ws}.{name}.json", json.dumps(dict(id=f"{ws}.{name}", version=1, platform=ws, description=desc, params=params, steps=steps), indent=1) + "\n")
    openapp = dict(op="open_app", package=p["app"])
    home = dict(op="tap", labels=L["home"], optional=True, timeout_s=6)
    R("open_home", f"Open {p['name']} and go to the home screen.", [], [openapp, home])
    R("read_activity", f"Open {p['name']} notifications/activity and return what is shown (replies, likes, mentions).", [],
      [openapp, dict(op="tap", labels=L["activity"], optional=True), dict(op="sleep", ms=1500), dict(op="collect", count=3)])
    R("scan_feed", f"Scroll the {p['name']} feed and collect post texts to judge for engagement.", [],
      [openapp, home, dict(op="tap", labels=L["notes"], optional=True), dict(op="sleep", ms=1200), dict(op="collect", count=5)])
    R("comment", f"On the post currently open in {p['name']}, write a comment and send it. Only after an engage gate of AUTO or owner approval.", [dict(name="text", required=True)],
      [dict(op="tap", labels=L["comment"], timeout_s=6), dict(op="type", labels=L["field"], text="{text}"), dict(op="tap", labels=L["send"], timeout_s=8)])
    R("like", f"Like the post currently open in {p['name']}.", [], [dict(op="tap", labels=L["like"])])
    if p["can_post"] or ws == "substack":
        R("post_text", f"Create and publish a text post in {p['name']}. Only after an engage gate of AUTO or owner approval.", [dict(name="text", required=True)],
          [openapp, home, dict(op="tap", labels=L["compose"], timeout_s=6), dict(op="type", labels=L["field"], text="{text}"), dict(op="tap", labels=L["post"], timeout_s=8)])
    if ws == "substack":
        R("reply_in_thread", "Reply to the note or comment currently open in Substack.", [dict(name="text", required=True)],
          [dict(op="tap", labels=["Reply"], timeout_s=6), dict(op="type", labels=["Reply", "Write a reply", "Add a reply"], text="{text}"), dict(op="tap", labels=["Post", "Send", "Reply"], timeout_s=8)])
        R("restack", "Restack the note currently open in Substack.", [], [dict(op="tap", labels=["Restack"]), dict(op="tap", labels=["Restack", "Share"], optional=True)])
        R("open_profile", "Open a profile in Substack by name via search.", [dict(name="name", required=True)],
          [openapp, dict(op="tap", labels=["Search", "Explore"], timeout_s=6), dict(op="type", labels=["Search"], text="{name}"), dict(op="key", code=66), dict(op="sleep", ms=1500), dict(op="tap", labels=["{name}"], timeout_s=8)])
        R("read_stats", "Open Substack stats and return the visible numbers (subscribers, views).", [],
          [openapp, dict(op="tap", labels=["Dashboard", "Stats", "Profile"], optional=True), dict(op="tap", labels=["Stats", "Subscribers"], optional=True), dict(op="sleep", ms=2000), dict(op="collect", count=2)])

# A single-agent workspace: turns any repo, document or page into an installed skill.
w(f"{A}/workspaces/toolsmith.json", json.dumps(dict(
    id="toolsmith", version=1, name="Skill Builder", platform="toolsmith", app="", summary="One agent that turns a GitHub repo, a document, a web page or pasted notes into an installed Zafiro skill.", quotas="",
    agents=[dict(id="skill_builder", name="Skill Builder", tools=["create_skill", "http_request", "py_web_read", "execute_python", "load_skill", "save_file", "memory"], skills=["skill-from-source", "skill-creator"], usesScreen=False,
                 instructions="You are the Skill Builder. The owner gives you a GitHub repo, a document, a web page or notes. Load skill skill-from-source and follow it. Install each resulting skill with create_skill. For a repo that already contains skills, install each one (check the license first). Reply with the installed skill ids and one line on what each does. Never install a skill you could not read in full."),],
    tasks=[], skills=["skill-from-source", "skill-creator"], mcps=[], memory=[]), indent=1) + "\n")
DISABLED.append("skill-from-source")
# Phone health: read-only checks, notifies the owner at most once a day per problem.
skill("phone-health", "Checks that the phone can run the team: awake, unlocked, battery, Shizuku, network. Tells the owner once when something blocks work.", """
# Phone health
Run these checks with `device_info` and `execute_python` (rish helper in files/rish, RISH_APPLICATION_ID=com.niki914.zafiro.auto, stdin=DEVNULL):
1. Battery and charging (device_info). Below 25% and not charging: problem.
2. Screen state and lock: `dumpsys window | grep isKeyguardShowing` and wakefulness. Locked or asleep: try `input keyevent KEYCODE_WAKEUP` once. Still locked: problem (never try to bypass a lock).
3. Shizuku/rish alive: run `id` through rish. 'Server is not running': problem. You cannot restart Shizuku yourself without a computer or wireless-debugging pairing, so tell the owner exactly what to tap.
4. Network (device_info). Offline: problem.
5. Recent runs: count FAILED/MISSED/INTERRUPTED in the last 3 hours from the Dashboard data you can read with `board op=list`.
For each problem call `notify` ONCE per day per problem: remember it in files/outputs/health/alerts.json (date + problem) and skip if already sent today. If all is well, do nothing and say STATUS: DONE with 'all clear'.
""")
w(f"{A}/workspaces/health.json", json.dumps(dict(
    id="health", version=1, name="Phone health", platform="health", app="", summary="A watchdog that checks the phone every 30 minutes (awake, unlocked, battery, Shizuku, network) and tells you once if something blocks the team.", quotas="",
    agents=[dict(id="phone_doctor", name="Phone Doctor", tools=["device_info", "execute_python", "notify", "board", "save_file", "load_skill"], skills=["phone-health"], usesScreen=False,
                 instructions="You are the Phone Doctor. Load skill phone-health and run its checks. Be quiet when all is well. Never change settings, never bypass a lock, never uninstall anything.")],
    tasks=[dict(key="check", name="Phone health check", prompt="Run the phone-health checks now.", agent="phone_doctor", trigger="INTERVAL", intervalMinutes=30, maxSteps=25, maxMinutes=10, criteria="Checks ran; any problem was reported once to the owner.")],
    skills=["phone-health"], mcps=[], memory=[]), indent=1) + "\n")
dis = f"{A}/skills_disabled_by_default.txt"
cur = [l.strip() for l in open(dis).read().splitlines() if l.strip()]
open(dis, "w").write("\n".join(cur + [d for d in sorted(set(DISABLED)) if d not in cur]) + "\n")
missing = sorted({sk for p in P for sk in p["skills"] if not os.path.isdir(f"{A}/skills/{sk}")})
print("skill ids not bundled (ignored at enable time):", missing)
print("generated", len(P), "workspaces,", len(set(DISABLED)), "skills")
