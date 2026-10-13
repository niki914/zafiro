---
name: zafiro-config
description: Read or change Zafiro's own settings by editing the JSON files in its sandbox. Use this whenever the user asks to see, explain, change, add or remove Zafiro configuration — agent profiles, execution rules, takeover rules, MCP servers, custom Python tools, text actions, saved LLM configs, or app preferences such as theme and floating ball — and also when a Zafiro setting seems wrong, missing, or stuck. Make sure to use this skill even when the user does not say "config": any request about Zafiro's behaviour that a settings toggle would answer belongs here.
---

# Zafiro config

Zafiro keeps its settings as plain JSON files in its own sandbox. There is no config
tool and no API: you read and write those files directly with `execute_python` or
`shell`. This skill gives you the file map, the shape of each file, the rules that
keep you from damaging user data, and how long a change takes to take effect.

## Where the settings live

```
/data/user/0/<package>/files/settings/
```

You do not need to know `<package>` in advance. Look around the Zafiro sandbox you are
already running inside, find the directory that contains `settings/`, and work from
there. Read the directory listing before each write so you always operate on the file
that actually exists, rather than one you assumed.

If a file below does not exist yet, Zafiro falls back to a built-in default. Writing
the file for the first time is normal and correct.

## The files

| File | Holds | Shape |
| --- | --- | --- |
| `app_state.json` | app preferences: theme, language, floating ball, notifications, markdown scale, draft text, pinned conversations | flat object, one key per setting |
| `tools/builtin_tools.json` | which built-in tools are on | `{"version":2,"enabled":{"<tool_name>":true}}` |
| `tools/custom_py_tools.json` | custom Python tools | `{"tools":[{name,code,description,schema_json,enabled,timeout_ms}]}` |
| `tools/mcp/servers.json` | MCP servers | `{"servers":[{name,url,enabled,headers}]}` |
| `tools/mcp/service.json` | Zafiro exposing its own tools over MCP to a PC | flat object: `enabled`, `port`, `host`, `bearerToken`, `exposedTools`, `keepScreenOn` |
| `tools/text_actions.json` | text-selection actions | `{"actions":[{name,prompt,enabled,requires_screenshot,run_in_background}]}` |
| `rules/execution_rules.json` | command safety rules | `{"rules":[{id,name,enabled_mode,patterns}]}` |
| `rules/takeover_rules.json` | voice-assistant takeover rules | `{"rules":[{id,name,enabled,target,patterns}],"default_target":{...}}` |
| `agents/registry.json` | agent profiles | `{"agents":[{id,name,alias,enabled,order,memory_mode,created_at,updated_at}]}` |
| `agents/<id>/memory.json` | one agent's saved memories | `{"memories":[{id,content,created_at,updated_at}]}` |
| `llm/saved_configs.json` | saved model configs and the active one | `{"active_id":"...","prompt":"...","configs":[{id,name,provider,endpoint,api_key,model,protocol,supports_images,proxy,thinking_level,max_tokens,context_window_tokens,created_at,updated_at}]}` |

Read a file before you change it. Never invent fields — copy the shape you actually
saw, and keep every key you are not deliberately changing.

## Two rules you must not skip

### 1. Sensitive values: mask them, and tell the user

`llm/saved_configs.json` stores `api_key` in plain text. `tools/mcp/servers.json` and
`tools/mcp/service.json` can hold tokens in `headers` and `bearerToken`. Anything you
read enters this conversation and therefore goes to the model provider.

- Prefer not to read those fields at all. When a task needs the rest of the file,
  read it and then remove or mask the secret before you print or echo it.
- Mask as `sk-ba2f****24dc0`: keep the first 4 and last 4 characters, replace the
  middle with `****`.
- **If you do end up seeing a real secret, say so plainly in your reply** — name the
  file and field, and suggest the user rotate that key. Do not hide it, and do not
  repeat the value.
- Never write a masked value back, and never ask the user to tell you a secret — that
  would put the value in this conversation, which is the thing you are avoiding. When a
  key or token must change, tell the user to edit it themselves in the Zafiro settings
  UI, then carry on with whatever part of the task does not need it.

### 2. Write to a temp file, verify, then replace

Never edit a settings file in place. A half-written JSON file can make Zafiro fail to
start. Write the whole document beside the original as `<name>__tmp.json` in the same
directory, read it back and confirm it parses and has the shape you intended, then
replace the original in one atomic step so Zafiro never sees a partial file. Do not
stream a file and close it in place, and do not truncate and rewrite.

Always leave the file a valid JSON document: if it parsed before your change, it must
parse after. If your verification fails, delete the temp file and leave the original
untouched.

## When a change takes effect

This is the part that surprises people, so check it before you tell the user
something is broken.

| What you changed | Effect |
| --- | --- |
| execution rules, takeover rules, text actions, MCP servers, custom Python tools, built-in tool flags, saved LLM configs, agent profiles | **hot** — Zafiro re-reads these from disk on the next turn, and the settings screens re-read on open. No restart needed. |
| `app_state.json` preferences: theme, language, floating ball, resident notification, turn notification, keep screen on, always show message actions, voice-assistant isolated session, markdown scale | **cold** — these are loaded once at app start into in-memory state. The change is on disk but not visible yet. |
| `agents/<id>/memory.json` | hot — memories are re-read each turn |
| anything you are unsure about | assume cold, and tell the user a restart may be needed |

So: after editing an `app_state.json` preference, do not claim it is done and working.
Say what you changed and that it needs a restart to show.

**Do not restart Zafiro yourself, and do not run anything that kills or cold-starts the
app.** A restart ends this conversation, so only the user can decide to do it. Report
that a restart is needed, hand the decision over, and stop there.

## Notes

- Keep `id` and `name` pairs consistent when you edit rules; other code looks rules up
  by `id`.
- `max_tokens` and `context_window_tokens` inside a saved LLM config are **strings**,
  not numbers — an empty string means "not set, use the default". Do not convert them.
- Deleting an entry from `tools/text_actions.json` is permanent; unlike some other
  defaults it is not restored.
- Prefer `execute_python` over `shell` here: JSON handling in Python is safer, and it
  avoids shell quoting bugs around regex patterns, which contain backslashes.
