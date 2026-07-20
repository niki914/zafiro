#!/usr/bin/env python3
import argparse
import curses
import datetime as dt
import os
import subprocess
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass, field


MARKER = "__GIT_STAT_COMMIT__"
KINDS = ("feat", "fix", "chores", "wip", "refactor", "docs", "plan", "test", "other")


@dataclass
class Commit:
    sha: str
    date: dt.date
    subject: str
    added: int = 0
    deleted: int = 0
    kind: str = "other"


@dataclass
class Stats:
    days: int
    start: dt.date
    end: dt.date
    commits: list[Commit] = field(default_factory=list)
    by_day: dict[dt.date, list[Commit]] = field(default_factory=lambda: defaultdict(list))
    by_kind: Counter = field(default_factory=Counter)


def run_git(args: list[str]) -> str:
    proc = subprocess.run(
        ["git", *args],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr.strip() or "git command failed")
    return proc.stdout


def repo_name() -> str:
    try:
        root = run_git(["rev-parse", "--show-toplevel"]).strip()
        return os.path.basename(root) or root
    except Exception:
        return "git repo"


def classify(subject: str) -> str:
    text = subject.strip().lower()
    prefix = text.split(":", 1)[0].split(" ", 1)[0]
    if prefix in KINDS:
        return prefix
    if prefix == "chore":
        return "chores"
    if text.startswith("use "):
        return "refactor"
    return "other"


def parse_int(value: str) -> int:
    return int(value) if value.isdigit() else 0


def load_stats(days: int) -> Stats:
    today = dt.date.today()
    start = today - dt.timedelta(days=days - 1)
    pretty = f"{MARKER}%x09%H%x09%ad%x09%s"
    output = run_git([
        "log",
        f"--since={start.isoformat()} 00:00:00",
        "--date=short",
        f"--pretty=format:{pretty}",
        "--numstat",
    ])
    commits: list[Commit] = []
    current: Commit | None = None
    for raw in output.splitlines():
        line = raw.rstrip("\n")
        if line.startswith(MARKER + "\t"):
            _, sha, date_text, subject = line.split("\t", 3)
            current = Commit(
                sha=sha[:8],
                date=dt.date.fromisoformat(date_text),
                subject=subject,
                kind=classify(subject),
            )
            commits.append(current)
            continue
        if current and "\t" in line:
            parts = line.split("\t")
            if len(parts) >= 3:
                current.added += parse_int(parts[0])
                current.deleted += parse_int(parts[1])

    stats = Stats(days=days, start=start, end=today, commits=commits)
    for commit in commits:
        stats.by_day[commit.date].append(commit)
        stats.by_kind[commit.kind] += 1
    return stats


def day_range(stats: Stats) -> list[dt.date]:
    return [stats.start + dt.timedelta(days=i) for i in range(stats.days)]


def recent_days(stats: Stats) -> list[dt.date]:
    return list(reversed(day_range(stats)))


def streak(stats: Stats) -> int:
    count = 0
    cursor = stats.end
    while cursor >= stats.start and stats.by_day.get(cursor):
        count += 1
        cursor -= dt.timedelta(days=1)
    return count


def safe_add(stdscr, y: int, x: int, text: str, attr: int = 0) -> None:
    height, width = stdscr.getmaxyx()
    if y < 0 or y >= height or x >= width:
        return
    text = text[: max(0, width - x - 1)]
    if text:
        stdscr.addstr(y, x, text, attr)


def bar(value: int, max_value: int, width: int, char: str = "#") -> str:
    if width <= 0:
        return ""
    if max_value <= 0:
        return "." * min(width, 1)
    size = max(1 if value else 0, round(value / max_value * width))
    return char * size + "." * max(0, width - size)


def fmt_num(value: float) -> str:
    return f"{value:.2f}".rstrip("0").rstrip(".")


def heat_strip(stats: Stats) -> str:
    days = day_range(stats)
    max_count = max((len(stats.by_day.get(day, [])) for day in days), default=0)
    chars = " .:-=+*#%@"
    result = []
    for day in days:
        count = len(stats.by_day.get(day, []))
        if count == 0 or max_count == 0:
            result.append(".")
        else:
            index = max(1, round(count / max_count * (len(chars) - 1)))
            result.append(chars[index])
    return "".join(result)


def init_colors() -> dict[str, int]:
    curses.start_color()
    curses.use_default_colors()
    pairs = {
        "feat": curses.COLOR_GREEN,
        "fix": curses.COLOR_RED,
        "chores": curses.COLOR_CYAN,
        "wip": curses.COLOR_YELLOW,
        "refactor": curses.COLOR_MAGENTA,
        "docs": curses.COLOR_BLUE,
        "plan": curses.COLOR_WHITE,
        "test": curses.COLOR_CYAN,
        "other": curses.COLOR_WHITE,
        "muted": curses.COLOR_BLACK,
        "title": curses.COLOR_YELLOW,
    }
    result: dict[str, int] = {}
    for idx, (name, color) in enumerate(pairs.items(), start=1):
        curses.init_pair(idx, color, -1)
        result[name] = curses.color_pair(idx)
    return result


