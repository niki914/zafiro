# Nexus

> Plug your own model into your phone's voice assistant, and let it actually get things done for you

[![stars](https://img.shields.io/github/stars/niki914/agentic-nexus?label=stars)](https://github.com/niki914/agentic-nexus)
[![release](https://img.shields.io/github/v/release/Xposed-Modules-Repo/com.niki.breeno.openai?include_prereleases)](https://github.com/niki914/agentic-nexus)
[![downloads](https://img.shields.io/github/downloads/Xposed-Modules-Repo/com.niki.breeno.openai/total)](https://github.com/niki914/agentic-nexus)

<table align="center">
<tr>
<td align="center" valign="middle"><img src="https://github.com/niki914/agentic-nexus/blob/main/res/using_mcp.jpg?raw=true" alt="using_mcp" width="200"/></td>
<td align="center" valign="middle"><img src="https://github.com/niki914/agentic-nexus/blob/main/res/memory.jpg?raw=true" alt="memory" width="200"/></td>
<td align="center" valign="middle"><img src="https://github.com/niki914/agentic-nexus/blob/main/res/mcp_status.jpg?raw=true" alt="mcp_status" width="200"/></td>
</tr>
</table>

Nexus hands the voice assistant you use every day back to you: you decide which model it runs, how it thinks, and what it can do. It is no longer a fixed program that can only check the weather or set alarms, but an agent that truly belongs to you — say a single sentence, and it can orchestrate your entire phone to get it done.

> Currently in Beta — capabilities and stability are being continuously improved. Source code is not yet public.

<table align="center">
<tr>
<td align="center" valign="middle"><img src="https://github.com/niki914/agentic-nexus/blob/main/res/gh_mcp.gif?raw=true" alt="gh_mcp" width="200"/></td>
<td align="center" valign="middle"><img src="https://github.com/niki914/agentic-nexus/blob/main/res/hyper.gif?raw=true" alt="hyper" width="200"/></td>
<td align="center" valign="middle"><img src="https://github.com/niki914/agentic-nexus/blob/main/res/magisk.gif?raw=true" alt="magisk" width="200"/></td>
</tr>
</table>

### Why It's Worth Trying

- A genuinely polished interface: blending Apple Liquid Glass with Material3 — minimal, restrained, elegant. From onboarding to chat to settings, every screen holds up to scrutiny.
- You own the model: freely choose your model and service. What was once a vendor-locked assistant capability becomes a personal configuration you can swap at will.
- Beyond chat: built-in memory, tool calling, and extension capabilities evolve the assistant from "can answer" to "can act".
- Low barrier to entry: core experiences are not tied to complex prerequisites — it just works without fuss.

### The Real Highlight: One Sentence to Command Your Entire Phone

Ordinary model-swapping tools stop at "swap the model's answers." Nexus aims to connect Agent capabilities with Android system powers — through Shell and custom tools, your phone can do incredible things:

- Open any app: "Open my expense tracker" — the assistant launches the app directly.
- Jump to any website: hand your frequently visited pages to the assistant; a single sentence gets you there.
- Run your scripts: let the Agent write a script with cat, then wrap it as a tool like `bash /sdcard/backup.sh` — trigger it with one sentence from then on.
- Send app notifications: when a task finishes, proactively push a notification to let you know.

One level up is MCP: imagine connecting a Mi Home MCP, paired with a rooted phone — "Dim the living room lights, set the AC to 26°C" — your voice assistant does it all. The more MCPs you connect, the larger the world your assistant can reach.

### Tools & Memory

Nexus is more than a model proxy — it ships with a runtime tool system:

- Memory: remembers your preferences and important information, persisting across new conversations.
- Built-in tools: manage toggles for preset foundational capabilities.
- Custom tools: wrap fixed commands into tools the assistant can understand and invoke.
- MCP: connect to external services, auto-discover and use more capabilities.

> Command execution capabilities are powerful, which also means risk — only add commands you understand and trust.

<div align="center">
  <img src="https://github.com/niki914/agentic-nexus/blob/main/res/custom_tool_using__long.jpg?raw=true" alt="custom_tool_using" width="200"/>
</div>

### Compatibility

- Adapted for mainstream voice assistants (e.g., Breeno, XiaoAi), with more adaptations ongoing.
- When the current device does not yet support takeover, you can still use Nexus's built-in chat interface.
- Supports DeepSeek, OpenAI, Anthropic, Google and other models, as well as compatible API endpoints.
- Bring your own API key — Nexus does not bundle model accounts.

### FAQ

#### Is Nexus a chat app?

It has a very usable built-in chat interface, but its core goal is to let your everyday voice assistant connect to your own model and get things done for you.

#### Does it support all phone models and OS versions?

No guarantees. Internal implementations of system assistants change across versions; adaptations proceed gradually by model and version. Uncovered versions may have limited experiences.

#### Does it bundle models or proxy services?

No. You need to provide your own model service, API key, and network environment.

#### Will the project be open-sourced?

Source code is not yet public. This page serves to introduce features, collect feedback, and publish usage guides.

### Feedback

When submitting feedback, please include the following when possible:

- Phone model and system version
- Voice assistant type in use
- Assistant version and Nexus version
- Steps to reproduce, screenshots, or screen recordings
- Relevant logs if available

Community and feedback entry points are as shown on the "About" page within the app.
