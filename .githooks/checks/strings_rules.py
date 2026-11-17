"""多语言资源 (strings.xml) 键值对齐与 UI 文案话痨标点检查。

只依赖 Python 标准库。
- 国际化规范 (strings-alignment)：以 values/strings.xml 为基准，所有支持语种严格对齐键集合，防止漏翻与多余键；
- UI 文案规范 (verbose-ui)：检测中文 UI 文案中的分号与解释性括号，杜绝话痨风格。
"""

from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

from core.ignore import extract_file_ignores, is_rule_ignored, split_rules
from core.models import Category, CheckResult, Finding, Rule, Severity

STRINGS_PATTERN = re.compile(r"^(.*?/res)/values([^/]*)/strings\.xml$")
APP_MAIN_RES_DIR = "app/src/main/res"
THEME_SETTINGS_PATH = "app/src/main/java/com/niki914/zafiro/app/ui/content/ThemeSettingsContent.kt"

KNOWN_LOCALES = (
    "values",
    "values-en",
    "values-b+zh+Hant",
    "values-es",
    "values-ja",
    "values-ru",
    "values-ar",
    "values-fa",
)

RE_ALLOWED_PARENS = re.compile(
    r"\(\s*%\d*\$?[a-zA-Z]\s*\)"          # e.g. (%1$s), (%s), (%d)
    r"|（\s*%\d*\$?[a-zA-Z]\s*）"        # fullwidth: （%1$s）
    r"|\b[a-zA-Z_][a-zA-Z0-9_]*\([^)]*\)"  # code notation: main(), main(query: str)
)

ALIGNMENT_RULE = Rule(
    name="strings-alignment",
    category=Category.I18N,
    severity=Severity.BLOCK,
    description="多语言资源键值集合与基准版本未对齐 (存在缺失、多余或重复键)",
    hint="请在对应语言文件中补齐缺失键，或在基准文件中标记 translatable=\"false\"",
)

VERBOSE_UI_RULE = Rule(
    name="verbose-ui",
    category=Category.UI,
    severity=Severity.WARN,
    description="中文 UI 文案包含分号或解释性括号，文案应保持专业简洁、拒绝话痨",
    hint="建议去掉多余解释括号；分号精简为短句或改用换行；确实需要时加 <!-- githooks:ignore verbose-ui --> 放行",
)

LANGUAGE_UI_RULE = Rule(
    name="language-ui-registration",
    category=Category.I18N,
    severity=Severity.BLOCK,
    description="多语言资源目录与 UI 语言选项 (ThemeSettingsContent.kt) 未保持双向同步",
    hint="请在 ThemeSettingsContent.kt 的 languageOptions() 中注册新增语言，或使用 <!-- githooks:ignore-file language-ui-registration --> 放行",
)


@dataclass(frozen=True)
class VerboseUiWarning:
    path: str
    key: str
    text: str
    reasons: tuple[str, ...]


@dataclass(frozen=True)
class ParsedStrings:
    all_keys: frozenset[str]
    translatable_keys: frozenset[str]
    untranslatable_keys: frozenset[str]
    duplicates: tuple[str, ...]
    total_count: int


@dataclass(frozen=True)
class TargetLanguageReport:
    path: str
    locale: str
    total_count: int
    missing_keys: tuple[str, ...]
    extra_keys: tuple[str, ...]
    duplicates: tuple[str, ...]
    xml_error: str | None = None

    @property
    def has_error(self) -> bool:
        return bool(self.xml_error or self.missing_keys or self.extra_keys or self.duplicates)


@dataclass(frozen=True)
class ModuleReport:
    res_dir: str
    base_file: str | None
    base_total: int
    target_reports: list[TargetLanguageReport]
    base_error: str | None = None
    verbose_warnings: tuple[VerboseUiWarning, ...] = ()

    @property
    def has_error(self) -> bool:
        return bool(self.base_error or any(t.has_error for t in self.target_reports))


