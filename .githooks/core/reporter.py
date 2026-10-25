"""统一报告、高亮提示与统计表格生成模块。

只依赖 Python 标准库。
提供一致的终端格式化、阻断拦截反馈、自适应列宽统计大盘与规则字典目录输出。
"""

from __future__ import annotations

from collections import Counter, defaultdict

from core.models import Finding, Rule, Severity


def print_findings(
    findings: list[Finding],
    ignored: int,
    scope_note: str = "",
    notices: list[str] | None = None,
) -> int:
    """输出检查违规明细与汇总，根据是否存在阻断项返回退出码 (1 表示阻断，0 表示通过)。"""
    if notices:
        for notice in notices:
            print(f"githooks: [提示] {notice}")
        print()

    blocking_findings = [f for f in findings if f.severity == Severity.BLOCK.value]
    warning_findings = [f for f in findings if f.severity != Severity.BLOCK.value]

    grouped: dict[str, list[Finding]] = defaultdict(list)
    for f in findings:
        grouped[f.path].append(f)

    for path in sorted(grouped):
        for f in sorted(grouped[path], key=lambda x: (x.lineno or 0)):
            prefix = f"{f.path}:{f.lineno}:" if f.lineno is not None else f"{f.path}:"
            tag = "阻断" if f.severity == Severity.BLOCK.value else "警告"
            print(f"{prefix} [{tag}] [{f.category}] {f.rule}: {f.message}")
            if f.hint:
                print(f"    → {f.hint}")

    if findings or ignored:
        parts: list[str] = []
        if blocking_findings:
            parts.append(f"{len(blocking_findings)} 阻断")
        if warning_findings:
            parts.append(f"{len(warning_findings)} 警告")
        parts.append(f"{ignored} 已豁免")
        print()
        print(f"githooks: {'，'.join(parts)}{scope_note}")

    if blocking_findings:
        print("githooks: 提交被拦截。修正后重新 git add；确有必要时按 .githooks/README.md 放行。")
        return 1
    return 0


def print_stat(
    findings: list[Finding],
    total_scanned: int,
    evaluated_rules: tuple[str, ...] | None = None,
) -> int:
    """输出按模块统计的动态列宽大盘表格（根据已评估规则自适应列，不漏项）。"""
    per_module: dict[str, Counter] = defaultdict(Counter)
    present_rules: set[str] = set()

    for f in findings:
        module = f.path.split("/")[0] if "/" in f.path else f.path
        per_module[module][f.rule] += 1
        present_rules.add(f.rule)

    # 确定要展示的列：优先使用传入的 evaluated_rules，否则使用当前出现的规则
    if evaluated_rules:
        display_rules = [r for r in evaluated_rules if r in present_rules or len(evaluated_rules) <= 12]
    else:
        display_rules = sorted(present_rules)

    if not display_rules:
        display_rules = ["none"]

    module_col_width = max([len(m) for m in per_module] + [6])
    rule_col_widths = {r: max(len(r), 6) for r in display_rules}

    # 表头
    headers = [f"{'module':<{module_col_width}}"]
    for r in display_rules:
        headers.append(f"{r:>{rule_col_widths[r]}}")
    headers.append(f"{'total':>6}")
    print("  ".join(headers))
    print("-" * (sum(len(h) for h in headers) + (len(headers) - 1) * 2))

    # 数据行
    for module in sorted(per_module, key=lambda m: -sum(per_module[m].values())):
        c = per_module[module]
        row = [f"{module:<{module_col_width}}"]
        for r in display_rules:
            val = c.get(r, 0)
            row.append(f"{val:>{rule_col_widths[r]}}")
        row.append(f"{sum(c.values()):>6}")
        print("  ".join(row))

    total_violations = len(findings)
    print()
    print(f"githooks: 扫描 {total_scanned} 个目标，共 {total_violations} 处违规")
    return 0


def print_rules_catalog(rules: list[Rule]) -> int:
    """按类别分组列出所有规则清单。"""
    by_category: dict[str, list[Rule]] = defaultdict(list)
    for r in rules:
        by_category[r.category.value].append(r)

    print("================================================================================")
    print("githooks 规则分类全集 (Rule Taxonomy Catalog)")
    print("================================================================================")

    for cat_name in sorted(by_category):
        cat_rules = by_category[cat_name]
        print(f"\n📂 [{cat_name.upper()}] ({len(cat_rules)} 条规则)")
        print("-" * 60)
        for r in cat_rules:
            level_str = "阻断" if r.severity == Severity.BLOCK else "警告"
            print(f"  • {r.name} [{level_str}]: {r.description}")
            if r.hint:
                print(f"      → {r.hint}")

    print("\n================================================================================")
    return 0