def draw_header(stdscr, stats: Stats, colors: dict[str, int]) -> int:
    total = len(stats.commits)
    active_days = sum(1 for day in day_range(stats) if stats.by_day.get(day))
    avg = total / max(1, stats.days)
    active_avg = total / max(1, active_days)
    peak_day, peak_count = max(
        ((day, len(stats.by_day.get(day, []))) for day in day_range(stats)),
        key=lambda item: item[1],
    )
    title = f"git_stat.py | {repo_name()} | last {stats.days} days | q quit | arrows/page scroll days"
    safe_add(stdscr, 0, 0, title, colors["title"] | curses.A_BOLD)
    safe_add(
        stdscr,
        1,
        0,
        (
            f"commits {total} | active days {active_days}/{stats.days} | "
            f"avg/day {fmt_num(avg)} | active avg {fmt_num(active_avg)} | "
            f"streak {streak(stats)}d | peak {peak_day:%m-%d} x{peak_count}"
        ),
        curses.A_BOLD,
    )
    safe_add(stdscr, 2, 0, f"speed [{heat_strip(stats)}]  .=quiet  @=peak", colors["muted"])
    return 4


def draw_kinds(stdscr, y: int, stats: Stats, colors: dict[str, int]) -> int:
    _, width = stdscr.getmaxyx()
    chart_width = max(8, min(42, width - 20))
    max_count = max(stats.by_kind.values(), default=0)
    safe_add(stdscr, y, 0, "Commit style", curses.A_BOLD)
    y += 1
    for kind in KINDS:
        count = stats.by_kind.get(kind, 0)
        if count == 0 and kind not in ("feat", "fix", "chores", "wip", "refactor"):
            continue
        line = f"{kind:<8} {bar(count, max_count, chart_width, '=')} {count:>3}"
        safe_add(stdscr, y, 0, line, colors.get(kind, 0))
        y += 1
    return y + 1


def draw_daily(stdscr, y: int, stats: Stats, offset: int, colors: dict[str, int]) -> int:
    height, width = stdscr.getmaxyx()
    chart_width = max(8, min(52, width - 38))
    days = day_range(stats)
    counts = {day: len(stats.by_day.get(day, [])) for day in days}
    max_count = max(counts.values(), default=0)
    visible_days = recent_days(stats)
    rows = max(0, height - y - 3)
    page = visible_days[offset : offset + rows]
    page_no = offset // max(1, rows) + 1
    page_count = max(1, (len(visible_days) + max(1, rows) - 1) // max(1, rows))
    safe_add(stdscr, y, 0, f"Daily commits | recent on top | page {page_no}/{page_count}", curses.A_BOLD)
    y += 1
    for day in page:
        commits = stats.by_day.get(day, [])
        added = sum(item.added for item in commits)
        deleted = sum(item.deleted for item in commits)
        line = (
            f"{day:%m-%d} {bar(len(commits), max_count, chart_width)} "
            f"{len(commits):>2}  +{added:<5} -{deleted:<5}"
        )
        attr = curses.A_NORMAL if commits else colors["muted"]
        safe_add(stdscr, y, 0, line, attr)
        y += 1
    return y + 1


def draw(stdscr, stats: Stats, offset: int, colors: dict[str, int]) -> None:
    stdscr.erase()
    height, width = stdscr.getmaxyx()
    if height < 16 or width < 56:
        safe_add(stdscr, 0, 0, "Terminal too small. Need about 56x16.")
        stdscr.refresh()
        return
    y = draw_header(stdscr, stats, colors)
    y = draw_kinds(stdscr, y, stats, colors)
    draw_daily(stdscr, y, stats, offset, colors)
    safe_add(stdscr, height - 1, 0, "Use Up/Down, j/k, PgUp/PgDn to scroll days. q exits.", colors["muted"])
    stdscr.refresh()


def tui(stdscr, stats: Stats) -> None:
    curses.curs_set(0)
    stdscr.keypad(True)
    colors = init_colors()
    offset = 0
    while True:
        draw(stdscr, stats, offset, colors)
        key = stdscr.getch()
        max_offset = max(0, len(recent_days(stats)) - 1)
        page_step = max(1, stdscr.getmaxyx()[0] - 16)
        if key in (ord("q"), ord("Q"), 27):
            break
        if key in (curses.KEY_DOWN, ord("j")):
            offset = min(max_offset, offset + 1)
        elif key in (curses.KEY_UP, ord("k")):
            offset = max(0, offset - 1)
        elif key == curses.KEY_NPAGE:
            offset = min(max_offset, offset + page_step)
        elif key == curses.KEY_PPAGE:
            offset = max(0, offset - page_step)


def print_plain(stats: Stats) -> None:
    max_count = max((len(stats.by_day.get(day, [])) for day in day_range(stats)), default=0)
    print(f"git_stat.py | {repo_name()} | last {stats.days} days")
    print(f"commits: {len(stats.commits)}, active streak: {streak(stats)}d")
    print()
    for kind in KINDS:
        count = stats.by_kind.get(kind, 0)
        if count:
            print(f"{kind:<8} {count}")
    print()
    for day in recent_days(stats):
        count = len(stats.by_day.get(day, []))
        print(f"{day:%m-%d} {bar(count, max_count, 32)} {count:>2}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Small ASCII TUI for git development speed.")
    parser.add_argument("--days", type=int, default=30, help="days to inspect, default: 30")
    parser.add_argument("--plain", action="store_true", help="print a non-interactive report")
    args = parser.parse_args()
    days = max(1, min(args.days, 365))
    try:
        stats = load_stats(days)
        if args.plain or not sys.stdout.isatty():
            print_plain(stats)
        else:
            curses.wrapper(tui, stats)
        return 0
    except Exception as exc:
        print(f"git_stat.py: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