def parse_strings_xml(content: str) -> tuple[ParsedStrings | None, str | None]:
    """解析 strings.xml 内容为 ParsedStrings。返回 (parsed, error_message)。"""
    try:
        root = ET.fromstring(content)
    except ET.ParseError as e:
        return None, str(e)

    raw_names: list[str] = []
    translatable_keys: set[str] = set()
    untranslatable_keys: set[str] = set()

    for child in root:
        if child.tag in ("string", "plurals", "string-array"):
            name = child.attrib.get("name")
            if not name:
                continue
            raw_names.append(name)
            is_trans = child.attrib.get("translatable", "true").lower() != "false"
            if is_trans:
                translatable_keys.add(name)
            else:
                untranslatable_keys.add(name)

    counts = Counter(raw_names)
    duplicates = tuple(sorted(n for n, c in counts.items() if c > 1))

    return (
        ParsedStrings(
            all_keys=frozenset(raw_names),
            translatable_keys=frozenset(translatable_keys),
            untranslatable_keys=frozenset(untranslatable_keys),
            duplicates=duplicates,
            total_count=len(raw_names),
        ),
        None,
    )


def is_key_ignored(raw_content: str, key: str) -> bool:
    """检查条目是否带有行级/条目级豁免注释。"""
    pattern = (
        r"<!--[^\n]*?githooks:ignore[^\n]*?(?:all|verbose-ui)[^\n]*?-->"
        r"\s*<(?:string|plurals|string-array)[^>]*name=[\"']"
        + re.escape(key)
        + r"[\"']"
    )
    return re.search(pattern, raw_content, re.DOTALL) is not None


def check_verbose_ui(content: str, path: str) -> list[VerboseUiWarning]:
    """检查中文 strings.xml 是否存在话痨违规（分号或解释性括号）。"""
    m = STRINGS_PATTERN.match(path)
    if not m:
        return []
    suffix = m.group(2)
    # 仅检测中文语言包（默认 values/strings.xml 与 values-b+zh*）
    if suffix != "" and not ("zh" in suffix):
        return []

    # 文件级豁免：<!-- githooks:ignore-file verbose-ui --> 或 all
    if re.search(r"<!--.*?githooks:ignore-file\s+(?:all|verbose-ui).*?-->", content):
        return []

    try:
        root = ET.fromstring(content)
    except ET.ParseError:
        return []

    warnings: list[VerboseUiWarning] = []
    for child in root:
        if child.tag in ("string", "plurals", "string-array"):
            key = child.attrib.get("name", "")
            if not key or is_key_ignored(content, key):
                continue

            texts: list[str] = []
            if child.tag == "string":
                if child.text:
                    texts.append(child.text)
            else:
                for item in child.findall("item"):
                    if item.text:
                        texts.append(item.text)

            for text in texts:
                reasons: list[str] = []
                if "；" in text or ";" in text:
                    reasons.append("分号")

                stripped = RE_ALLOWED_PARENS.sub("", text)
                if any(p in stripped for p in ("（", "）", "(", ")")):
                    reasons.append("括号")

                if reasons:
                    warnings.append(
                        VerboseUiWarning(
                            path=path,
                            key=key,
                            text=text.strip().replace("\n", " "),
                            reasons=tuple(reasons),
                        )
                    )
                    break
    return warnings


def discover_locales_from_fs(root: str, res_dir: str = APP_MAIN_RES_DIR) -> set[str]:
    """通过扫描文件系统获取指定 res 目录下的所有 locale 目录名（如 'values', 'values-en'）。"""
    res_path = Path(root, res_dir)
    locales: set[str] = set()
    if res_path.is_dir():
        for p in res_path.glob("values*/strings.xml"):
            try:
                rel = p.relative_to(Path(root)).as_posix()
                m = STRINGS_PATTERN.match(rel)
                if m:
                    locales.add(f"values{m.group(2)}")
            except ValueError:
                pass
    return locales


def get_effective_locales(
    root: str | None = None,
    res_dir: str = APP_MAIN_RES_DIR,
    extra_paths: set[str] | None = None,
    base_known: tuple[str, ...] = KNOWN_LOCALES,
) -> tuple[set[str], set[str]]:
    """合并已知基础语言与动态扫描的语言列表（取并集）。

    返回 (effective_locales, new_discovered_locales)。
    - effective_locales: 最终参与检查的完整语言目录集合；
    - new_discovered_locales: 动态发现但未在 base_known 中登记的新增语言集合。
    """
    discovered: set[str] = set()
    if root:
        discovered.update(discover_locales_from_fs(root, res_dir))
    if extra_paths:
        for p in extra_paths:
            m = STRINGS_PATTERN.match(p)
            if m and (not res_dir or m.group(1) == res_dir):
                discovered.add(f"values{m.group(2)}")
    new_locales = discovered - set(base_known)
    effective = set(base_known) | discovered
    return effective, new_locales


