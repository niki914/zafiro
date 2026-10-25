"""核心数据模型与规范定义。

只依赖 Python 标准库。定义规则分类、严重级别与违规项结构。
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum


class Severity(str, Enum):
    BLOCK = "block"
    WARN = "warn"


class Category(str, Enum):
    SAFETY = "safety"              # 并发、协程生命周期、异常、日志规范
    UI = "ui"                      # 设计系统规范 (圆角等)、UI 文案
    HYGIENE = "hygiene"            # 代码时态注释、导包与整洁度
    I18N = "i18n"                  # 国际化多语言资源对齐
    ARCHITECTURE = "architecture"  # 单文件 Token / 尺寸上限与 AI 上下文守护


@dataclass(frozen=True)
class Rule:
    name: str
    category: Category
    severity: Severity
    description: str
    hint: str


@dataclass(frozen=True)
class Finding:
    path: str
    lineno: int | None
    rule: str
    category: str
    severity: str
    message: str
    hint: str


@dataclass
class CheckResult:
    findings: list[Finding]
    ignored: int = 0
    scanned_count: int = 0
    notices: list[str] | None = None
