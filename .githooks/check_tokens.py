#!/usr/bin/env python3
"""兼容入口：单文件 Token 上限检查。

转发至 cli.py，并保留原有纯函数接口以兼容历史导入与单测。
"""

from __future__ import annotations

import sys
from pathlib import Path

HOOKS_DIR = Path(__file__).resolve().parent
if str(HOOKS_DIR) not in sys.path:
    sys.path.insert(0, str(HOOKS_DIR))

from checks.tokens_rules import (
    BYTE_LIMIT,
    CODEGEN_HEADER_MARKERS,
    CODEGEN_NAME_PATTERNS,
    EXCLUDE_PREFIXES,
    RULE_NAME,
    TARGET_SUFFIX,
    TOKEN_LIMIT,
    Violation,
    calculate_tokens,
    check_token_limits,
    is_codegen_filename,
    is_codegen_header,
    is_excluded_prefix,
)
from core.ignore import is_file_ignored as core_is_file_ignored


def is_ignored_file(content: str, rule: str = RULE_NAME) -> bool:
    return core_is_file_ignored(content, rule)


def format_violation_report(violations: list[Violation]) -> str:
    lines = [
        "",
        "================================================================================",
        f"❌ [GITHOOKS] 提交被拦截：检测到 {len(violations)} 个文件超出 {TOKEN_LIMIT:,} Tokens 限制 (上限 {BYTE_LIMIT:,} Bytes)",
        "================================================================================",
        "",
        "【超标文件明细】",
    ]
    for v in sorted(violations, key=lambda x: -x.tokens):
        ratio = (v.tokens / TOKEN_LIMIT) * 100
        lines.append(
            f"  • {v.path}\n"
            f"    当前大小: {v.bytes_size:,} Bytes ({v.tokens:,} Tokens) ➔ "
            f"超标 +{v.excess_tokens:,} Tokens (超限 {ratio:.1f}%)"
        )
    lines.extend([
        "",
        "--------------------------------------------------------------------------------",
        "💡 为什么限制 5,000 Tokens？",
        "  1. 保护模型注意力：单文件超长会导致 AI Agent 读写时丢失上下文，出现幻觉或工具截断；",
        "  2. 降低合并冲突：细粒度小文件显著减少多分支并发编辑时的冲突与爆炸半径；",
        "  3. 践行单一职责：倒逼按领域边界进行合理拆分与解耦。",
        "",
        "--------------------------------------------------------------------------------",
        "🛠️ 分场景拆分实操指引 (Step-by-Step Splitting Recipes)：",
        "",
        "  1. UI / Jetpack Compose 页面拆解：",
        "     • [抽离子组件] 将主页面中的局部布局独立为同级组件（如 *Header.kt, *List.kt, *Dialog.kt）；",
        "     • [拆分 Previews] 将 @Preview 组合函数统一迁移到独立的 *Previews.kt；",
        "     • [剥离样式配置] 将页面特定的配色、尺寸常数或动画规格抽出至 *Constants.kt。",
        "",
        "  2. ViewModel / 业务逻辑层拆解：",
        "     • [契约与状态下沉] 将 State、Event、Intent 独立到 *Contract.kt 或 *State.kt；",
        "     • [领域服务委托] 将非核心辅助业务（权限检查、数据格式化、埋点等）下沉至独立 Domain Service / UseCase；",
        "     • [工具扩展外置] 将私有数据变换辅助方法提取为顶层扩展函数放到 *Extensions.kt。",
        "",
        "  3. Repository / Service 数据分层拆解：",
        "     • [按领域生命周期拆分] 避免 Monolithic 大类，按业务实体分为子 Repo（如 AuthRepo, SyncRepo）；",
        "     • [DTO / 数据模型剥离] 将网络传输对象与数据库 Entity 抽离至独立的 *Models.kt；",
        "     • [存储与 IO 解耦] 将底层 Cache 与本地持久化逻辑独立为专用 Storage Delegate。",
        "",
        "  4. 单元测试类拆解：",
        "     • [按测试场景分流] 拆分为 *HappyPathTest.kt、*ErrorHandlingTest.kt、*ConcurrentTest.kt；",
        "     • [共享测试夹具] 将冗长的 Mock 数据构造与 Fixture 抽取至公共 *TestFixtures.kt。",
        "",
        "--------------------------------------------------------------------------------",
        "⚠️ 特殊场景豁免方式（Escape Hatch）：",
        "若该文件确实无法拆分且确有必要保留单文件形态，可在该文件内（推荐第一行）添加放行标记：",
        f"    // githooks:ignore-file {RULE_NAME}",
        "================================================================================",
    ])
    return "\n".join(lines)


def inspect_candidates(
    root: str,
    paths: list[str],
    size_getter,
    content_getter,
) -> tuple[list[Violation], int]:
    violations: list[Violation] = []
    skipped = 0
    for path in paths:
        if is_excluded_prefix(path) or is_codegen_filename(path):
            skipped += 1
            continue
        size = size_getter(root, path)
        if size <= BYTE_LIMIT:
            continue
        head = content_getter(root, path)
        if is_codegen_header(head) or is_ignored_file(head):
            skipped += 1
            continue
        tokens = calculate_tokens(size)
        violations.append(Violation(path, size, tokens, tokens - TOKEN_LIMIT))
    return violations, skipped


def main(argv: list[str] | None = None) -> int:
    from cli import main as cli_main

    args = list(argv) if argv is not None else sys.argv[1:]
    if "--stat" in args:
        from core.git import get_all_tracked_files, get_repo_root
        from checks.tokens_rules import check_token_limits, print_stat as tokens_print_stat, TARGET_SUFFIX, EXCLUDE_PREFIXES
        root = get_repo_root()
        if root:
            all_kt = get_all_tracked_files(root, TARGET_SUFFIX, EXCLUDE_PREFIXES)
            def fs_size(p: str) -> int:
                try:
                    return Path(root, p).stat().st_size
                except OSError:
                    return 0
            def fs_content(p: str) -> str:
                try:
                    with open(Path(root, p), "r", encoding="utf-8", errors="replace") as f:
                        return "".join([f.readline() for _ in range(20)])
                except OSError:
                    return ""
            violations, _ = inspect_candidates(root, all_kt, lambda r, p: fs_size(p), lambda r, p: fs_content(p))
            return tokens_print_stat(violations, len(all_kt))

    if not any(a in ("--category", "-c", "--rule", "-r") for a in args):
        args = ["--rule", RULE_NAME, *args]
    return cli_main(args)


if __name__ == "__main__":
    sys.exit(main())