def parse_ui_language_options(
    content: str, path: str = THEME_SETTINGS_PATH
) -> tuple[dict[str, str] | None, str | None]:
    """从 ThemeSettingsContent.kt 解析已注册的语言选项。

    返回 (options_dict, error_message)。
    options_dict 格式为 {tag: label}。
    如果解析失败（如文件被重构、函数名变更等），返回 (None, error_message)。
    """
    # 1. 提取文件内常量（如 private const val LANGUAGE_TAG_ZH_CN = "zh-CN"）
    constants: dict[str, str] = {}
    const_matches = re.finditer(
        r'\b(?:(?:private|internal|public)\s+)?(?:const\s+)?val\s+([A-Za-z0-9_]+)(?:\s*:\s*String)?\s*=\s*"([^"]*)"',
        content,
    )
    for m in const_matches:
        constants[m.group(1)] = m.group(2)

    # 2. 定位 languageOptions() 函数
    func_marker = "fun languageOptions"
    func_idx = content.find(func_marker)
    if func_idx == -1:
        return (
            None,
            f"在 {path} 中未匹配到 'languageOptions()' 函数声明。"
            f"若语言选项函数已更名、迁移为独立 Provider 或被重构，请同步更新 .githooks/checks/strings_rules.py 中的解析逻辑。",
        )

    # 3. 提取函数体（支持花括号函数体或单表达式函数体）
    body: str | None = None
    open_brace_idx = content.find("{", func_idx)
    assign_idx = content.find("=", func_idx)

    if open_brace_idx != -1 and (assign_idx == -1 or open_brace_idx < assign_idx):
        depth = 1
        start_pos = open_brace_idx + 1
        end_pos = -1
        for i in range(start_pos, len(content)):
            char = content[i]
            if char == "{":
                depth += 1
            elif char == "}":
                depth -= 1
                if depth == 0:
                    end_pos = i
                    break
        if end_pos != -1:
            body = content[start_pos:end_pos]
    elif assign_idx != -1:
        rest = content[assign_idx + 1 :]
        m = re.search(r"\n\s*(?:@Composable|fun\b|class\b|private\b|internal\b|public\b|$)", rest)
        body = rest[: m.start()] if m else rest

    if body is None:
        return (
            None,
            f"在 {path} 中无法界定 'languageOptions()' 的函数体边界。"
            f"若代码语法结构调整，请同步更新 .githooks/checks/strings_rules.py。",
        )

    # 4. 提取 LanguageOption(...) 调用
    options: dict[str, str] = {}
    option_matches = re.finditer(r"\bLanguageOption\s*\(([^)]+)\)", body)
    for opt in option_matches:
        args_str = opt.group(1).strip()
        # 提取 tag
        tag: str | None = None
        tag_named = re.search(r'\btag\s*=\s*(?:"([^"]*)"|([A-Za-z0-9_]+))', args_str)
        if tag_named:
            if tag_named.group(1) is not None:
                tag = tag_named.group(1)
            elif tag_named.group(2) is not None:
                tag = constants.get(tag_named.group(2), tag_named.group(2))
        else:
            first_arg = args_str.split(",", 1)[0].strip()
            if first_arg.startswith('"') and first_arg.endswith('"'):
                tag = first_arg[1:-1]
            elif first_arg in constants:
                tag = constants[first_arg]
            elif first_arg:
                tag = first_arg

        # 提取 label
        label: str = ""
        label_named = re.search(
            r'\blabel\s*=\s*(?:"([^"]*)"|stringResource\([^)]+\)|([A-Za-z0-9_]+))',
            args_str,
        )
        if label_named:
            if label_named.group(1) is not None:
                label = label_named.group(1)
            elif label_named.group(2) is not None:
                label = constants.get(label_named.group(2), label_named.group(2))
            else:
                label = "res"
        else:
            parts = [p.strip() for p in args_str.split(",")]
            if len(parts) >= 2:
                second_arg = parts[1]
                if second_arg.startswith('"') and second_arg.endswith('"'):
                    label = second_arg[1:-1]
                elif second_arg in constants:
                    label = constants[second_arg]
                else:
                    label = second_arg

        if tag is not None:
            options[tag] = label

    if not options:
        return (
            None,
            f"在 {path} 的 languageOptions() 中未解析到任何有效的 LanguageOption(...) 项。"
            f"若选项构造方式或数据类型已重构，请同步更新 .githooks/checks/strings_rules.py。",
        )

    return options, None


