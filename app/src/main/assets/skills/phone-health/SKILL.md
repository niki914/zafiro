---
name: phone-health
description: Checks that the phone can run the team: awake, unlocked, battery, Shizuku, network. Tells the owner once when something blocks work.
license: MIT
---

# Phone health
Run these checks with `device_info` and `execute_python` (rish helper in files/rish, RISH_APPLICATION_ID=com.niki914.zafiro.auto, stdin=DEVNULL):
1. Battery and charging (device_info). Below 25% and not charging: problem.
2. Screen state and lock: `dumpsys window | grep isKeyguardShowing` and wakefulness. Locked or asleep: try `input keyevent KEYCODE_WAKEUP` once. Still locked: problem (never try to bypass a lock).
3. Shizuku/rish alive: run `id` through rish. 'Server is not running': problem. You cannot restart Shizuku yourself without a computer or wireless-debugging pairing, so tell the owner exactly what to tap.
4. Network (device_info). Offline: problem.
5. Recent runs: count FAILED/MISSED/INTERRUPTED in the last 3 hours from the Dashboard data you can read with `board op=list`.
For each problem call `notify` ONCE per day per problem: remember it in files/outputs/health/alerts.json (date + problem) and skip if already sent today. If all is well, do nothing and say STATUS: DONE with 'all clear'.