def locale_to_tags(locale: str) -> set[str]:
    """根据 values 目录名生成对应的候选 BCP-47 语言 tag 集合。"""
    if locale == "values":
        return {"zh-CN", "zh", ""}
    suffix = locale[len("values-") :] if locale.startswith("values-") else locale
    candidates = {suffix}
    if suffix.startswith("b+"):
        clean = suffix[2:].replace("+", "-")
        candidates.add(clean)
        if clean == "zh-Hant":
            candidates.update({"zh-TW", "zh-HK"})
    elif "-r" in suffix:
        clean = suffix.replace("-r", "-")
        candidates.add(clean)
        if clean == "zh-TW":
            candidates.update({"zh-Hant", "b+zh+Hant"})
    return candidates


def tag_matches_locale(tag: str, locale: str) -> bool:
    """判断 BCP-47 tag 与 Android values 目录名是否匹配。"""
    if not tag:
        return locale == "values"
    candidates = locale_to_tags(locale)
    if tag in candidates:
        return True
    tag_clean = tag.lower().replace("_", "-")
    for c in candidates:
        c_clean = c.lower().replace("_", "-")
        if c_clean == tag_clean:
            return True
        if tag_clean.startswith("zh-tw") and "zh-hant" in c_clean:
            return True
        if tag_clean.startswith("zh-hant") and "zh-tw" in c_clean:
            return True
    return False


def check_ui_language_registration(
    app_res_locales: set[str],
    content_provider: Callable[[str], str | None],
    theme_settings_path: str = THEME_SETTINGS_PATH,
    app_res_dir: str = APP_MAIN_RES_DIR,
) -> list[Finding]:
    """检查 app/src/main/res 下的语言目录与 ThemeSettingsContent.kt 中的 UI 选项是否双向对齐。"""
    findings: list[Finding] = []
    content = content_provider(theme_settings_path)
    if content is None:
        findings.append(
            Finding(
                path=theme_settings_path,
                lineno=None,
                rule=LANGUAGE_UI_RULE.name,
                category=LANGUAGE_UI_RULE.category.value,
                severity=LANGUAGE_UI_RULE.severity.value,
                message=(
                    f"未找到 UI 语言注册文件: '{theme_settings_path}'。"
                    f"若该文件已被移动、重命名或重构，请同步更新 .githooks/checks/strings_rules.py 中的配置路径！"
                ),
                hint=LANGUAGE_UI_RULE.hint,
            )
        )
        return findings

    lines = content.splitlines()
    file_ignores = extract_file_ignores(lines)
    if is_rule_ignored(LANGUAGE_UI_RULE.name, file_ignores):
        return []

    parsed_options, parse_err = parse_ui_language_options(content, theme_settings_path)
    if parse_err:
        findings.append(
            Finding(
                path=theme_settings_path,
                lineno=None,
                rule=LANGUAGE_UI_RULE.name,
                category=LANGUAGE_UI_RULE.category.value,
                severity=LANGUAGE_UI_RULE.severity.value,
                message=f"UI 语言注册解析失败: {parse_err}",
                hint=(
                    "若语言注册结构或方法已重构，请同步更新 .githooks/checks/strings_rules.py 中的解析逻辑；"
                    "若确定无需检查，可在文件顶部添加 // githooks:ignore-file language-ui-registration 放行"
                ),
            )
        )
        return findings

    assert parsed_options is not None
    registered_tags = set(parsed_options.keys())

    # 1. 检查 app/src/main/res 下每个语言目录是否在 UI 中注册
    for locale in sorted(app_res_locales):
        matched = any(tag_matches_locale(tag, locale) for tag in registered_tags)
        if not matched:
            strings_file_path = f"{app_res_dir}/{locale}/strings.xml"
            strings_content = content_provider(strings_file_path) or ""
            strings_lines = strings_content.splitlines()
            strings_ignores = extract_file_ignores(strings_lines)
            if is_rule_ignored(LANGUAGE_UI_RULE.name, strings_ignores):
                continue

            suggested_tag = (
                locale[len("values-") :] if locale.startswith("values-") else "zh-CN"
            )
            findings.append(
                Finding(
                    path=strings_file_path,
                    lineno=None,
                    rule=LANGUAGE_UI_RULE.name,
                    category=LANGUAGE_UI_RULE.category.value,
                    severity=LANGUAGE_UI_RULE.severity.value,
                    message=(
                        f"检测到语言资源目录 '{locale}'，但未在 {theme_settings_path} 的 languageOptions() 中注册 UI 选项！"
                        f"(缺少对应 tag: '{suggested_tag}')"
                    ),
                    hint=(
                        f"请在 {theme_settings_path} 中添加对应语言的 LanguageOption(tag = \"{suggested_tag}\", label = \"...\")；"
                        f"若该语言暂不在 UI 界面暴露，可在对应 strings.xml 中添加 <!-- githooks:ignore-file language-ui-registration --> 放行"
                    ),
                )
            )

    # 2. 检查 UI 中注册的每个 tag 是否在 app/src/main/res 下存在对应资源
    for tag, label in sorted(parsed_options.items()):
        if not tag:
            continue
        matched = any(tag_matches_locale(tag, locale) for locale in app_res_locales)
        if not matched:
            findings.append(
                Finding(
                    path=theme_settings_path,
                    lineno=None,
                    rule=LANGUAGE_UI_RULE.name,
                    category=LANGUAGE_UI_RULE.category.value,
                    severity=LANGUAGE_UI_RULE.severity.value,
                    message=(
                        f"UI 语言选项中注册了 tag: '{tag}' (label: '{label}')，但在 {app_res_dir} 下未找到对应的 values 资源目录！"
                    ),
                    hint=(
                        f"请在 {app_res_dir} 下创建对应语言目录 (如 values-{tag}/strings.xml) 并对齐翻译键；"
                        f"或在 {theme_settings_path} 中移除未就绪的语言选项"
                    ),
                )
            )

    return findings


def discover_locale_files(root: str, res_dir: str, extra_paths: set[str] | None = None) -> dict[str, str]:
    """发现给定 res 目录下所有语言版本的 strings.xml。返回 {locale_suffix: relative_path}。"""
    res_path = Path(root, res_dir)
    results: dict[str, str] = {}
    all_candidate_paths: set[str] = set()

    if res_path.is_dir():
        for p in res_path.glob("values*/strings.xml"):
            try:
                all_candidate_paths.add(p.relative_to(Path(root)).as_posix())
            except ValueError:
                pass

    if extra_paths:
        for p in extra_paths:
            if p.startswith(res_dir + "/values"):
                all_candidate_paths.add(p)

    for rel in all_candidate_paths:
        m = STRINGS_PATTERN.match(rel)
        if m:
            suffix = m.group(2)
            results[suffix] = rel

    return results


def check_res_dir(
    root: str,
    res_dir: str,
    content_provider: Callable[[str], str | None],
    extra_paths: set[str] | None = None,
    expected_locales: set[str] | None = None,
) -> ModuleReport | None:
    """检查单个 res 目录下的各语言 strings.xml 对齐情况与 UI 文案。"""
    locale_map = discover_locale_files(root, res_dir, extra_paths)
    if not locale_map and not expected_locales:
        return None

    if expected_locales is None and res_dir == APP_MAIN_RES_DIR:
        expected_locales, _ = get_effective_locales(root, res_dir, extra_paths)

    base_path = locale_map.get("")
    if not base_path:
        return ModuleReport(
            res_dir=res_dir,
            base_file=None,
            base_total=0,
            target_reports=[],
            base_error="缺少基准语言文件 (values/strings.xml)",
        )

    base_content = content_provider(base_path)
    if base_content is None:
        return ModuleReport(
            res_dir=res_dir,
            base_file=base_path,
            base_total=0,
            target_reports=[],
            base_error=f"无法读取基准语言文件: {base_path}",
        )

    base_parsed, base_err = parse_strings_xml(base_content)
    if base_err or not base_parsed:
        return ModuleReport(
            res_dir=res_dir,
            base_file=base_path,
            base_total=0,
            target_reports=[],
            base_error=f"基准语言 XML 解析失败: {base_err}",
        )

    verbose_warnings: list[VerboseUiWarning] = []
    verbose_warnings.extend(check_verbose_ui(base_content, base_path))
    target_reports: list[TargetLanguageReport] = []

    for suffix, target_path in sorted(locale_map.items()):
        if suffix == "":
            continue
        locale_name = f"values{suffix}"
        content = content_provider(target_path)
        if content is None:
            target_reports.append(
                TargetLanguageReport(
                    path=target_path,
                    locale=locale_name,
                    total_count=0,
                    missing_keys=(),
                    extra_keys=(),
                    duplicates=(),
                    xml_error=f"无法读取文件: {target_path}",
                )
            )
            continue

        target_parsed, target_err = parse_strings_xml(content)
        if target_err or not target_parsed:
            target_reports.append(
                TargetLanguageReport(
                    path=target_path,
                    locale=locale_name,
                    total_count=0,
                    missing_keys=(),
                    extra_keys=(),
                    duplicates=(),
                    xml_error=f"XML 解析失败: {target_err}",
                )
            )
            continue

        verbose_warnings.extend(check_verbose_ui(content, target_path))

        missing = tuple(sorted(base_parsed.translatable_keys - target_parsed.all_keys))
        extra = tuple(sorted(target_parsed.all_keys - base_parsed.all_keys))
        target_reports.append(
            TargetLanguageReport(
                path=target_path,
                locale=locale_name,
                total_count=target_parsed.total_count,
                missing_keys=missing,
                extra_keys=extra,
                duplicates=target_parsed.duplicates,
            )
        )

    # 检查 expected_locales 中缺失的语言文件
    if expected_locales:
        existing_locales = {f"values{suffix}" for suffix in locale_map.keys()}
        for exp_loc in sorted(expected_locales):
            if exp_loc == "values":
                continue
            if exp_loc not in existing_locales:
                missing_path = f"{res_dir}/{exp_loc}/strings.xml"
                target_reports.append(
                    TargetLanguageReport(
                        path=missing_path,
                        locale=exp_loc,
                        total_count=0,
                        missing_keys=(),
                        extra_keys=(),
                        duplicates=(),
                        xml_error=f"缺少语言资源文件: {missing_path}",
                    )
                )

    return ModuleReport(
        res_dir=res_dir,
        base_file=base_path,
        base_total=base_parsed.total_count,
        target_reports=target_reports,
        verbose_warnings=tuple(verbose_warnings),
    )


def print_stat_table(reports: list[ModuleReport]) -> int:
    """输出各模块多语言键值统计大盘。"""
    all_locales: set[str] = set(KNOWN_LOCALES) - {"values"}
    for r in reports:
        for t in r.target_reports:
            all_locales.add(t.locale)
    sorted_targets = sorted(all_locales)

    headers = ["module", "values"] + sorted_targets + ["status"]
    rows = []
    for r in sorted(reports, key=lambda x: x.res_dir):
        row = [r.res_dir]
        if r.base_error:
            row.extend(["ERR"] * (len(headers) - 2))
            row.append(f"错误: {r.base_error}")
        else:
            row.append(str(r.base_total))
            loc_counts = {t.locale: t.total_count for t in r.target_reports}
            for loc in sorted_targets:
                if loc in loc_counts:
                    row.append(str(loc_counts[loc]))
                else:
                    row.append("-")

            if r.has_error:
                issues = []
                for t in r.target_reports:
                    if t.missing_keys:
                        issues.append(f"{t.locale} 缺 {len(t.missing_keys)}")
                    if t.extra_keys:
                        issues.append(f"{t.locale} 多 {len(t.extra_keys)}")
                status_str = f"不一致 ({', '.join(issues)})"
            else:
                status_str = "对齐"
            row.append(status_str)
        rows.append(row)

    col_widths = [max(len(row[i]) for row in ([headers] + rows)) for i in range(len(headers))]
    line_format = "  ".join(f"{{:<{w}}}" for w in col_widths)

    print(line_format.format(*headers))
    print("  ".join("-" * w for w in col_widths))
    for row in rows:
        print(line_format.format(*row))
    return 0


def reports_to_check_result(
    reports: list[ModuleReport],
    new_language_files: list[str],
    active_rules: set[str] | None = None,
    new_locales: set[str] | None = None,
    extra_findings: list[Finding] | None = None,
) -> CheckResult:
    """将 ModuleReport 转换为统一的 CheckResult 结构。"""
    findings: list[Finding] = []
    notices: list[str] = []

    if new_locales:
        notices.append(
            f"检测到新增语言目录: {', '.join(sorted(new_locales))}。"
            f"已自动合并至生效语言集合并进行对齐检查。"
        )

    if new_language_files:
        notices.append(
            f"检测到新增语言文件: {', '.join(new_language_files)}。"
            f"请确认项目支持的语种范围 ({', '.join(KNOWN_LOCALES)})，检查其他模块是否需同步补齐。"
        )

    # 1. verbose-ui 规则 (UI 分类)
    if active_rules is None or VERBOSE_UI_RULE.name in active_rules:
        for r in reports:
            for w in r.verbose_warnings:
                reasons_str = "与".join(w.reasons)
                findings.append(
                    Finding(
                        path=w.path,
                        lineno=None,
                        rule=VERBOSE_UI_RULE.name,
                        category=VERBOSE_UI_RULE.category.value,
                        severity=VERBOSE_UI_RULE.severity.value,
                        message=f"条目 [{w.key}] 包含{reasons_str}: \"{w.text}\"",
                        hint=VERBOSE_UI_RULE.hint,
                    )
                )

    # 2. strings-alignment 规则 (I18N 分类)
    if active_rules is None or ALIGNMENT_RULE.name in active_rules:
        for r in reports:
            if r.base_error:
                findings.append(
                    Finding(
                        path=r.base_file or r.res_dir,
                        lineno=None,
                        rule=ALIGNMENT_RULE.name,
                        category=ALIGNMENT_RULE.category.value,
                        severity=ALIGNMENT_RULE.severity.value,
                        message=r.base_error,
                        hint=ALIGNMENT_RULE.hint,
                    )
                )
            for t in r.target_reports:
                if not t.has_error:
                    continue
                diff_parts = []
                if t.missing_keys:
                    diff_parts.append(f"缺少 {len(t.missing_keys)} 个")
                if t.extra_keys:
                    diff_parts.append(f"多余 {len(t.extra_keys)} 个")
                if t.duplicates:
                    diff_parts.append(f"重复定义 {len(t.duplicates)} 个")

                detail_msg = f"{t.locale} ({', '.join(diff_parts)})"
                if t.xml_error:
                    detail_msg = f"{t.locale}: {t.xml_error}"
                elif t.missing_keys:
                    top_missing = ", ".join(t.missing_keys[:5])
                    if len(t.missing_keys) > 5:
                        top_missing += f" 等 {len(t.missing_keys)} 个"
                    detail_msg += f" ➔ 缺失键: [{top_missing}]"

                findings.append(
                    Finding(
                        path=t.path,
                        lineno=None,
                        rule=ALIGNMENT_RULE.name,
                        category=ALIGNMENT_RULE.category.value,
                        severity=ALIGNMENT_RULE.severity.value,
                        message=detail_msg,
                        hint=ALIGNMENT_RULE.hint,
                    )
                )

    # 3. 额外注入的 findings (如 language-ui-registration)
    if extra_findings:
        for f in extra_findings:
            if active_rules is None or f.rule in active_rules:
                findings.append(f)

    return CheckResult(
        findings=findings,
        ignored=0,
        scanned_count=len(reports),
        notices=notices,
    )
