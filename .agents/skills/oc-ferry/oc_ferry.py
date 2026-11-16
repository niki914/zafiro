#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""oc_ferry.py — OC Ferry 手机端核心实现（单文件、纯标准库、Python 3.8+）。

本模块把桌面版 OC Ferry（Electron 托盘应用）的五条核心链路移植为
「Zafiro 可直接加载的 Skill + 端侧 Python 3 本机转发服务」：

1. 发现：只按价格字段判定免费（input/output/cache_read/cache_write 全为 0），
   名字里含 "free" 不作数，声明 deprecated 的直接排除。
2. 体检：5 个工具 + 随机 token 文件，忠实移植 no_action / probe_mismatch /
   invalid_model_output / invalid_tool_call 四级判定；仅「语义不符」与
   「无动作」重试 1 次，超时与格式失败不重试。
3. 缓存：成功判定保留 6 小时，当时负载状态只保留 10 分钟，
   按模型 ID + 能力指纹失效，跨重启 / 跨进程可用，损坏文件安全降级。
4. 写入：只增删带 ``_oc_ferry`` 标记的条目，用户手写条目绝不触碰；
   写前备份、加锁、原子替换、内容没变不落盘，退出时把自己的条目摘干净。
5. 转发：只监听 127.0.0.1:41980 的 OpenAI 兼容端口；Bearer 定时安全比较、
   请求体 8MB 上限、并发上限 4、SSE 流式输出。桌面端「带 Origin 一律 403」
   的围栏在手机端**只作用于** ``/v1/*`` 与 ``/admin/*`` 数据接口（拒绝非同源
   ``Origin``）；``/panel``、``/``、``/health`` 不做 Origin 检查。
   ``GET /panel`` 与 ``GET /`` 是**免 Bearer、免 Origin 检查、零敏感数据**的
   静态页：页面本身不含任何模型 / 状态 / 密钥，用户无需先输入 key 即可打开；
   所有模型数据与全部管理操作仍由 ``/v1/*`` 与 ``/admin/*`` 强制 Bearer 鉴权
   （``/health`` 只回非敏感存活状态，仅回环）。安全边界 = 只绑 ``127.0.0.1``
   + 数据接口 Bearer + 静态页零数据。审批围栏保证模型只能用 JSON 信封表达
   意图，本机动作一律拒绝并转交为外部工具调用，执行权始终留在客户端。

设计约束（重要）：
- 只用 Python 3 标准库，兼容 Chaquopy 的 Python 3.8；
- 所有网络 I/O 都通过可注入的 :data:`HttpCall` 函数完成，
  纯逻辑路径（doctor / status / cleanup / import / 判定 / 合并）绝不联网；
- ``import oc_ferry`` 零副作用：不读文件、不建目录、不联网、不生成密钥。
- ``export`` 把免费模型清单导出为 md / json / csv 文档：默认走本地快照或
  ``--catalog``（完全离线），仅 ``--online`` 读取公开目录；**不需要 API Key，
  不向模型发送推理请求，不消耗任何额度**。手机 Termux 可直接使用，无需 Zafiro。

诚实交代：体检会自动向上游发送简短请求，消耗少量免费额度；「通过」只代表
这一次能按协议产生动作；上游不提供剩余额度查询；免费池随时变化；
不可用模型不会提供给客户端。
"""

import argparse
import contextlib
import copy
import csv
import hashlib
import hmac
import io
import json
import os
import secrets
import shutil
import socket
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import asdict, dataclass, field, fields, replace
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Callable, Dict, Iterable, List, Optional, Tuple, Union

__version__ = "1.0.0"

# ---------------------------------------------------------------------------
# 常量
# ---------------------------------------------------------------------------

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 41980
DEFAULT_CATALOG_URL = "https://models.dev/api.json"
DEFAULT_PROBE_CONCURRENCY = 2
DEFAULT_SUCCESS_TTL_MINUTES = 360
DEFAULT_LOAD_TTL_MINUTES = 10
DEFAULT_REQUEST_TIMEOUT = 60
DEFAULT_MAX_BODY_BYTES = 8 * 1024 * 1024
DEFAULT_MAX_INFLIGHT = 4

OC_CLIENT_PREFIX = "OC · "
UPSTREAM_ID_PREFIX = "opencode/"
MANAGED_MARKER = "_oc_ferry"
ENVELOPE_KEY = "oc_ferry_envelope"
PROBE_FILE_TEMPLATE = "/external/probe-{token}.txt"
COST_KEYS = ("input", "output", "cache_read", "cache_write")

# 四档状态（面向用户 / 面向 Agent）
STATUS_AVAILABLE = "available"
STATUS_CHAT_ONLY = "chat_only"
STATUS_RATE_LIMITED = "rate_limited"
STATUS_OFFLINE = "offline"
STATUS_UNKNOWN = "unknown"
CLIENT_VISIBLE_STATUSES = (STATUS_AVAILABLE, STATUS_CHAT_ONLY)

# 体检判定码（前四个忠实移植自上游 src/probe.js，后四个是手机端运输层分级）
VERDICT_OK = "ok"
VERDICT_NO_ACTION = "no_action"
VERDICT_PROBE_MISMATCH = "probe_mismatch"
VERDICT_INVALID_MODEL_OUTPUT = "invalid_model_output"
VERDICT_INVALID_TOOL_CALL = "invalid_tool_call"
VERDICT_TIMEOUT = "timeout"
VERDICT_NETWORK = "network"
VERDICT_HTTP_ERROR = "http_error"
VERDICT_RATE_LIMITED = "rate_limited"
RETRYABLE_VERDICTS = frozenset((VERDICT_NO_ACTION, VERDICT_PROBE_MISMATCH))

LOAD_HEALTHY = "healthy"
LOAD_LIMITED = "limited"
LOAD_UNKNOWN = "unknown"

ERR_TIMEOUT = "timeout"
ERR_NETWORK = "network"

# 审批围栏判定类别
KIND_TEXT = "text"
KIND_TOOL_REQUEST = "tool_request"
KIND_EXTERNAL_TOOL_CALL = "external_tool_call"
KIND_REJECTED = "rejected"
KIND_NORMALIZED = "normalized"

# 本机动作关键词：命中即拒绝执行并转交为外部工具调用（服务端绝不执行）
LOCAL_ACTION_WORDS = frozenset((
    "shell", "bash", "exec", "execute", "command", "subprocess", "os.system",
    "file", "file_path", "read_file", "write_file", "readfile", "writefile",
    "localhost", "127.0.0.1", "0.0.0.0", "termux", "adb", "chmod",
    "rm -", "sudo ", "open(", "eval(", "system(",
))

# 能力指纹参与的字段：能力变化时自动失效缓存
CAPABILITY_KEYS = (
    "tool_call", "tools", "reasoning", "attachment", "temperature", "vision",
    "modalities", "limit", "knowledge", "open_weights", "interleaved",
    "context_window", "max_output", "structured_output",
)

INT_FIELDS = frozenset((
    "port", "probe_concurrency", "success_ttl_minutes", "load_ttl_minutes",
    "request_timeout", "max_body_bytes", "max_inflight",
))
BOOL_FIELDS = frozenset(("embed_api_key",))

# 手机端控制面板静态页文件名（与 oc_ferry.py 同目录）。
# 该页免 Bearer、免 Origin 检查，且只含静态 UI 骨架、零敏感数据；
# 所有模型数据与管理操作仍由 /v1/* 与 /admin/* 强制 Bearer 提供。
PANEL_FILENAME = "panel.html"


# ---------------------------------------------------------------------------
# 异常
# ---------------------------------------------------------------------------


class FerryError(Exception):
    """OC Ferry 基础异常。"""


class ConfigError(FerryError):
    """配置非法。"""


class ModelsFileError(FerryError):
    """目标模型配置文件无法安全读写（绝不覆盖损坏文件）。"""


class BodyTooLarge(FerryError):
    """请求体超过上限。"""


# ---------------------------------------------------------------------------
# 通用小工具
# ---------------------------------------------------------------------------


def _as_number(value: Any) -> Optional[float]:
    """把价格字段解析为 float；无法解析返回 None（bool 不当作数字）。"""
    if isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        return float(value)
    if isinstance(value, str):
        text = value.strip()
        if not text:
            return None
        try:
            return float(text)
        except ValueError:
            return None
    return None


def _as_float(value: Any) -> Optional[float]:
    """把缓存里的时间戳解析为 float；无法解析返回 None。"""
    if isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        return float(value)
    if isinstance(value, str):
        try:
            return float(value)
        except ValueError:
            return None
    return None


def _canonical(value: Any) -> str:
    """稳定序列化，用于「内容没变就不落盘」的语义比较。"""
    return json.dumps(value, sort_keys=True, ensure_ascii=False,
                      separators=(",", ":"), default=str)


def _is_http_url(url: str) -> bool:
    """校验 URL 是带主机的 http/https 地址。"""
    if not isinstance(url, str):
        return False
    parts = urllib.parse.urlparse(url)
    return parts.scheme in ("http", "https") and bool(parts.netloc)


def _join_url(base: str, path: str) -> str:
    """拼接上游 base_url 与路径，避免重复斜杠。"""
    return base.rstrip("/") + "/" + path.lstrip("/")


def _default_data_dir() -> str:
    """默认数据目录：环境变量 > HOME/.oc-ferry > 临时目录兜底。"""
    env = os.environ.get("OC_FERRY_DATA_DIR", "")
    if env:
        return env
    home = os.path.expanduser("~")
    if home and home != "~":
        return os.path.join(home, ".oc-ferry")
    return os.path.join(tempfile.gettempdir(), "oc-ferry")


def _strip_code_fence(text: str) -> str:
    """去掉 ```json ... ``` 包裹，便于解析模型输出的 JSON 信封。"""
    lines = text.strip().splitlines()
    if lines and lines[0].strip().startswith("```"):
        lines = lines[1:]
    if lines and lines[-1].strip() == "```":
        lines = lines[:-1]
    return "\n".join(lines).strip()


def _coerce_arguments(value: Any) -> Dict[str, Any]:
    """把 tool_call.arguments（JSON 字符串或 dict）规范成 dict。"""
    if isinstance(value, dict):
        return value
    if isinstance(value, str):
        try:
            parsed = json.loads(value)
        except ValueError:
            return {"value": value}
        if isinstance(parsed, dict):
            return parsed
        return {"value": value}
    return {}


# ---------------------------------------------------------------------------
# 配置
# ---------------------------------------------------------------------------


@dataclass
class FerryConfig:
    """OC Ferry 运行配置。

    字段全部带默认值，便于 dataclass 在 Python 3.8 下直接实例化；
    ``resolved_paths()`` 负责把空字符串路径填充为 data_dir 下的默认路径。
    """

    data_dir: str = ""
    port: int = DEFAULT_PORT
    host: str = DEFAULT_HOST
    upstream_base_url: str = ""
    upstream_api_key: str = ""
    catalog_url: str = DEFAULT_CATALOG_URL
    catalog_file: str = ""
    probe_concurrency: int = DEFAULT_PROBE_CONCURRENCY
    success_ttl_minutes: int = DEFAULT_SUCCESS_TTL_MINUTES
    load_ttl_minutes: int = DEFAULT_LOAD_TTL_MINUTES
    models_file: str = ""
    api_key_file: str = ""
    api_key: str = ""
    request_timeout: int = DEFAULT_REQUEST_TIMEOUT
    max_body_bytes: int = DEFAULT_MAX_BODY_BYTES
    max_inflight: int = DEFAULT_MAX_INFLIGHT
    embed_api_key: bool = True
    upstream_model_map: Dict[str, str] = field(default_factory=dict)
    cache_file: str = ""
    state_file: str = ""
    selection_file: str = ""

    @classmethod
    def from_dict(cls, raw: Dict[str, Any]) -> "FerryConfig":
        """从 JSON 配置字典构造，忽略未知键并做基础类型强转。

        Args:
            raw: 已解析的配置字典。

        Returns:
            FerryConfig 实例（路径尚未解析）。
        """
        known = set(item.name for item in fields(cls))
        kwargs: Dict[str, Any] = {}
        for key, value in raw.items():
            if key not in known:
                continue
            if key == "upstream_model_map":
                kwargs[key] = dict(value) if isinstance(value, dict) else {}
            elif key in INT_FIELDS:
                try:
                    kwargs[key] = int(value)
                except (TypeError, ValueError):
                    continue
            elif key in BOOL_FIELDS:
                kwargs[key] = bool(value)
            else:
                kwargs[key] = "" if value is None else str(value)
        return cls(**kwargs)

    def resolved_paths(self) -> "FerryConfig":
        """返回填充默认路径后的新配置（不修改自身）。"""
        data = os.path.abspath(self.data_dir or _default_data_dir())
        return replace(
            self,
            data_dir=data,
            models_file=self.models_file or os.path.join(data, "models.json"),
            api_key_file=self.api_key_file or os.path.join(data, "api-key"),
            cache_file=self.cache_file or os.path.join(data, "probe_cache.json"),
            state_file=self.state_file or os.path.join(data, "catalog_snapshot.json"),
            selection_file=self.selection_file or os.path.join(data, "selection.json"),
        )

    def validate(self) -> None:
        """校验安全红线与数值范围；失败抛 :class:`ConfigError`。

        注意：``host`` 被强制为 127.0.0.1，避免任何非回环监听；
        ``upstream_base_url`` 允许为空（doctor/status 等纯逻辑命令不需要它）。
        """
        if self.host != DEFAULT_HOST:
            raise ConfigError("host must be exactly 127.0.0.1 (loopback only)")
        if not (1 <= self.port <= 65535):
            raise ConfigError("port must be within 1..65535")
        if self.request_timeout <= 0:
            raise ConfigError("request_timeout must be positive")
        if self.probe_concurrency < 1:
            raise ConfigError("probe_concurrency must be >= 1")
        if self.max_inflight < 1:
            raise ConfigError("max_inflight must be >= 1")
        if self.max_body_bytes < 1024:
            raise ConfigError("max_body_bytes must be >= 1024")
        if self.success_ttl_minutes <= 0 or self.load_ttl_minutes <= 0:
            raise ConfigError("TTL values must be positive")
        if self.upstream_base_url and not _is_http_url(self.upstream_base_url):
            raise ConfigError("upstream_base_url must be an http(s) URL")
        if self.catalog_url and not _is_http_url(self.catalog_url):
            raise ConfigError("catalog_url must be an http(s) URL")


def _default_config_path() -> str:
    """默认配置文件路径：与脚本同目录的 config.json。"""
    return os.path.join(os.path.dirname(os.path.abspath(__file__)), "config.json")


def load_config(path: Optional[str] = None, data_dir_override: str = "") -> FerryConfig:
    """加载配置并解析路径。

    Args:
        path: 显式配置文件路径；为 None 时尝试同目录 config.json，
              不存在则使用内置默认值。
        data_dir_override: 命令行 ``--data-dir``，优先级最高。

    Returns:
        已校验、路径已解析的 FerryConfig。

    Raises:
        ConfigError: 配置非法。
        OSError: 文件读取失败。
        ValueError: JSON 非法。
    """
    chosen = path or ""
    raw: Dict[str, Any] = {}
    if not chosen:
        candidate = _default_config_path()
        if os.path.isfile(candidate):
            chosen = candidate
    if chosen:
        with open(chosen, "r", encoding="utf-8") as handle:
            loaded = json.load(handle)
        if not isinstance(loaded, dict):
            raise ConfigError("config root must be a JSON object")
        raw = loaded
    config = FerryConfig.from_dict(raw)
    if data_dir_override:
        config.data_dir = data_dir_override
    config = config.resolved_paths()
    config.validate()
    return config


# ---------------------------------------------------------------------------
# 可注入 HTTP 层（离线测试友好）
# ---------------------------------------------------------------------------


@dataclass
class HttpRequest:
    """一次上游 HTTP 调用的描述（注入点的主要入参）。"""

    method: str = "GET"
    url: str = ""
    headers: Dict[str, str] = field(default_factory=dict)
    body: bytes = b""
    timeout: int = DEFAULT_REQUEST_TIMEOUT
    stream: bool = False


@dataclass
class HttpResponse:
    """一次上游 HTTP 调用的结果。

    ``error`` 非空表示运输层失败（timeout / network），此时 status 通常为 0；
    ``chunks`` 非空表示流式响应，逐块透传给客户端。
    """

    status: int = 200
    headers: Dict[str, str] = field(default_factory=dict)
    body: bytes = b""
    chunks: Optional[Iterable[bytes]] = None
    error: str = ""


HttpCall = Callable[[HttpRequest], HttpResponse]


def urllib_http_call(request: HttpRequest) -> HttpResponse:
    """默认 HTTP 实现（urllib，标准库）。

    捕获超时/网络错误并写入 ``error`` 字段，使上层的重试策略可以纯逻辑地
    被离线测试覆盖。流式响应返回惰性生成器，生成器耗尽后自动关闭连接。

    Args:
        request: 请求描述。

    Returns:
        HttpResponse；任何情况下都不抛网络异常。
    """
    method = request.method.upper()
    data = request.body if method in ("POST", "PUT", "PATCH") else None
    req = urllib.request.Request(request.url, data=data, method=method)
    for key, value in request.headers.items():
        req.add_header(key, value)
    # 部分公开目录 / 网关会拒绝 urllib 默认的 Python-urllib UA（返回 403），
    # 因此统一补一个可识别的 UA；调用方显式设置时以其为准。
    if not any(key.lower() == "user-agent" for key in request.headers):
        req.add_header("User-Agent", "oc-ferry/%s" % __version__)
    try:
        resp = urllib.request.urlopen(req, timeout=request.timeout)
    except urllib.error.HTTPError as exc:
        try:
            body = exc.read()
        except OSError:
            body = b""
        headers: Dict[str, str] = {}
        if exc.headers is not None:
            headers = dict((k.lower(), str(v)) for k, v in exc.headers.items())
        with contextlib.suppress(OSError):
            exc.close()
        return HttpResponse(status=int(exc.code), headers=headers, body=body)
    except urllib.error.URLError as exc:
        reason = str(getattr(exc, "reason", exc))
        inner = getattr(exc, "reason", None)
        if "timed out" in reason.lower() or "timeout" in reason.lower() \
                or isinstance(inner, socket.timeout):
            return HttpResponse(status=0, error="timeout: %s" % reason)
        return HttpResponse(status=0, error="network: %s" % reason)
    except socket.timeout:
        return HttpResponse(status=0, error="timeout: socket timed out")
    except OSError as exc:
        return HttpResponse(status=0, error="network: %s" % exc)

    headers = dict((k.lower(), str(v)) for k, v in resp.getheaders())
    status = int(resp.getcode() or 0)
    if request.stream:
        def _chunks() -> Iterable[bytes]:
            try:
                while True:
                    line = resp.readline()
                    if not line:
                        break
                    yield line
            finally:
                with contextlib.suppress(OSError):
                    resp.close()
        return HttpResponse(status=status, headers=headers, chunks=_chunks())
    try:
        body = resp.read()
    finally:
        with contextlib.suppress(OSError):
            resp.close()
    return HttpResponse(status=status, headers=headers, body=body)


def _upstream_headers(config: FerryConfig, extra: Optional[Dict[str, str]] = None) -> Dict[str, str]:
    """构造上游请求头（注入用户配置的 upstream_api_key）。"""
    headers: Dict[str, str] = {
        "Content-Type": "application/json",
        "Accept": "application/json",
    }
    if config.upstream_api_key:
        headers["Authorization"] = "Bearer %s" % config.upstream_api_key
    if extra:
        headers.update(extra)
    return headers


def _upstream_model_for(model: Dict[str, Any], config: FerryConfig) -> str:
    """把目录模型映射成传给 upstream_base_url 的 model id。

    优先使用 ``upstream_model_map`` 里的显式覆盖；否则剥掉 ``opencode/``
    前缀，使用记录的 ``upstream_model``（形如 ``<provider>/<model>``）。
    """
    model_id = str(model.get("id", ""))
    override = config.upstream_model_map.get(model_id)
    if isinstance(override, str) and override:
        return override
    bare = model_id[len(UPSTREAM_ID_PREFIX):] if model_id.startswith(UPSTREAM_ID_PREFIX) else model_id
    return str(model.get("upstream_model") or bare)


# ---------------------------------------------------------------------------
# 链路 1：免费模型发现
# ---------------------------------------------------------------------------


def _looks_like_model(value: Any) -> bool:
    """判断一个 dict 是否像模型记录（含 cost 或价格字段）。"""
    if not isinstance(value, dict):
        return False
    if isinstance(value.get("cost"), dict):
        return True
    return any(key in value for key in COST_KEYS)


def _iter_catalog_models(catalog: Dict[str, Any]) -> Iterable[Tuple[str, str, Dict[str, Any]]]:
    """遍历目录，产出 ``(provider_id, model_key, model)``。

    兼容 models.dev/api.json 的 ``provider -> models -> key -> model`` 结构，
    以及扁平的 ``id -> model`` 结构。
    """
    if not isinstance(catalog, dict):
        return
    for provider_key, provider in catalog.items():
        if not isinstance(provider, dict):
            continue
        nested = provider.get("models")
        if isinstance(nested, dict):
            provider_id = str(provider.get("id") or provider_key)
            for model_key, model in nested.items():
                if isinstance(model, dict):
                    yield provider_id, str(model_key), model
            continue
        if _looks_like_model(provider):
            yield str(provider_key), str(provider.get("id") or provider_key), provider


def _extract_cost(model: Dict[str, Any]) -> Optional[Dict[str, float]]:
    """提取四个价格字段；优先读 ``cost`` 子对象，其次读顶层字段。

    缺失的子字段按 0 处理；若完全没有可解析的价格信息则返回 None，
    避免把没有价格元数据的条目误判为免费。
    """
    source = model.get("cost") if isinstance(model.get("cost"), dict) else None
    prices: Dict[str, float] = {}
    found = False
    for key in COST_KEYS:
        raw: Any = None
        if source is not None and key in source:
            raw = source[key]
        elif key in model:
            raw = model[key]
        number = _as_number(raw)
        if number is None:
            prices[key] = 0.0
        else:
            prices[key] = number
            found = True
    return prices if found else None


def _is_deprecated(model: Dict[str, Any]) -> bool:
    """声明 deprecated 的模型直接排除（bool / 时间戳字符串 / status 均识别）。"""
    if bool(model.get("deprecated")):
        return True
    status = model.get("status")
    if isinstance(status, str) and status.strip().lower() == "deprecated":
        return True
    return False


def _capability_fingerprint(model: Dict[str, Any]) -> str:
    """按能力字段计算稳定指纹；能力变化会让体检缓存自动失效。"""
    parts: Dict[str, Any] = {}
    for key in CAPABILITY_KEYS:
        if key in model:
            parts[key] = model[key]
    for key in COST_KEYS:
        if key in model:
            parts["price_" + key] = model[key]
    canonical = _canonical(parts)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()[:16]


def discover_free_models(catalog: Dict[str, Any]) -> List[Dict[str, Any]]:
    """从模型目录中发现免费模型。

    判定规则（严格对齐上游）：
    - **只按价格字段**：input / output / cache_read / cache_write 全为 0；
    - **名字不作数**：模型名是否含 "free" 完全不参与判定（本函数从不读名字
      做免费判断，名字仅用于展示）；
    - **deprecated 直接排除**。

    Args:
        catalog: 已解析的 models.dev/api.json（或用户自填目录）。

    Returns:
        规范化模型记录列表，按 ``id`` 稳定排序。每条含 id（``opencode/``
        前缀）、upstream_model、provider、name、fingerprint、cost。
    """
    results: List[Dict[str, Any]] = []
    for provider_id, model_key, model in _iter_catalog_models(catalog):
        if _is_deprecated(model):
            continue
        cost = _extract_cost(model)
        if cost is None:
            continue
        if any(cost[key] != 0.0 for key in COST_KEYS):
            continue
        model_id = "%s%s/%s" % (UPSTREAM_ID_PREFIX, provider_id, model_key)
        results.append({
            "id": model_id,
            "upstream_model": "%s/%s" % (provider_id, model_key),
            "provider": provider_id,
            "name": str(model.get("name") or model_key),
            "fingerprint": _capability_fingerprint(model),
            "cost": cost,
        })
    results.sort(key=lambda item: str(item["id"]))
    return results


# ---------------------------------------------------------------------------
# 链路 2：体检
# ---------------------------------------------------------------------------


def _probe_tools_definition() -> List[Dict[str, Any]]:
    """返回 5 个体检工具定义（每次深拷贝，避免共享可变全局状态）。"""
    return copy.deepcopy([
        {
            "type": "function",
            "function": {
                "name": "Read",
                "description": "Read a file at file_path and return its contents.",
                "parameters": {
                    "type": "object",
                    "properties": {"file_path": {"type": "string", "description": "Absolute file path."}},
                    "required": ["file_path"],
                },
            },
        },
        {
            "type": "function",
            "function": {
                "name": "Write",
                "description": "Write content to a file at file_path.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "file_path": {"type": "string"},
                        "content": {"type": "string"},
                    },
                    "required": ["file_path", "content"],
                },
            },
        },
        {
            "type": "function",
            "function": {
                "name": "Bash",
                "description": "Describe a command to run (never executed by the ferry host).",
                "parameters": {
                    "type": "object",
                    "properties": {"command": {"type": "string"}},
                    "required": ["command"],
                },
            },
        },
        {
            "type": "function",
            "function": {
                "name": "Glob",
                "description": "Match file paths by a glob pattern.",
                "parameters": {
                    "type": "object",
                    "properties": {"pattern": {"type": "string"}},
                    "required": ["pattern"],
                },
            },
        },
        {
            "type": "function",
            "function": {
                "name": "WebSearch",
                "description": "Search the web for a query.",
                "parameters": {
                    "type": "object",
                    "properties": {"query": {"type": "string"}},
                    "required": ["query"],
                },
            },
        },
    ])


def build_probe_body(model_id: str, token: str) -> Dict[str, Any]:
    """构造体检请求体：5 工具、随机 token 文件、``parallel_tool_calls: false``。

    Args:
        model_id: 上游模型 id（保持 ``opencode/`` 前缀）。
        token: 随机 token，会出现在要求读取的文件名里。

    Returns:
        OpenAI /chat/completions 兼容的请求体字典。
    """
    probe_path = PROBE_FILE_TEMPLATE.format(token=token)
    system = (
        "OC Ferry capability probe. Use exactly ONE tool call (Read) to read the "
        "requested file and do not call any other tool. The ferry host NEVER "
        "executes local actions: shell commands and file writes are forbidden, "
        "and any action intent must be expressed as a JSON envelope "
        "('%s') so the client alone decides whether to execute it." % ENVELOPE_KEY
    )
    user = (
        "Use the Read tool exactly once to read the file %s and report its contents. "
        "Do not call any other tool." % probe_path
    )
    return {
        "model": model_id,
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": user},
        ],
        "tools": _probe_tools_definition(),
        "tool_choice": "auto",
        "parallel_tool_calls": False,
        "temperature": 0,
        "max_tokens": 512,
        "stream": False,
    }


@dataclass
class ProbeVerdict:
    """一次体检响应的判定结果。

    Attributes:
        code: 判定码（见模块常量）。
        status: 该判定对应的四档状态。
        retryable: 是否允许重试（仅 no_action / probe_mismatch）。
        tools_enabled: 是否给客户端开放工具（仅对话降级时为 False）。
        reason: 可读原因。
        http_status_hint: 桌面端 probe.js 的对应 HTTP 语义；no_action 为 502。
    """

    code: str = VERDICT_INVALID_MODEL_OUTPUT
    status: str = STATUS_CHAT_ONLY
    retryable: bool = False
    tools_enabled: bool = False
    reason: str = ""
    http_status_hint: int = 200


def judge_probe(response: Any, token: str) -> ProbeVerdict:
    """判定体检响应（忠实移植上游 ``src/probe.js`` 的四级判定码）。

    - 响应整体结构不是 chat completion → ``invalid_model_output``（仅对话降级）
    - tool_calls 条目结构/arguments 非法 → ``invalid_tool_call``（仅对话降级）
    - 无 tool_calls（只回文本）→ ``no_action``（桌面 probe 端点实际抛 502，
      手机端降级为「可用 · 仅对话」并关闭工具）
    - 调用数 ≠ 1 / 名字 ≠ Read / file_path 不含 token → ``probe_mismatch``
    - 恰好 1 个 Read 且 file_path 含 token → ``ok``

    Args:
        response: 已解析的上游 JSON 响应。
        token: 本次体检的随机 token。

    Returns:
        ProbeVerdict。
    """
    def invalid_output(reason: str) -> ProbeVerdict:
        return ProbeVerdict(code=VERDICT_INVALID_MODEL_OUTPUT, status=STATUS_CHAT_ONLY,
                            retryable=False, tools_enabled=False, reason=reason)

    def invalid_call(reason: str) -> ProbeVerdict:
        return ProbeVerdict(code=VERDICT_INVALID_TOOL_CALL, status=STATUS_CHAT_ONLY,
                            retryable=False, tools_enabled=False, reason=reason)

    def mismatch(reason: str) -> ProbeVerdict:
        return ProbeVerdict(code=VERDICT_PROBE_MISMATCH, status=STATUS_OFFLINE,
                            retryable=True, tools_enabled=False, reason=reason)

    if not isinstance(response, dict):
        return invalid_output("response is not a JSON object")
    choices = response.get("choices")
    if not isinstance(choices, list) or not choices:
        return invalid_output("choices is missing or empty")
    first = choices[0]
    if not isinstance(first, dict):
        return invalid_output("choices[0] is not an object")
    message = first.get("message")
    if not isinstance(message, dict):
        return invalid_output("choices[0].message is missing")
    tool_calls = message.get("tool_calls")
    if tool_calls is None or tool_calls == [] or tool_calls == "":
        return ProbeVerdict(
            code=VERDICT_NO_ACTION,
            status=STATUS_CHAT_ONLY,
            retryable=True,
            tools_enabled=False,
            reason="model replied with text only (no tool_calls); desktop probe.js returns 502",
            http_status_hint=502,
        )
    if not isinstance(tool_calls, list):
        return invalid_call("tool_calls is not a list")

    parsed: List[Tuple[str, Dict[str, Any]]] = []
    for call in tool_calls:
        if not isinstance(call, dict):
            return invalid_call("tool_call entry is not an object")
        function = call.get("function")
        if not isinstance(function, dict):
            return invalid_call("tool_call.function is missing")
        name = function.get("name")
        if not isinstance(name, str) or not name:
            return invalid_call("tool_call.function.name is missing")
        raw_arguments = function.get("arguments", {})
        if isinstance(raw_arguments, str):
            try:
                arguments = json.loads(raw_arguments) if raw_arguments.strip() else {}
            except ValueError:
                return invalid_call("tool_call.function.arguments is not valid JSON")
        elif isinstance(raw_arguments, dict):
            arguments = raw_arguments
        else:
            return invalid_call("tool_call.function.arguments has unsupported type")
        if not isinstance(arguments, dict):
            return invalid_call("tool_call.function.arguments is not an object")
        parsed.append((name, arguments))

    if len(parsed) != 1:
        return mismatch("expected exactly one tool call, got %d" % len(parsed))
    name, arguments = parsed[0]
    if name != "Read":
        return mismatch("expected the Read tool, got %s" % name)
    file_path = arguments.get("file_path")
    if not isinstance(file_path, str) or token not in file_path:
        return mismatch("file_path does not contain the probe token")
    return ProbeVerdict(code=VERDICT_OK, status=STATUS_AVAILABLE, retryable=False,
                        tools_enabled=True, reason="valid single Read action",
                        http_status_hint=200)


@dataclass
class ProbeResult:
    """一次完整体检（含重试与运输层分级）的结果。"""

    model_id: str = ""
    status: str = STATUS_UNKNOWN
    verdict: str = ""
    attempts: int = 0
    tools_enabled: bool = False
    reason: str = ""
    load_state: str = LOAD_UNKNOWN
    token: str = ""
    checked_at: float = 0.0
    fingerprint: str = ""


def _classify_probe_http(response: HttpResponse) -> Optional[Tuple[str, str, str, str]]:
    """把运输层结果映射成 ``(status, verdict, reason, load_state)``。

    返回 None 表示 200 且需要继续解析响应体。超时/网络/HTTP 错误一律**不重试**。
    """
    if response.error:
        lowered = response.error.lower()
        if lowered.startswith(ERR_TIMEOUT):
            return (STATUS_OFFLINE, VERDICT_TIMEOUT,
                    "probe timed out; not retried", LOAD_UNKNOWN)
        return (STATUS_OFFLINE, VERDICT_NETWORK,
                "network error; not retried: %s" % response.error, LOAD_UNKNOWN)
    if response.status == 429 or response.status == 503:
        return (STATUS_RATE_LIMITED, VERDICT_RATE_LIMITED,
                "upstream rate limited (HTTP %d); not retried" % response.status,
                LOAD_LIMITED)
    if response.status >= 500:
        return (STATUS_OFFLINE, VERDICT_HTTP_ERROR,
                "upstream error HTTP %d; not retried" % response.status, LOAD_UNKNOWN)
    if response.status < 200 or response.status >= 300:
        return (STATUS_OFFLINE, VERDICT_HTTP_ERROR,
                "unexpected HTTP %d; not retried" % response.status, LOAD_UNKNOWN)
    return None


def probe_model(model_id: str,
                config: FerryConfig,
                http_call: HttpCall,
                token: str = "",
                fingerprint: str = "",
                now_fn: Callable[[], float] = time.time) -> ProbeResult:
    """对单个模型执行体检。

    重试策略（严格对齐需求）：
    - ``no_action`` / ``probe_mismatch``（语义不符与无动作）→ 重试 1 次；
    - 超时、网络错误、HTTP 错误、格式不支持（invalid_*）→ 一律不重试；
    - 任一次得到 ``ok`` 立即判定 ``available``；
    - 两次都用完仍 no_action → ``chat_only``（可用·仅对话，关闭工具）；
      仍 probe_mismatch → ``offline``（下线，不提供给客户端）。

    Args:
        model_id: 上游模型 id。
        config: 运行配置。
        http_call: 可注入的 HTTP 调用函数（离线测试可完全替身）。
        token: 可选的固定 token（测试用）；为空则随机生成。
        fingerprint: 能力指纹，写入结果供缓存使用。
        now_fn: 可注入的时间函数（测试用）。

    Returns:
        ProbeResult。
    """
    probe_token = token or secrets.token_hex(8)
    url = _join_url(config.upstream_base_url, "/chat/completions")
    headers = _upstream_headers(config)
    last_verdict: Optional[ProbeVerdict] = None
    attempts = 0
    reason = ""

    def make_result(status: str, verdict: str, tools: bool, why: str,
                    load_state: str, used_attempts: int) -> ProbeResult:
        return ProbeResult(
            model_id=model_id, status=status, verdict=verdict,
            attempts=used_attempts, tools_enabled=tools, reason=why,
            load_state=load_state, token=probe_token,
            checked_at=now_fn(), fingerprint=fingerprint,
        )

    for attempt in range(2):
        attempts = attempt + 1
        body = build_probe_body(model_id, probe_token)
        raw = json.dumps(body, ensure_ascii=False).encode("utf-8")
        request = HttpRequest(method="POST", url=url, headers=headers,
                              body=raw, timeout=config.request_timeout, stream=False)
        try:
            response = http_call(request)
        except Exception as exc:  # 注入式 HTTP 边界统一降级，不让异常穿透
            return make_result(STATUS_OFFLINE, VERDICT_NETWORK, False,
                               "http_call raised; not retried: %s" % exc,
                               LOAD_UNKNOWN, attempts)
        classified = _classify_probe_http(response)
        if classified is not None:
            status, verdict, why, load_state = classified
            return make_result(status, verdict, False, why, load_state, attempts)
        try:
            payload = json.loads(response.body.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            verdict = ProbeVerdict(code=VERDICT_INVALID_MODEL_OUTPUT,
                                   status=STATUS_CHAT_ONLY, retryable=False,
                                   tools_enabled=False,
                                   reason="upstream body is not valid JSON; not retried")
        else:
            verdict = judge_probe(payload, probe_token)
        last_verdict = verdict
        reason = verdict.reason
        if verdict.code == VERDICT_OK:
            return make_result(STATUS_AVAILABLE, VERDICT_OK, True,
                               verdict.reason, LOAD_HEALTHY, attempts)
        if not verdict.retryable:
            return make_result(verdict.status, verdict.code, False,
                               verdict.reason, LOAD_HEALTHY, attempts)
        # retryable：继续下一轮（最多 1 次重试）

    final_verdict = last_verdict.code if last_verdict is not None else VERDICT_PROBE_MISMATCH
    final_status = last_verdict.status if last_verdict is not None else STATUS_OFFLINE
    return make_result(final_status, final_verdict, False,
                       "after %d attempts: %s" % (attempts, reason),
                       LOAD_HEALTHY, attempts)


# ---------------------------------------------------------------------------
# 链路 3：缓存
# ---------------------------------------------------------------------------


class ProbeCache:
    """体检结果缓存：JSON 持久化、双 TTL、能力指纹失效、跨进程/跨重启可用。

    - 成功判定（available / chat_only / offline）保留 ``success_ttl_minutes``；
    - 限流判定与当时负载状态只保留 ``load_ttl_minutes``；
    - ``get`` 会校验模型 ID + 能力指纹，指纹变化即失效；
    - 每次读写都重新读盘 + 原子替换，多进程不会写坏文件；
    - 损坏文件安全降级为空缓存，并在下一次写入前备份为 ``.corrupt-<ts>``。
    """

    def __init__(self, path: str,
                 success_ttl_minutes: int = DEFAULT_SUCCESS_TTL_MINUTES,
                 load_ttl_minutes: int = DEFAULT_LOAD_TTL_MINUTES) -> None:
        self.path = path
        self.success_ttl = float(success_ttl_minutes) * 60.0
        self.load_ttl = float(load_ttl_minutes) * 60.0
        self.corrupted = False
        self._lock = threading.RLock()

    def _read(self) -> Dict[str, Dict[str, Any]]:
        """从磁盘读取条目；任何损坏都安全降级为空字典。"""
        if not os.path.isfile(self.path):
            return {}
        try:
            with open(self.path, "r", encoding="utf-8") as handle:
                doc = json.load(handle)
            if not isinstance(doc, dict) or not isinstance(doc.get("entries"), dict):
                self.corrupted = True
                return {}
            entries: Dict[str, Dict[str, Any]] = {}
            for key, value in doc["entries"].items():
                if isinstance(value, dict):
                    entries[str(key)] = value
            self.corrupted = False
            return entries
        except (OSError, ValueError, UnicodeError):
            self.corrupted = True
            return {}

    def _write(self, entries: Dict[str, Dict[str, Any]]) -> None:
        """原子写回缓存；损坏文件先备份再覆盖。"""
        parent = os.path.dirname(os.path.abspath(self.path))
        os.makedirs(parent, exist_ok=True)
        if os.path.isfile(self.path) and self.corrupted:
            with contextlib.suppress(OSError):
                shutil.copy2(self.path, "%s.corrupt-%d" % (self.path, int(time.time())))
        text = json.dumps({"schema": 1, "entries": entries},
                          sort_keys=True, ensure_ascii=False, indent=2) + "\n"
        _atomic_write_text(self.path, text, mode=0o600)

    def get(self, model_id: str, fingerprint: str,
            now: Optional[float] = None) -> Optional[Dict[str, Any]]:
        """取有效判定；指纹不符、判定过期、限流过期均返回 None。"""
        current = time.time() if now is None else now
        with self._lock:
            entry = self._read().get(model_id)
            if not isinstance(entry, dict):
                return None
            if str(entry.get("fingerprint", "")) != str(fingerprint):
                return None
            checked = _as_float(entry.get("checked_at"))
            if checked is None or current < checked or (current - checked) > self.success_ttl:
                return None
            if str(entry.get("status", "")) == STATUS_RATE_LIMITED:
                load_checked = _as_float(entry.get("load_checked_at"))
                if load_checked is None or (current - load_checked) > self.load_ttl:
                    return None
            return dict(entry)

    def put(self, result: ProbeResult, fingerprint: str) -> None:
        """写入一次体检判定（同时刷新负载时间戳）。"""
        checked = result.checked_at or time.time()
        with self._lock:
            entries = self._read()
            entries[result.model_id] = {
                "model_id": result.model_id,
                "fingerprint": fingerprint,
                "status": result.status,
                "verdict": result.verdict,
                "tools_enabled": bool(result.tools_enabled),
                "reason": result.reason,
                "checked_at": checked,
                "load_state": result.load_state,
                "load_checked_at": checked,
            }
            self._write(entries)

    def put_load(self, model_id: str, load_state: str,
                 now: Optional[float] = None) -> None:
        """只刷新负载状态（10 分钟 TTL），不改判定的 6 小时有效期。"""
        current = time.time() if now is None else now
        with self._lock:
            entries = self._read()
            entry = entries.get(model_id)
            if not isinstance(entry, dict):
                return
            entry["load_state"] = load_state
            entry["load_checked_at"] = current
            self._write(entries)

    def get_load(self, model_id: str, now: Optional[float] = None) -> Optional[str]:
        """取 10 分钟内有效的负载状态；过期返回 None。"""
        current = time.time() if now is None else now
        with self._lock:
            entry = self._read().get(model_id)
            if not isinstance(entry, dict):
                return None
            checked = _as_float(entry.get("load_checked_at"))
            if checked is None or current < checked or (current - checked) > self.load_ttl:
                return None
            return str(entry.get("load_state", LOAD_UNKNOWN))

    def all_entries(self) -> Dict[str, Dict[str, Any]]:
        """返回全部缓存条目（供 status 命令只读展示）。"""
        with self._lock:
            return dict((key, dict(value)) for key, value in self._read().items())


# ---------------------------------------------------------------------------
# 链路 4：安全写入（备份 / 加锁 / 原子替换 / 只动自己的条目）
# ---------------------------------------------------------------------------


class FileLock:
    """基于 ``O_CREAT|O_EXCL`` 的轻量跨进程文件锁（含 stale 清理）。"""

    def __init__(self, target_path: str, timeout: float = 10.0,
                 stale_seconds: float = 120.0) -> None:
        self.path = target_path + ".lock"
        self.timeout = float(timeout)
        self.stale_seconds = float(stale_seconds)
        self._held = False

    def _remove_if_stale(self) -> bool:
        """删除过期锁文件；成功返回 True。"""
        try:
            stat = os.stat(self.path)
            if time.time() - stat.st_mtime > self.stale_seconds:
                os.unlink(self.path)
                return True
        except OSError:
            return False
        return False

    def __enter__(self) -> "FileLock":
        parent = os.path.dirname(os.path.abspath(self.path))
        os.makedirs(parent, exist_ok=True)
        deadline = time.time() + self.timeout
        while True:
            try:
                fd = os.open(self.path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
                with os.fdopen(fd, "w", encoding="utf-8") as handle:
                    handle.write("%d %d\n" % (os.getpid(), int(time.time())))
                self._held = True
                return self
            except FileExistsError:
                if self._remove_if_stale():
                    continue
                if time.time() >= deadline:
                    raise TimeoutError("could not acquire lock: %s" % self.path)
                time.sleep(0.05)

    def __exit__(self, exc_type: Any, exc: Any, tb: Any) -> bool:
        if self._held:
            with contextlib.suppress(OSError):
                os.unlink(self.path)
            self._held = False
        return False


@dataclass
class WriteReport:
    """一次安全写入的结果摘要。"""

    path: str = ""
    changed: bool = False
    created: bool = False
    added: int = 0
    removed: int = 0
    kept: int = 0
    backup_path: str = ""
    reason: str = ""


def _atomic_write_text(path: str, text: str, mode: int = 0o600) -> None:
    """同目录临时文件 + fsync + ``os.replace`` 原子落盘。"""
    parent = os.path.dirname(os.path.abspath(path))
    os.makedirs(parent, exist_ok=True)
    fd, tmp_path = tempfile.mkstemp(prefix=".oc-ferry-tmp-", dir=parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(text)
            handle.flush()
            os.fsync(handle.fileno())
        with contextlib.suppress(OSError):
            os.chmod(tmp_path, mode)
        os.replace(tmp_path, path)
    except BaseException:
        with contextlib.suppress(OSError):
            os.unlink(tmp_path)
        raise


def _atomic_write_json(path: str, doc: Any, backup: bool,
                       exists: bool, report: WriteReport) -> None:
    """写前备份 + 原子替换 JSON 文档。"""
    if exists and backup:
        backup_path = path + ".oc-ferry.bak"
        shutil.copy2(path, backup_path)
        report.backup_path = backup_path
    text = json.dumps(doc, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    _atomic_write_text(path, text, mode=0o600)


def _load_models_doc(path: str) -> Dict[str, Any]:
    """读取目标模型配置文件并识别形态。

    支持裸数组 ``[...]`` 与对象 ``{"models": [...]}`` 两种形态；
    对象形态下会**原样保留其它顶层键**（用户手写配置绝不丢失）。
    文件损坏 / 结构不认识时抛 :class:`ModelsFileError`，绝不覆盖。
    """
    if not os.path.isfile(path):
        doc: Dict[str, Any] = {"models": []}
        return {
            "doc": doc, "models": doc["models"], "shape": "object",
            "raw": "", "exists": False, "add_models_key": False,
        }
    with open(path, "r", encoding="utf-8") as handle:
        raw = handle.read()
    try:
        doc = json.loads(raw)
    except ValueError as exc:
        raise ModelsFileError("models file is not valid JSON; refusing to overwrite: %s" % exc)
    if isinstance(doc, list):
        return {
            "doc": doc, "models": doc, "shape": "array",
            "raw": raw, "exists": True, "add_models_key": False,
        }
    if isinstance(doc, dict):
        models = doc.get("models")
        if models is None:
            return {
                "doc": doc, "models": [], "shape": "object",
                "raw": raw, "exists": True, "add_models_key": True,
            }
        if isinstance(models, list):
            return {
                "doc": doc, "models": models, "shape": "object",
                "raw": raw, "exists": True, "add_models_key": False,
            }
        raise ModelsFileError("'models' must be an array; refusing to overwrite")
    raise ModelsFileError("models file must be an array or object; refusing to overwrite")


def _is_managed(entry: Any) -> bool:
    """判断条目是否由 oc-ferry 打过标记（唯一可增删的判定依据）。"""
    if not isinstance(entry, dict):
        return False
    marker = entry.get(MANAGED_MARKER)
    return isinstance(marker, dict) and bool(marker.get("managed"))


def _merge_doc(state: Dict[str, Any], new_list: List[Dict[str, Any]]) -> Any:
    """按原形态构造写回文档；对象形态保留其它顶层键。"""
    if state["shape"] == "array":
        return new_list
    doc = copy.deepcopy(state["doc"])
    doc["models"] = new_list
    return doc


def merge_models_file(models_file: str, entries: List[Dict[str, Any]],
                      backup: bool = True, lock_timeout: float = 10.0) -> WriteReport:
    """把 oc-ferry 管理的条目合并进目标模型配置文件。

    安全保证：
    - 只删除/新增带 ``_oc_ferry.managed`` 标记的条目，用户手写条目原样保留；
    - 写前备份、文件锁、``os.replace`` 原子替换；
    - 语义内容没变化就不落盘（也不备份）；
    - 结构损坏或不认识时抛错，绝不覆盖。
    """
    report = WriteReport(path=models_file)
    path = os.path.abspath(models_file)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with FileLock(path, timeout=lock_timeout):
        state = _load_models_doc(path)
        before = _canonical(state["doc"])
        current = state["models"]
        user_entries = [item for item in current if not _is_managed(item)]
        old_managed = [item for item in current if _is_managed(item)]
        new_list = user_entries + [dict(item) for item in entries]
        new_doc = _merge_doc(state, new_list)
        report.kept = len(user_entries)
        report.removed = len(old_managed)
        report.added = len(entries)
        report.created = not state["exists"]
        if state["exists"] and before == _canonical(new_doc):
            report.reason = "content unchanged; no write performed"
            return report
        _atomic_write_json(path, new_doc, backup, state["exists"], report)
        report.changed = True
        report.reason = "merged %d managed entries (kept %d user entries)" % (
            len(entries), len(user_entries))
    return report


def cleanup_models_file(models_file: str, backup: bool = True,
                        lock_timeout: float = 10.0) -> WriteReport:
    """摘掉所有 oc-ferry 管理的条目（退出时清场），用户条目绝不动。"""
    report = WriteReport(path=models_file)
    path = os.path.abspath(models_file)
    if not os.path.isfile(path):
        report.reason = "models file does not exist; nothing to clean"
        return report
    with FileLock(path, timeout=lock_timeout):
        state = _load_models_doc(path)
        before = _canonical(state["doc"])
        current = state["models"]
        user_entries = [item for item in current if not _is_managed(item)]
        old_managed = [item for item in current if _is_managed(item)]
        report.kept = len(user_entries)
        report.removed = len(old_managed)
        if not old_managed:
            report.reason = "no managed entries; no write performed"
            return report
        new_doc = _merge_doc(state, user_entries)
        if before == _canonical(new_doc):
            report.reason = "content unchanged; no write performed"
            return report
        _atomic_write_json(path, new_doc, backup, state["exists"], report)
        report.changed = True
        report.reason = "removed %d managed entries (kept %d user entries)" % (
            len(old_managed), len(user_entries))
    return report


# ---------------------------------------------------------------------------
# 审批围栏：JSON 信封协议（只解析/转换，绝不执行）
# ---------------------------------------------------------------------------


@dataclass
class Envelope:
    """解码后的模型输出信封。"""

    accepted: bool = False
    kind: str = KIND_REJECTED
    payload: Dict[str, Any] = field(default_factory=dict)
    reason: str = ""


@dataclass
class FenceResult:
    """审批围栏对一条 assistant message 的裁决。"""

    allowed: bool = True
    kind: str = KIND_TEXT
    envelope: Dict[str, Any] = field(default_factory=dict)
    reason: str = ""
    local_actions_rejected: int = 0


class ApprovalFence:
    """审批围栏：模型只能用 JSON 信封表达「想做什么」。

    **本类不包含任何执行路径**：没有 subprocess、没有 os.system、没有任何
    文件写入或命令调用。任何命中本机动作关键词的意图一律被拒绝，并被
    **转交为外部工具调用**（``external_tool_call`` 信封），执行权始终留在
    客户端（Zafiro Agent）；服务端永不自行执行本机动作。
    """

    def decode(self, content: str) -> Envelope:
        """把模型输出解码为 JSON 信封；非信封内容判为拒绝。

        合法信封示例::

            {"oc_ferry_envelope": 1, "type": "tool_request",
             "tool": "search", "arguments": {"query": "..."}}
        """
        if not isinstance(content, str) or not content.strip():
            return Envelope(accepted=False, kind=KIND_REJECTED, reason="empty output")
        text = _strip_code_fence(content.strip())
        try:
            value = json.loads(text)
        except ValueError:
            return Envelope(accepted=False, kind=KIND_REJECTED,
                            payload={"raw": content},
                            reason="output is not a JSON envelope")
        if not isinstance(value, dict) or ENVELOPE_KEY not in value:
            return Envelope(accepted=False, kind=KIND_REJECTED,
                            payload={"raw": content},
                            reason="missing '%s' envelope key" % ENVELOPE_KEY)
        kind = str(value.get("type") or KIND_TEXT)
        return Envelope(accepted=True, kind=kind, payload=value, reason="valid envelope")

    def encode_text(self, text: str) -> str:
        """把纯文本规范化为合法 ``text`` 信封 JSON。"""
        return json.dumps({ENVELOPE_KEY: 1, "type": KIND_TEXT, "content": text},
                          ensure_ascii=False)

    def encode_external_tool_call(self, tool: str,
                                  arguments: Dict[str, Any], reason: str) -> str:
        """把被拒绝的本机动作转成外部工具调用信封 JSON。"""
        return json.dumps({
            ENVELOPE_KEY: 1,
            "type": KIND_EXTERNAL_TOOL_CALL,
            "tool": tool,
            "arguments": arguments,
            "reason": reason,
            "note": "Execution is delegated to the client; the server never runs local actions.",
        }, ensure_ascii=False)

    def is_local_action(self, tool: str, arguments: Dict[str, Any]) -> bool:
        """判断工具名/参数是否表达本机动作（命中关键词即视为本机动作）。"""
        haystack = (str(tool) + " " + _canonical(arguments)).lower()
        return any(word in haystack for word in LOCAL_ACTION_WORDS)

    def evaluate(self, message: Dict[str, Any]) -> FenceResult:
        """裁决一条 assistant message：解析信封 + 检测本机动作意图。

        - 本机动作（shell/file/exec/...）→ ``allowed=False``，转成
          ``external_tool_call`` 信封，并记录拒绝数量；
        - 普通客户端工具调用 → 允许转发（执行权仍在客户端）；
        - 非信封纯文本 → 规范化为 ``text`` 信封（模型只能用信封表达意图）。
        """
        content = message.get("content") if isinstance(message.get("content"), str) else ""
        raw_calls = message.get("tool_calls")
        tool_calls = raw_calls if isinstance(raw_calls, list) else []
        envelope = self.decode(content) if content else Envelope(
            accepted=False, kind=KIND_REJECTED, reason="no text content")

        requests: List[Dict[str, Any]] = []
        rejected = 0
        for call in tool_calls:
            if not isinstance(call, dict):
                continue
            function = call.get("function") if isinstance(call.get("function"), dict) else {}
            name = str(function.get("name") or call.get("name") or "")
            arguments = _coerce_arguments(function.get("arguments"))
            is_local = self.is_local_action(name, arguments)
            if is_local:
                rejected += 1
            requests.append({
                "tool": name,
                "arguments": arguments,
                "delegated": True,
                "blocked_local": is_local,
                "reason": ("local action rejected; delegated as external tool call"
                           if is_local else "client-side tool call"),
            })

        if envelope.accepted:
            tool = str(envelope.payload.get("tool") or "")
            raw_args = envelope.payload.get("arguments")
            args = raw_args if isinstance(raw_args, dict) else {}
            if tool and self.is_local_action(tool, args):
                rejected += 1
                requests.append({
                    "tool": tool,
                    "arguments": args,
                    "delegated": True,
                    "blocked_local": True,
                    "reason": "local action in envelope rejected; delegated to client",
                })

        if rejected > 0:
            return FenceResult(
                allowed=False,
                kind=KIND_EXTERNAL_TOOL_CALL,
                envelope={
                    ENVELOPE_KEY: 1,
                    "type": KIND_EXTERNAL_TOOL_CALL,
                    "requests": requests,
                    "reason": "local actions rejected; execution delegated to client",
                    "note": "The ferry server never executes local actions.",
                },
                reason="%d local action(s) rejected" % rejected,
                local_actions_rejected=rejected,
            )
        if requests:
            return FenceResult(
                allowed=True,
                kind=KIND_TOOL_REQUEST,
                envelope={ENVELOPE_KEY: 1, "type": KIND_TOOL_REQUEST,
                          "requests": requests},
                reason="client-side tool calls only",
            )
        if not envelope.accepted:
            return FenceResult(
                allowed=False,
                kind=KIND_NORMALIZED,
                envelope={ENVELOPE_KEY: 1, "type": KIND_TEXT, "content": content},
                reason="plain text normalized into a JSON envelope",
            )
        return FenceResult(allowed=True, kind=envelope.kind,
                           envelope=envelope.payload, reason="valid envelope")

    def rewrite(self, response: Dict[str, Any], result: FenceResult) -> Dict[str, Any]:
        """按裁决改写响应：剥离危险 tool_calls，替换为安全 JSON 信封。

        ``allowed=True`` 时原样返回（执行权在客户端，无需改写）。
        """
        if result.allowed:
            return response
        rewritten = copy.deepcopy(response)
        rewritten["oc_ferry_fence"] = {
            "allowed": False,
            "kind": result.kind,
            "reason": result.reason,
            "local_actions_rejected": result.local_actions_rejected,
        }
        choices = rewritten.get("choices")
        if isinstance(choices, list) and choices and isinstance(choices[0], dict):
            message = choices[0].get("message")
            if isinstance(message, dict):
                message.pop("tool_calls", None)
                message["content"] = json.dumps(result.envelope, ensure_ascii=False)
        return rewritten


# ---------------------------------------------------------------------------
# 模型注册表 / 状态
# ---------------------------------------------------------------------------


class ModelRegistry:
    """目录模型索引：把客户端可见 id 映射回上游模型记录。"""

    def __init__(self, models: Optional[List[Dict[str, Any]]] = None) -> None:
        self._lock = threading.RLock()
        self._models: Dict[str, Dict[str, Any]] = {}
        self._by_client_id: Dict[str, str] = {}
        self._by_upstream: Dict[str, str] = {}
        self.set_models(models or [])

    def set_models(self, models: List[Dict[str, Any]]) -> None:
        """重建索引。"""
        with self._lock:
            self._models = {}
            self._by_client_id = {}
            self._by_upstream = {}
            for item in models:
                if not isinstance(item, dict):
                    continue
                model_id = str(item.get("id") or "")
                if not model_id:
                    continue
                self._models[model_id] = dict(item)
                display = OC_CLIENT_PREFIX + str(item.get("name") or model_id)
                self._by_client_id[display] = model_id
                self._by_client_id[model_id] = model_id
                upstream = str(item.get("upstream_model") or "")
                if upstream:
                    self._by_upstream.setdefault(upstream, model_id)
                bare = (model_id[len(UPSTREAM_ID_PREFIX):]
                        if model_id.startswith(UPSTREAM_ID_PREFIX) else model_id)
                self._by_upstream.setdefault(bare, model_id)

    def resolve(self, requested: str) -> Optional[Dict[str, Any]]:
        """按 ``OC · <name>`` / ``opencode/<...>`` / 裸 upstream id 解析。"""
        key = (requested or "").strip()
        if not key:
            return None
        with self._lock:
            model_id = (self._by_client_id.get(key)
                        or self._by_upstream.get(key)
                        or self._models.get(key))
            if model_id is None:
                return None
            return dict(self._models[model_id])

    def all_models(self) -> List[Dict[str, Any]]:
        """返回按 id 排序的全部模型记录副本。"""
        with self._lock:
            return [dict(value) for value in sorted(
                self._models.values(), key=lambda item: str(item.get("id", "")))]


def _load_snapshot(path: str) -> List[Dict[str, Any]]:
    """读取本地目录快照；缺失或损坏返回空列表（不联网）。"""
    if not os.path.isfile(path):
        return []
    try:
        with open(path, "r", encoding="utf-8") as handle:
            doc = json.load(handle)
        models = doc.get("models") if isinstance(doc, dict) else None
        if not isinstance(models, list):
            return []
        return [dict(item) for item in models
                if isinstance(item, dict) and item.get("id")]
    except (OSError, ValueError, UnicodeError):
        return []


def _save_snapshot(path: str, models: List[Dict[str, Any]]) -> None:
    """原子写目录快照（供 serve 启动时零联网加载）。"""
    text = json.dumps({"schema": 1, "models": models},
                      ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    _atomic_write_text(path, text, mode=0o600)


def _read_selection(path: str) -> str:
    """读取当前选中的模型 id（不联网，文件缺失 / 损坏返回空串）。"""
    if not os.path.isfile(path):
        return ""
    with contextlib.suppress(OSError, ValueError, UnicodeError):
        with open(path, "r", encoding="utf-8") as handle:
            doc = json.load(handle)
        if isinstance(doc, dict):
            return str(doc.get("model") or "")
    return ""


def _write_selection(path: str, model_id: str) -> None:
    """原子写当前选中的模型 id。"""
    text = json.dumps({"model": model_id}, ensure_ascii=False, indent=2) + "\n"
    _atomic_write_text(path, text, mode=0o600)


def build_client_entries(models: List[Dict[str, Any]], cache: ProbeCache,
                         config: FerryConfig, api_key: str,
                         now: Optional[float] = None) -> List[Dict[str, Any]]:
    """把体检通过的模型转成可写入客户端配置的条目。

    只包含 ``available`` 与 ``chat_only``；限流/下线模型不会提供给客户端。
    客户端可见 id 前缀为 ``OC · ``，上游 id 保持 ``opencode/`` 前缀。
    """
    current = time.time() if now is None else now
    entries: List[Dict[str, Any]] = []
    for model in models:
        cached = cache.get(str(model.get("id", "")), str(model.get("fingerprint", "")),
                           now=current)
        if cached is None:
            continue
        status = str(cached.get("status", STATUS_UNKNOWN))
        if status not in CLIENT_VISIBLE_STATUSES:
            continue
        tools = status == STATUS_AVAILABLE and bool(cached.get("tools_enabled"))
        display = OC_CLIENT_PREFIX + str(model.get("name") or model.get("id"))
        item: Dict[str, Any] = {
            "id": display,
            "name": display,
            "model_id": str(model.get("id", "")),
            "upstream_model": _upstream_model_for(model, config),
            "base_url": "http://%s:%d/v1" % (config.host, config.port),
            "tools": tools,
            "status": status,
            MANAGED_MARKER: {
                "managed": True,
                "schema": 1,
                "model_id": str(model.get("id", "")),
                "fingerprint": str(model.get("fingerprint", "")),
                "status": status,
                "updated_at": current,
            },
        }
        if config.embed_api_key:
            item["api_key"] = api_key
        entries.append(item)
    entries.sort(key=lambda entry: str(entry.get("id", "")))
    return entries


def read_api_key(config: FerryConfig) -> str:
    """只读地获取本机 api_key（doctor 用，绝不生成、绝不写文件）。"""
    if config.api_key:
        return config.api_key
    if os.path.isfile(config.api_key_file):
        try:
            with open(config.api_key_file, "r", encoding="utf-8") as handle:
                return handle.read().strip()
        except (OSError, UnicodeError):
            return ""
    return ""


def ensure_api_key(config: FerryConfig) -> str:
    """获取（必要时生成并持久化）本机 api_key。仅明确命令/服务启动时调用。"""
    if config.api_key:
        return config.api_key
    path = config.api_key_file
    with FileLock(path, timeout=10.0):
        if os.path.isfile(path):
            with contextlib.suppress(OSError, UnicodeError):
                with open(path, "r", encoding="utf-8") as handle:
                    existing = handle.read().strip()
                if existing:
                    return existing
        key = secrets.token_urlsafe(32)
        _atomic_write_text(path, key + "\n", mode=0o600)
        return key


class FerryState:
    """服务与 CLI 共享的运行状态（不含网络副作用，网络只经 http_call）。"""

    def __init__(self, config: FerryConfig, http_call: HttpCall,
                 api_key: str = "",
                 models: Optional[List[Dict[str, Any]]] = None) -> None:
        self.config = config
        self.http_call = http_call
        self.api_key = api_key
        self.cache = ProbeCache(config.cache_file,
                                config.success_ttl_minutes,
                                config.load_ttl_minutes)
        self.fence = ApprovalFence()
        self.registry = ModelRegistry()
        self.semaphore = threading.BoundedSemaphore(config.max_inflight)
        self._lock = threading.RLock()
        snapshot = models if models is not None else _load_snapshot(config.state_file)
        self.registry.set_models(snapshot)

    def _fetch_catalog(self, catalog: Optional[Dict[str, Any]]) -> Dict[str, Any]:
        """取目录：优先注入的 catalog（测试用）> 本地 catalog_file > HTTP GET。"""
        if isinstance(catalog, dict):
            return catalog
        if self.config.catalog_file and os.path.isfile(self.config.catalog_file):
            with open(self.config.catalog_file, "r", encoding="utf-8") as handle:
                loaded = json.load(handle)
            if not isinstance(loaded, dict):
                raise FerryError("catalog_file root must be a JSON object")
            return loaded
        if not self.config.catalog_url:
            raise FerryError("catalog_url is not configured")
        request = HttpRequest(method="GET", url=self.config.catalog_url,
                              headers={"Accept": "application/json"},
                              body=b"", timeout=self.config.request_timeout,
                              stream=False)
        try:
            response = self.http_call(request)
        except Exception as exc:  # 注入式 HTTP 边界统一降级
            raise FerryError("catalog fetch failed: %s" % exc)
        if response.error:
            raise FerryError("catalog fetch failed: %s" % response.error)
        if response.status != 200:
            raise FerryError("catalog fetch returned HTTP %d" % response.status)
        try:
            loaded = json.loads(response.body.decode("utf-8"))
        except (ValueError, UnicodeDecodeError) as exc:
            raise FerryError("catalog is not valid JSON: %s" % exc)
        if not isinstance(loaded, dict):
            raise FerryError("catalog root must be a JSON object")
        return loaded

    def refresh(self, catalog: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        """刷新：拉目录 → 发现 → 并发体检 → 更新缓存与快照。

        **会联网并消耗少量免费额度**（每个模型最多 2 次短请求）。
        仅由显式命令 ``refresh`` 或 ``POST /admin/refresh`` 调用。
        """
        if not self.config.upstream_base_url:
            raise FerryError("upstream_base_url is required before probing")
        raw_catalog = self._fetch_catalog(catalog)
        models = discover_free_models(raw_catalog)
        counts: Dict[str, int] = {
            STATUS_AVAILABLE: 0, STATUS_CHAT_ONLY: 0,
            STATUS_RATE_LIMITED: 0, STATUS_OFFLINE: 0,
        }
        if models:
            with ThreadPoolExecutor(max_workers=self.config.probe_concurrency) as pool:
                futures = {}
                for model in models:
                    future = pool.submit(
                        probe_model, str(model["id"]), self.config, self.http_call,
                        "", str(model.get("fingerprint", "")), time.time)
                    futures[future] = model
                for future in as_completed(futures):
                    model = futures[future]
                    result = future.result()
                    self.cache.put(result, str(model.get("fingerprint", "")))
                    status = result.status if result.status in counts else STATUS_OFFLINE
                    counts[status] += 1
        with self._lock:
            _save_snapshot(self.config.state_file, models)
            self.registry.set_models(models)
        return {
            "discovered": len(models),
            "probed": len(models),
            "status": counts,
            "probe_concurrency": self.config.probe_concurrency,
            "cached_at": int(time.time()),
            "quota_notice": (
                "Each probe sends one short request (plus at most one retry) to the "
                "upstream and consumes a small amount of free quota. Passing only "
                "means this one call produced a protocol-valid action."),
        }

    def available_models(self) -> List[Dict[str, Any]]:
        """返回当前可提供给客户端的模型（含 status / tools_enabled）。"""
        out: List[Dict[str, Any]] = []
        for model in self.registry.all_models():
            cached = self.cache.get(str(model.get("id", "")),
                                    str(model.get("fingerprint", "")))
            if cached is None:
                continue
            status = str(cached.get("status", STATUS_UNKNOWN))
            if status not in CLIENT_VISIBLE_STATUSES:
                continue
            record = dict(model)
            record["status"] = status
            record["tools_enabled"] = (status == STATUS_AVAILABLE
                                       and bool(cached.get("tools_enabled")))
            out.append(record)
        return out

    def resolve_requested(self, requested: str) -> Tuple[Optional[Dict[str, Any]],
                                                         Optional[Dict[str, Any]]]:
        """解析客户端传入的 model，并返回其缓存判定。"""
        model = self.registry.resolve(requested)
        if model is None:
            return None, None
        cached = self.cache.get(str(model.get("id", "")),
                                str(model.get("fingerprint", "")))
        return model, cached

    def import_models(self) -> WriteReport:
        """把当前可用模型安全写入客户端配置文件（不联网）。"""
        entries = build_client_entries(self.registry.all_models(), self.cache,
                                       self.config, self.api_key)
        return merge_models_file(self.config.models_file, entries)

    def cleanup(self) -> WriteReport:
        """摘掉自己的条目（退出时清场，不联网）。"""
        return cleanup_models_file(self.config.models_file)

    def status_snapshot(self) -> Dict[str, Any]:
        """返回全量模型 + 缓存判定，供手机端控制面板渲染四档状态。

        与 ``/v1/models``（只列客户端可见模型）不同，本方法包含
        rate_limited / offline / 未体检模型，让面板能显示完整状态分布。
        """
        models: List[Dict[str, Any]] = []
        counts: Dict[str, int] = {
            STATUS_AVAILABLE: 0, STATUS_CHAT_ONLY: 0,
            STATUS_RATE_LIMITED: 0, STATUS_OFFLINE: 0,
        }
        for model in self.registry.all_models():
            record = dict(model)
            cached = self.cache.get(str(model.get("id", "")),
                                    str(model.get("fingerprint", "")))
            if cached is None:
                record["status"] = STATUS_UNKNOWN
                record["verdict"] = ""
                record["tools_enabled"] = False
                record["reason"] = "no fresh probe result"
                record["load_state"] = LOAD_UNKNOWN
                record["checked_at"] = 0.0
            else:
                record["status"] = str(cached.get("status", STATUS_UNKNOWN))
                record["verdict"] = str(cached.get("verdict", ""))
                record["tools_enabled"] = bool(cached.get("tools_enabled"))
                record["reason"] = str(cached.get("reason", ""))
                record["load_state"] = str(cached.get("load_state", LOAD_UNKNOWN))
                record["checked_at"] = _as_float(cached.get("checked_at")) or 0.0
            if record["status"] in counts:
                counts[record["status"]] += 1
            models.append(record)
        return {
            "models": models,
            "counts": counts,
            "selection": self.get_selection(),
            "cache_entries": len(self.cache.all_entries()),
            "panel_path": "/panel",
        }

    def get_selection(self) -> str:
        """返回当前选中的模型 id。"""
        return _read_selection(self.config.selection_file)

    def set_selection(self, model_id: str) -> str:
        """设置当前选中的模型；未知模型抛 :class:`FerryError`。"""
        model = self.registry.resolve(model_id)
        if model is None:
            raise FerryError("unknown model: %s" % model_id)
        resolved = str(model.get("id", ""))
        _write_selection(self.config.selection_file, resolved)
        return resolved


# ---------------------------------------------------------------------------
# 本机服务（127.0.0.1 的 OpenAI 兼容端口）
# ---------------------------------------------------------------------------


def _safe_error_payload(status: int, body: bytes) -> Dict[str, Any]:
    """把上游错误响应体安全地转成 JSON 字典。"""
    with contextlib.suppress(ValueError, UnicodeDecodeError, AttributeError):
        payload = json.loads(body.decode("utf-8"))
        if isinstance(payload, dict):
            return payload
    return {"error": {"message": "upstream returned HTTP %d" % status,
                      "type": "upstream_error"}}


class FerryHandler(BaseHTTPRequestHandler):
    """OpenAI 兼容 + admin 路由的 HTTP handler。"""

    protocol_version = "HTTP/1.1"
    server_version = "OCFerry/" + __version__

    @property
    def state(self) -> FerryState:
        """取回挂在 server 上的共享状态。"""
        return self.server.ferry_state  # type: ignore[attr-defined]

    def log_message(self, fmt: str, *args: Any) -> None:
        """静默访问日志，避免手机端日志噪音与信息泄露。"""
        return

    def _send_json(self, status: int, payload: Any,
                   extra_headers: Optional[Dict[str, str]] = None) -> None:
        """发送 JSON 响应（带 Content-Length，兼容 HTTP/1.1 keep-alive）。"""
        body = json.dumps(payload, ensure_ascii=False, default=str).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-OC-Ferry", __version__)
        if extra_headers:
            for key, value in extra_headers.items():
                self.send_header(key, value)
        self.end_headers()
        try:
            self.wfile.write(body)
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, OSError):
            return

    def _send_html(self, status: int, body: bytes) -> None:
        """发送 HTML 响应（仅用于零敏感数据的静态面板页）。

        直接透传文件原始字节，不做文本模式的隐式换行转换；这样在
        ``core.autocrlf`` 把工作区文件写成 CRLF 的 Windows 环境下，
        响应体仍与磁盘上的 ``panel.html`` 逐字节一致。
        """
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("X-OC-Ferry", __version__)
        self.end_headers()
        try:
            self.wfile.write(body)
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, OSError):
            return

    def _is_same_origin(self, origin: str) -> bool:
        """判断 Origin 是否为本机同源（协议 + loopback 主机 + 端口一致）。"""
        if not origin or origin == "null":
            return False
        try:
            parts = urllib.parse.urlparse(origin)
        except ValueError:
            return False
        if parts.scheme not in ("http", "https"):
            return False
        if parts.hostname not in (self.state.config.host, "localhost"):
            return False
        try:
            port = parts.port
        except ValueError:
            return False
        default_port = 443 if parts.scheme == "https" else 80
        return (port or default_port) == self.state.config.port

    def _guard(self) -> bool:
        """统一围栏：静态面板放行 → 并发上限 → 数据接口 Origin → Bearer。

        手机端安全边界 = **只绑 127.0.0.1 + 数据接口 Bearer + 静态页零数据**：

        - ``GET /panel`` 与 ``GET /``：**免 Bearer、免 Origin 检查**，直接放行；
          只返回同目录 ``panel.html`` 静态内容，页面本身零模型 / 状态 / 密钥
          数据。这是必需的，否则用户还没输 key 就打不开页面。
        - ``GET /health``：仅回环、免 Bearer、免 Origin 检查的非敏感存活探针
          （QA 契约：不含 api_key / upstream_api_key / 模型明细）。
        - ``/v1/*`` 与 ``/admin/*``：**Origin 围栏只作用于这两类路径**
          （非同源 Origin → 403，防 DNS rebinding / CSRF / 恶意网页），
          并强制 ``Authorization: Bearer`` 定时比较（``hmac.compare_digest``）。
          同源 Origin 必须放行：浏览器对同源 POST 会自动附加 ``Origin``，
          若一并拒绝，面板自己的 ``fetch`` 会全部 403 而不可用。
        - 其余路径（含未知路由）：同样走 Bearer 比较，不泄露任何数据。

        桌面端「任何带 Origin 一律 403」的围栏在手机端被**收窄**为
        「只作用于 ``/v1/*`` 与 ``/admin/*``、且只拒绝非同源 Origin」，
        这是「手机端控制面板必须可用」所要求的唯一有意放宽。
        """
        self._semaphore_held = False
        path = urllib.parse.urlparse(self.path).path

        # 静态面板：免 Bearer、免 Origin、零数据。放在并发配额之前放行，
        # 保证用户尚未输入 key、或推理并发已满时也能打开页面。
        if path in ("/panel", "/"):
            return True

        if not self.state.semaphore.acquire(blocking=False):
            self._send_json(503, {"error": {"message": "server busy",
                                            "type": "overloaded"}})
            return False
        self._semaphore_held = True

        origin = self.headers.get("Origin")
        protected = path.startswith("/v1/") or path.startswith("/admin/")
        if protected and origin is not None and not self._is_same_origin(origin):
            self._send_json(403, {"error": {
                "message": "cross-origin Origin header is not allowed",
                "type": "forbidden_origin"}})
            return False

        # 非敏感存活探针：仅回环、免 Bearer。所有模型数据与管理操作都在下面
        # 的 Bearer 比较之后，由 /v1/* 与 /admin/* 严格鉴权。
        if path == "/health":
            return True

        expected = "Bearer %s" % self.state.api_key
        provided = self.headers.get("Authorization", "")
        if not hmac.compare_digest(provided.encode("utf-8"),
                                   expected.encode("utf-8")):
            self._send_json(401, {"error": {"message": "invalid api key",
                                            "type": "unauthorized"}})
            return False
        return True

    def _release(self) -> None:
        """释放并发配额（流式请求在整个转发期间持有）。"""
        if getattr(self, "_semaphore_held", False):
            self.state.semaphore.release()
            self._semaphore_held = False

    def _read_body(self) -> bytes:
        """读取请求体，强制 8MB 上限；不支持 chunked。"""
        if str(self.headers.get("Transfer-Encoding", "")).lower() == "chunked":
            raise ValueError("chunked transfer encoding is not supported")
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            raise ValueError("invalid Content-Length")
        if length < 0:
            raise ValueError("negative Content-Length")
        if length > self.state.config.max_body_bytes:
            raise BodyTooLarge("request body exceeds %d bytes" % self.state.config.max_body_bytes)
        if length == 0:
            return b""
        data = self.rfile.read(length)
        if len(data) != length:
            raise ValueError("incomplete request body")
        return data

    def _path(self) -> str:
        """返回不带 query 的路径。"""
        return urllib.parse.urlparse(self.path).path

    def _parse_json(self, body: bytes) -> Dict[str, Any]:
        """解析请求体 JSON；非法则抛 :class:`ValueError`。"""
        try:
            payload = json.loads(body.decode("utf-8")) if body else {}
        except (ValueError, UnicodeDecodeError) as exc:
            raise ValueError("invalid JSON body: %s" % exc)
        if not isinstance(payload, dict):
            raise ValueError("JSON body must be an object")
        return payload

    def do_GET(self) -> None:
        """GET /health, /panel, /v1/models, /admin/selection, /admin/status。"""
        try:
            if not self._guard():
                return
            path = self._path()
            if path == "/health":
                self._handle_health()
            elif path in ("/panel", "/"):
                self._handle_panel()
            elif path == "/v1/models":
                self._handle_models()
            elif path == "/admin/selection":
                self._send_json(200, {"selection": self.state.get_selection()})
            elif path == "/admin/status":
                self._send_json(200, self.state.status_snapshot())
            else:
                self._send_json(404, {"error": {"message": "not found",
                                                "type": "not_found"}})
        except FerryError as exc:
            self._send_json(400, {"error": {"message": str(exc), "type": "bad_request"}})
        finally:
            self._release()

    def do_POST(self) -> None:
        """POST /v1/chat/completions 与 4 个 admin 动作路由。

        注意：**没有** ``/admin/cleanup``。清场统一由 CLI ``cleanup`` /
        ``serve`` 退出时完成，面板「清理」按钮只给出明确的 CLI 提示，
        不伪造服务端接口。
        """
        try:
            if not self._guard():
                return
            path = self._path()
            body = self._read_body()
            if path == "/v1/chat/completions":
                self._handle_chat(body)
            elif path == "/admin/refresh":
                self._handle_admin_refresh(body)
            elif path == "/admin/probe":
                self._handle_admin_probe(body)
            elif path == "/admin/import":
                self._handle_admin_import()
            elif path == "/admin/selection":
                self._handle_admin_selection(body)
            else:
                self._send_json(404, {"error": {"message": "not found",
                                                "type": "not_found"}})
        except BodyTooLarge as exc:
            self._send_json(413, {"error": {"message": str(exc),
                                            "type": "payload_too_large"}})
        except (ValueError, ModelsFileError, FerryError) as exc:
            self._send_json(400, {"error": {"message": str(exc),
                                            "type": "bad_request"}})
        finally:
            self._release()

    def _handle_health(self) -> None:
        """健康检查：只返回非敏感状态，不含 api_key / upstream_api_key。

        为手机端控制面板额外提供：上游是否已配置与主机名（只 host:port，
        不含路径与凭据）、缓存条目数、面板路径。
        """
        parsed = urllib.parse.urlparse(self.state.config.upstream_base_url or "")
        upstream_host = parsed.hostname or ""
        if upstream_host and parsed.port:
            upstream_host = "%s:%d" % (upstream_host, parsed.port)
        self._send_json(200, {
            "status": "ok",
            "service": "oc-ferry",
            "version": __version__,
            "host": self.state.config.host,
            "port": self.state.config.port,
            "models_registered": len(self.state.registry.all_models()),
            "models_available": len(self.state.available_models()),
            "cache_entries": len(self.state.cache.all_entries()),
            "upstream_configured": bool(self.state.config.upstream_base_url),
            "upstream_host": upstream_host,
            "panel_path": "/panel",
            "time": int(time.time()),
        })

    def _handle_models(self) -> None:
        """列出当前可用模型（限流/下线模型不会出现）。"""
        data: List[Dict[str, Any]] = []
        for model in self.state.available_models():
            data.append({
                "id": OC_CLIENT_PREFIX + str(model.get("name") or model.get("id")),
                "object": "model",
                "created": int(time.time()),
                "owned_by": "oc-ferry",
                "oc_ferry": {
                    "model_id": str(model.get("id", "")),
                    "status": model.get("status", STATUS_UNKNOWN),
                    "tools": bool(model.get("tools_enabled")),
                },
            })
        self._send_json(200, {"object": "list", "data": data})

    def _handle_chat(self, body: bytes) -> None:
        """转发 chat/completions，支持 stream 的 SSE 输出。"""
        payload = self._parse_json(body)
        requested = str(payload.get("model") or "")
        model, cached = self.state.resolve_requested(requested)
        if model is None:
            self._send_json(404, {"error": {"message": "unknown model",
                                            "type": "model_not_found"}})
            return
        status = str(cached.get("status", STATUS_UNKNOWN)) if cached else STATUS_UNKNOWN
        if status not in CLIENT_VISIBLE_STATUSES:
            self._send_json(404, {"error": {
                "message": "model is not currently available (status=%s)" % status,
                "type": "model_unavailable"}})
            return

        upstream_body = copy.deepcopy(payload)
        upstream_body["model"] = _upstream_model_for(model, self.state.config)
        if status == STATUS_CHAT_ONLY:
            # 仅对话：关闭工具，避免给不支持工具协议的模型下发工具定义。
            for key in ("tools", "tool_choice", "parallel_tool_calls", "functions"):
                upstream_body.pop(key, None)
        is_stream = bool(payload.get("stream"))
        upstream_body["stream"] = is_stream
        extra = {"Accept": "text/event-stream, application/json"} if is_stream else None
        request = HttpRequest(
            method="POST",
            url=_join_url(self.state.config.upstream_base_url, "/chat/completions"),
            headers=_upstream_headers(self.state.config, extra),
            body=json.dumps(upstream_body, ensure_ascii=False).encode("utf-8"),
            timeout=self.state.config.request_timeout,
            stream=is_stream,
        )
        try:
            response = self.state.http_call(request)
        except Exception as exc:  # 注入式 HTTP 边界统一降级
            self._send_json(502, {"error": {"message": "upstream unavailable",
                                            "type": "upstream_error",
                                            "detail": str(exc)}})
            return
        if response.error:
            self._send_json(502, {"error": {"message": "upstream unavailable",
                                            "type": "upstream_error",
                                            "detail": response.error}})
            return
        if is_stream:
            self._stream_response(response)
            return
        self._forward_nonstream(response, str(model.get("id", "")))

    def _forward_nonstream(self, response: HttpResponse, model_id: str) -> None:
        """非流式转发 + 审批围栏后处理。"""
        if response.status < 200 or response.status >= 300:
            if response.status == 429 and model_id:
                self.state.cache.put_load(model_id, LOAD_LIMITED)
            self._send_json(response.status,
                            _safe_error_payload(response.status, response.body))
            return
        try:
            data = json.loads(response.body.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            self._send_json(502, {"error": {"message": "upstream returned invalid JSON",
                                            "type": "upstream_error"}})
            return
        if isinstance(data, dict):
            choices = data.get("choices")
            if isinstance(choices, list) and choices and isinstance(choices[0], dict):
                message = choices[0].get("message")
                if isinstance(message, dict):
                    result = self.state.fence.evaluate(message)
                    if not result.allowed:
                        data = self.state.fence.rewrite(data, result)
                    data["oc_ferry"] = {
                        "fence": result.kind,
                        "reason": result.reason,
                        "local_actions_rejected": result.local_actions_rejected,
                    }
        self._send_json(200, data)

    def _stream_response(self, response: HttpResponse) -> None:
        """SSE 流式透传。

        流式响应不做信封改写（无法在完整消息到达前安全重写），围栏仅以
        ``X-OC-Ferry-Fence: stream-passthrough`` 标注审计边界；执行权仍由
        客户端 Zafiro Agent 掌握，服务端不执行任何动作。详见设计文档。
        """
        if response.status < 200 or response.status >= 300:
            self._send_json(response.status,
                            _safe_error_payload(response.status, response.body))
            return
        self.send_response(200)
        self.send_header("Content-Type",
                         response.headers.get("content-type",
                                              "text/event-stream; charset=utf-8"))
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.send_header("X-OC-Ferry-Fence", "stream-passthrough")
        self.send_header("X-OC-Ferry", __version__)
        self.end_headers()
        self.close_connection = True
        chunks: Iterable[bytes]
        if response.chunks is not None:
            chunks = response.chunks
        else:
            chunks = [response.body] if response.body else []
        try:
            for chunk in chunks:
                if not chunk:
                    continue
                self.wfile.write(chunk)
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, OSError):
            return

    def _handle_admin_refresh(self, body: bytes) -> None:
        """POST /admin/refresh：可选 body ``{"catalog": {...}}`` 离线注入目录。"""
        payload = self._parse_json(body)
        catalog = payload.get("catalog") if isinstance(payload.get("catalog"), dict) else None
        try:
            summary = self.state.refresh(catalog=catalog)
        except FerryError as exc:
            self._send_json(502, {"error": {"message": str(exc),
                                            "type": "refresh_failed"}})
            return
        self._send_json(200, summary)

    def _handle_admin_probe(self, body: bytes) -> None:
        """POST /admin/probe：对单个模型执行一次体检。"""
        payload = self._parse_json(body)
        requested = str(payload.get("model") or "")
        if not requested:
            raise ValueError("'model' is required")
        token = str(payload.get("token") or "")
        model = self.state.registry.resolve(requested) or {
            "id": requested, "fingerprint": "", "upstream_model": ""}
        result = probe_model(str(model["id"]), self.state.config,
                             self.state.http_call, token=token,
                             fingerprint=str(model.get("fingerprint", "")))
        self.state.cache.put(result, str(model.get("fingerprint", "")))
        self._send_json(200, asdict(result))

    def _handle_admin_import(self) -> None:
        """POST /admin/import：把可用模型安全写入目标配置文件。"""
        self._send_json(200, asdict(self.state.import_models()))

    def _handle_admin_selection(self, body: bytes) -> None:
        """POST /admin/selection：设置或取消当前选中的模型。

        body ``{"model": "..."}`` 选中；``{"clear": true}`` 或空 model 取消选中
        （手机端控制面板的勾选 / 取消勾选）。
        """
        payload = self._parse_json(body)
        requested = str(payload.get("model") or "")
        if bool(payload.get("clear")) or not requested:
            _write_selection(self.state.config.selection_file, "")
            self._send_json(200, {"selection": ""})
            return
        try:
            resolved = self.state.set_selection(requested)
        except FerryError as exc:
            self._send_json(400, {"error": {"message": str(exc),
                                            "type": "unknown_model"}})
            return
        self._send_json(200, {"selection": resolved})

    def _handle_panel(self) -> None:
        """GET /panel（或 /）：返回脚本同目录的零数据静态页 panel.html。

        本路由**免 Bearer、免 Origin 检查**（由 ``_guard`` 直接放行），只返回
        静态 HTML 骨架，不含任何模型 / 状态 / 密钥数据；所有数据由
        ``/health`` ``/v1/*`` ``/admin/*`` 提供并严格鉴权。
        文件缺失 / 不可读 / 非 UTF-8 时返回明确的 **503 JSON** 错误，
        不抛异常、不崩溃。按二进制读取并原样透传，保证 LF 与 CRLF
        工作区下响应体都与磁盘文件逐字节一致。
        """
        path = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                            PANEL_FILENAME)
        try:
            with open(path, "rb") as handle:
                raw = handle.read()
            raw.decode("utf-8")
        except (OSError, UnicodeError) as exc:
            self._send_json(503, {"error": {
                "message": "panel.html is missing or unreadable: %s" % exc,
                "type": "panel_unavailable"}})
            return
        self._send_html(200, raw)


class FerryHTTPServer(ThreadingHTTPServer):
    """只绑定回环地址的线程化 HTTP 服务。"""

    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, server_address: Tuple[str, int],
                 handler: Callable[..., BaseHTTPRequestHandler],
                 state: FerryState) -> None:
        self.ferry_state = state
        super(FerryHTTPServer, self).__init__(server_address, handler)


# ---------------------------------------------------------------------------
# CLI 子命令
# ---------------------------------------------------------------------------


_DOCTOR_CATALOG: Dict[str, Any] = {
    "demo": {
        "id": "demo",
        "models": {
            "free-model": {
                "name": "Demo Free",
                "cost": {"input": 0, "output": 0, "cache_read": 0, "cache_write": 0},
                "tool_call": True,
            },
            "paid-model": {
                "name": "Demo Paid",
                "cost": {"input": 1, "output": 2, "cache_read": 0, "cache_write": 0},
            },
            "old-free": {
                "name": "Old Free",
                "cost": {"input": 0, "output": 0, "cache_read": 0, "cache_write": 0},
                "deprecated": True,
            },
            "free-by-name-only": {
                "name": "FREE by name",
                "cost": {"input": 0.5, "output": 0},
            },
        },
    },
}

_DOCTOR_PROBE_RESPONSE: Dict[str, Any] = {
    "choices": [{
        "message": {
            "role": "assistant",
            "content": None,
            "tool_calls": [{
                "id": "call_1",
                "type": "function",
                "function": {
                    "name": "Read",
                    "arguments": '{"file_path": "/external/probe-doctortoken.txt"}',
                },
            }],
        },
    }],
}


def cmd_doctor(config: FerryConfig) -> int:
    """离线自检：配置/路径/缓存/纯逻辑冒烟，**绝不联网、绝不写文件**。"""
    cache = ProbeCache(config.cache_file, config.success_ttl_minutes,
                       config.load_ttl_minutes)
    entries = cache.all_entries()
    models = _load_snapshot(config.state_file)
    key = read_api_key(config)
    free = discover_free_models(_DOCTOR_CATALOG)
    body = build_probe_body("opencode/demo/free-model", "doctortoken")
    verdict = judge_probe(_DOCTOR_PROBE_RESPONSE, "doctortoken")
    parent = os.path.dirname(os.path.abspath(config.data_dir)) or "."
    writable = os.access(parent, os.W_OK) or os.access(config.data_dir, os.W_OK)

    lines = [
        "OC Ferry doctor (offline, no network, no writes)",
        "  python            : %s" % sys.version.split()[0],
        "  oc_ferry version  : %s" % __version__,
        "  data_dir          : %s" % config.data_dir,
        "  parent writable   : %s" % writable,
        "  models_file       : %s (%s)" % (
            config.models_file, "exists" if os.path.isfile(config.models_file) else "missing"),
        "  api_key_file      : %s (%s)" % (
            config.api_key_file, "present" if key else "absent, generated on serve/import"),
        "  cache_file        : %s (entries=%d, corrupted=%s)" % (
            config.cache_file, len(entries), cache.corrupted),
        "  state_file        : %s (snapshot=%d models)" % (
            config.state_file, len(models)),
        "  host:port         : %s:%d (loopback only)" % (config.host, config.port),
        "  upstream_base_url : %s" % (config.upstream_base_url or "<not set>"),
        "  catalog_url       : %s" % config.catalog_url,
        "  concurrency       : probe=%d, inflight=%d" % (
            config.probe_concurrency, config.max_inflight),
        "  ttl               : verdict=%dmin, load=%dmin" % (
            config.success_ttl_minutes, config.load_ttl_minutes),
        "  selfcheck.discover_free_models : %d free (paid/deprecated/name-only excluded)" % len(free),
        "  selfcheck.build_probe_body    : %d tools, parallel_tool_calls=%s" % (
            len(body["tools"]), body["parallel_tool_calls"]),
        "  selfcheck.judge_probe         : %s (status=%s)" % (
            verdict.code, verdict.status),
        "  selfcheck.fence               : %s" % (
            ApprovalFence().decode('{"oc_ferry_envelope": 1, "type": "text"}').kind),
        "  result            : OK",
    ]
    print("\n".join(lines))
    return 0


def cmd_refresh(config: FerryConfig, http_call: HttpCall) -> int:
    """刷新目录并体检（联网，消耗少量免费额度）。"""
    state = FerryState(config, http_call, api_key=read_api_key(config))
    print("OC Ferry refresh: this sends short probe requests and consumes a small "
          "amount of free quota.")
    try:
        summary = state.refresh()
    except FerryError as exc:
        print("refresh failed: %s" % exc, file=sys.stderr)
        return 1
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


def cmd_probe(config: FerryConfig, http_call: HttpCall,
              model_id: str, token: str) -> int:
    """对单个模型执行体检。"""
    state = FerryState(config, http_call, api_key="")
    model = state.registry.resolve(model_id) or {
        "id": model_id, "fingerprint": "", "upstream_model": ""}
    result = probe_model(str(model["id"]), config, http_call, token=token,
                         fingerprint=str(model.get("fingerprint", "")))
    state.cache.put(result, str(model.get("fingerprint", "")))
    print(json.dumps(asdict(result), ensure_ascii=False, indent=2))
    return 0


def cmd_import(config: FerryConfig) -> int:
    """把可用模型写入客户端配置文件（不联网）。"""
    api_key = ensure_api_key(config)
    state = FerryState(config, urllib_http_call, api_key=api_key)
    try:
        report = state.import_models()
    except ModelsFileError as exc:
        print("import failed: %s" % exc, file=sys.stderr)
        return 1
    print(json.dumps(asdict(report), ensure_ascii=False, indent=2))
    return 0


def cmd_serve(config: FerryConfig, http_call: HttpCall) -> int:
    """启动本机服务（阻塞）；优雅退出时把自己的条目摘干净。"""
    if not config.upstream_base_url:
        print("serve requires upstream_base_url (see config.example.json)",
              file=sys.stderr)
        return 2
    api_key = ensure_api_key(config)
    state = FerryState(config, http_call, api_key=api_key)
    server: Optional[FerryHTTPServer] = None
    try:
        server = FerryHTTPServer((config.host, config.port), FerryHandler, state)
    except OSError as exc:
        print("cannot bind %s:%d: %s" % (config.host, config.port, exc),
              file=sys.stderr)
        return 1
    print("OC Ferry %s listening on http://%s:%d/v1" %
          (__version__, config.host, config.port))
    print("Panel (this device only, no auth, zero data): "
          "http://%s:%d/panel" % (config.host, config.port))
    print("Bearer api-key (data endpoints): %s" % api_key)
    print("Panel route (1): GET /panel (GET / is the same page)")
    print("Data/admin routes (8): /health | /v1/models | "
          "/v1/chat/completions | /admin/refresh | /admin/probe | "
          "/admin/import | /admin/selection (GET+POST) | /admin/status")
    print("Cleanup: run 'python oc_ferry.py cleanup' (no HTTP route by design)")
    try:
        server.serve_forever(poll_interval=0.5)
    except KeyboardInterrupt:
        print("\nshutting down ...")
    finally:
        server.server_close()
        try:
            report = state.cleanup()
            print("cleanup: %s" % report.reason)
        except ModelsFileError as exc:
            print("cleanup skipped: %s" % exc, file=sys.stderr)
    return 0


def cmd_status(config: FerryConfig) -> int:
    """打印缓存与目录状态（离线、只读）。"""
    cache = ProbeCache(config.cache_file, config.success_ttl_minutes,
                       config.load_ttl_minutes)
    entries = cache.all_entries()
    models = _load_snapshot(config.state_file)
    print("OC Ferry status (offline, read-only)")
    print("  data_dir        : %s" % config.data_dir)
    print("  catalog snapshot: %d models" % len(models))
    print("  cache entries   : %d (corrupted=%s)" % (len(entries), cache.corrupted))
    print("  selection       : %s" % (_read_selection(config.selection_file) or "<none>"))
    for model_id in sorted(entries):
        entry = entries[model_id]
        print("  - %s | status=%s | verdict=%s | tools=%s | attempts_load=%s" % (
            model_id,
            entry.get("status", STATUS_UNKNOWN),
            entry.get("verdict", ""),
            bool(entry.get("tools_enabled")),
            cache.get_load(model_id) or "expired",
        ))
    print("  status legend   : available(可用) / chat_only(仅对话) / "
          "rate_limited(限流) / offline(下线)")
    return 0


def cmd_cleanup(config: FerryConfig) -> int:
    """摘掉自己打过标记的条目（不联网）。"""
    try:
        report = cleanup_models_file(config.models_file)
    except ModelsFileError as exc:
        print("cleanup failed: %s" % exc, file=sys.stderr)
        return 1
    print(json.dumps(asdict(report), ensure_ascii=False, indent=2))
    return 0


# ---------------------------------------------------------------------------
# 文档导出：把免费模型清单输出成 Markdown / JSON / CSV
# ---------------------------------------------------------------------------

EXPORT_FORMATS = ("md", "json", "csv")

STATUS_LABELS = {
    STATUS_AVAILABLE: "可用",
    STATUS_CHAT_ONLY: "仅对话",
    STATUS_RATE_LIMITED: "限流",
    STATUS_OFFLINE: "下线",
    STATUS_UNKNOWN: "未体检",
}


def _md_cell(value: Any) -> str:
    """转义 Markdown 表格单元格，避免模型名里的 ``|`` / 换行破坏表格。"""
    text = str(value if value is not None else "")
    return (text.replace("\\", "\\\\").replace("|", "\\|")
            .replace("\r", " ").replace("\n", " "))


def _format_ts(value: Any) -> str:
    """把缓存里的时间戳格式化为本地时间字符串；非法值原样返回。"""
    if value in (None, ""):
        return ""
    try:
        return time.strftime("%Y-%m-%d %H:%M", time.localtime(float(value)))
    except (TypeError, ValueError, OSError, OverflowError):
        return str(value)


def _read_catalog_file(path: str) -> Dict[str, Any]:
    """读取本地模型目录 JSON；根节点必须是对象。"""
    with open(path, "r", encoding="utf-8") as handle:
        loaded = json.load(handle)
    if not isinstance(loaded, dict):
        raise FerryError("catalog root must be a JSON object: %s" % path)
    return loaded


def _fetch_export_catalog(config: FerryConfig,
                          http_call: HttpCall) -> Dict[str, Any]:
    """通过可注入 ``http_call`` 拉取公开模型目录（导出链路唯一联网点）。

    公开目录不需要 API Key，也不会消耗任何推理额度；只有显式传入
    ``--online`` 时才会走到这里。
    """
    if not config.catalog_url:
        raise FerryError("catalog_url is not configured")
    request = HttpRequest(method="GET", url=config.catalog_url,
                          headers={"Accept": "application/json"}, body=b"",
                          timeout=config.request_timeout, stream=False)
    try:
        response = http_call(request)
    except Exception as exc:  # 注入式 HTTP 边界统一降级
        raise FerryError("catalog fetch failed: %s" % exc)
    if response.error:
        raise FerryError("catalog fetch failed: %s" % response.error)
    if response.status != 200:
        raise FerryError("catalog fetch returned HTTP %d" % response.status)
    try:
        loaded = json.loads(response.body.decode("utf-8"))
    except (ValueError, UnicodeDecodeError) as exc:
        raise FerryError("catalog is not valid JSON: %s" % exc)
    if not isinstance(loaded, dict):
        raise FerryError("catalog root must be a JSON object")
    return loaded


def collect_free_models(config: FerryConfig, http_call: HttpCall,
                        catalog_path: str = "",
                        online: bool = False) -> Tuple[List[Dict[str, Any]], str, bool]:
    """收集免费模型清单。

    数据源优先级：``--catalog`` 本地文件 > ``config.catalog_file`` >
    ``--online`` 公开目录 > 本地快照（``state_file``）。**只有 ``--online``
    且前两项都不存在时才会联网**；其余路径完全离线。

    Args:
        config: 已校验、路径已解析的配置。
        http_call: 可注入的 HTTP 调用函数。
        catalog_path: 显式指定的本地目录 JSON 路径。
        online: 是否允许拉取公开目录。

    Returns:
        ``(models, source_label, used_network)``；``models`` 结构同
        :func:`discover_free_models`。
    """
    raw: Optional[Dict[str, Any]] = None
    source = ""
    used_network = False
    if catalog_path:
        raw = _read_catalog_file(catalog_path)
        source = "本地目录文件 %s" % catalog_path
    elif config.catalog_file and os.path.isfile(config.catalog_file):
        raw = _read_catalog_file(config.catalog_file)
        source = "配置中的 catalog_file %s" % config.catalog_file
    elif online:
        raw = _fetch_export_catalog(config, http_call)
        source = "公开目录 %s" % config.catalog_url
        used_network = True
    if raw is not None:
        models = discover_free_models(raw)
    else:
        models = _load_snapshot(config.state_file)
        source = "本地快照 %s" % config.state_file
    return models, source, used_network


def build_export_rows(models: List[Dict[str, Any]],
                      entries: Dict[str, Dict[str, Any]]) -> List[Dict[str, Any]]:
    """把模型清单与体检缓存合并成文档行（缓存缺失时状态为「未体检」）。"""
    rows: List[Dict[str, Any]] = []
    for model in models:
        model_id = str(model.get("id", ""))
        entry = entries.get(model_id, {})
        status = str(entry.get("status") or STATUS_UNKNOWN)
        rows.append({
            "id": model_id,
            "upstream_model": str(model.get("upstream_model", "")),
            "provider": str(model.get("provider", "")),
            "name": str(model.get("name", "")),
            "status": status,
            "status_label": STATUS_LABELS.get(status, status),
            "verdict": str(entry.get("verdict", "") or ""),
            "tools": bool(entry.get("tools_enabled", False)),
            "checked_at": _format_ts(entry.get("checked_at")),
            "fingerprint": str(model.get("fingerprint", "")),
        })
    return rows


def render_markdown(rows: List[Dict[str, Any]], source: str,
                    generated: str) -> str:
    """把清单渲染成中文 Markdown 文档（按供应商分组）。"""
    counts: Dict[str, int] = {}
    for row in rows:
        key = str(row["status"])
        counts[key] = counts.get(key, 0) + 1
    groups: Dict[str, List[Dict[str, Any]]] = {}
    for row in rows:
        groups.setdefault(str(row["provider"]), []).append(row)

    lines: List[str] = [
        "# OC Ferry 免费模型清单",
        "",
        "> 由 `python oc_ferry.py export` 生成，可离线查阅与分享。",
        "> 生成时间：%s" % generated,
        "> 数据来源：%s" % source,
        "> 判定规则：`input` / `output` / `cache_read` / `cache_write` 四个价格字段"
        "全为 0 才算免费；`deprecated` 直接排除；名字里含 `free` **不作数**。",
        "",
        "## 概览",
        "",
        "| 项目 | 值 |",
        "| --- | --- |",
        "| 免费模型总数 | %d |" % len(rows),
        "| 供应商数量 | %d |" % len(groups),
        "| 数据来源 | %s |" % _md_cell(source),
        "| 生成时间 | %s |" % _md_cell(generated),
        "",
        "四档状态统计（未体检表示本机尚未执行 `refresh`）：",
        "",
        "| 状态 | 数量 | 含义 |",
        "| --- | --- | --- |",
    ]
    meanings = {
        STATUS_AVAILABLE: "体检通过，客户端可用，带工具",
        STATUS_CHAT_ONLY: "只回文本或格式不支持，仅对话、关闭工具",
        STATUS_RATE_LIMITED: "上游 429 / 503，10 分钟后重判，不提供给客户端",
        STATUS_OFFLINE: "动作不符 / 超时 / 网络或 HTTP 错误，不提供给客户端",
        STATUS_UNKNOWN: "本机尚未体检（导出清单本身不需要体检）",
    }
    for status in (STATUS_AVAILABLE, STATUS_CHAT_ONLY,
                   STATUS_RATE_LIMITED, STATUS_OFFLINE, STATUS_UNKNOWN):
        lines.append("| %s（%s） | %d | %s |" % (
            _md_cell(STATUS_LABELS.get(status, status)), status,
            counts.get(status, 0), _md_cell(meanings.get(status, ""))))
    lines.extend(["", "## 免费模型明细", ""])
    for provider in sorted(groups):
        items = groups[provider]
        lines.append("### %s（%d）" % (_md_cell(provider), len(items)))
        lines.append("")
        lines.append("| 模型 ID | 上游模型 | 名称 | 状态 | 工具 | 判定码 | 体检时间 |")
        lines.append("| --- | --- | --- | --- | --- | --- | --- |")
        for row in items:
            lines.append("| `%s` | `%s` | %s | %s | %s | %s | %s |" % (
                _md_cell(row["id"]), _md_cell(row["upstream_model"]),
                _md_cell(row["name"]), _md_cell(row["status_label"]),
                "是" if row["tools"] else "否",
                _md_cell(row["verdict"] or "-"),
                _md_cell(row["checked_at"] or "-")))
        lines.append("")
    lines.extend([
        "## 如何使用这份清单",
        "",
        "1. 在手机 Termux 里安装并配置 OC Ferry（见 `TERMUX.md`）。",
        "2. `python oc_ferry.py --config config.termux.json refresh` 会向上游发送短请求"
        "做工具调用体检，结果写入缓存；随后再 `export` 即可带上四档状态。",
        "3. 只有 `available` / `chat_only` 会出现在客户端的 `/v1/models`；"
        "导入后客户端可见 id 形如 `OC · <名称>`，上游 id 保持 `opencode/` 前缀。",
        "4. 不想体检、只想拿清单：直接 `export --online`，**不需要任何 API Key、"
        "不消耗额度**。",
        "",
        "## 诚实交代",
        "",
        "- 免费判定**只认价格字段**，目录里的价格或 `deprecated` 标记随时可能变动；",
        "  这份清单是生成时刻的快照，不是长期承诺。",
        "- 「免费」不等于「无限量」：上游通常有速率 / 额度限制，本项目不查询、不猜测余额。",
        "- 导出清单**不代表模型可用**；可用性要经过 `refresh` 的工具体检，"
        "且「通过」只代表这一次能按协议产生动作。",
        "- 导出操作只读目录、不向模型发送任何推理请求。",
        "",
    ])
    return "\n".join(lines)


def render_json(rows: List[Dict[str, Any]], source: str,
                generated: str) -> str:
    """把清单渲染成结构化 JSON（便于脚本消费）。"""
    payload = {
        "generated_at": generated,
        "source": source,
        "total": len(rows),
        "providers": len(set(str(row["provider"]) for row in rows)),
        "counts": dict((status, sum(1 for r in rows if r["status"] == status))
                       for status in STATUS_LABELS),
        "models": rows,
    }
    return json.dumps(payload, ensure_ascii=False, indent=2) + "\n"


def render_csv(rows: List[Dict[str, Any]]) -> str:
    """把清单渲染成 CSV（带表头，UTF-8，字段统一由 csv 模块转义）。"""
    buffer = io.StringIO()
    writer = csv.writer(buffer, lineterminator="\n")
    writer.writerow(["id", "upstream_model", "provider", "name",
                     "status", "status_label", "verdict", "tools",
                     "checked_at", "fingerprint"])
    for row in rows:
        writer.writerow([row["id"], row["upstream_model"], row["provider"],
                         row["name"], row["status"], row["status_label"],
                         row["verdict"], "yes" if row["tools"] else "no",
                         row["checked_at"], row["fingerprint"]])
    return buffer.getvalue()


def write_export(path: str, text: str) -> None:
    """原子写入导出文档（同目录临时文件 + fsync + os.replace，LF 换行）。"""
    parent = os.path.dirname(os.path.abspath(path))
    if parent and not os.path.isdir(parent):
        os.makedirs(parent, exist_ok=True)
    fd, tmp = tempfile.mkstemp(prefix=".oc-ferry-export-", dir=parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(text)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(tmp, path)
    except Exception:
        with contextlib.suppress(OSError):
            os.unlink(tmp)
        raise


def cmd_export(config: FerryConfig, http_call: HttpCall, fmt: str,
               output: str, catalog_path: str, online: bool,
               no_cache: bool) -> int:
    """导出免费模型文档；默认完全离线，``--online`` 才拉公开目录。"""
    try:
        models, source, used_network = collect_free_models(
            config, http_call, catalog_path, online)
    except (FerryError, OSError, ValueError) as exc:
        print("export failed: %s" % exc, file=sys.stderr)
        return 1
    if not models:
        print("没有免费模型数据。可任选其一：先运行 refresh；"
              "或用 export --online 拉取公开目录（不需要 API Key）；"
              "或用 export --catalog <本地目录.json>。", file=sys.stderr)
        return 1
    entries: Dict[str, Dict[str, Any]] = {}
    if not no_cache:
        cache = ProbeCache(config.cache_file, config.success_ttl_minutes,
                           config.load_ttl_minutes)
        entries = cache.all_entries()
    rows = build_export_rows(models, entries)
    generated = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime())
    if fmt == "json":
        text = render_json(rows, source, generated)
    elif fmt == "csv":
        text = render_csv(rows)
    else:
        text = render_markdown(rows, source, generated)
    path = output or os.path.join(config.data_dir, "free-models." + fmt)
    try:
        write_export(path, text)
    except OSError as exc:
        print("export write failed: %s" % exc, file=sys.stderr)
        return 1
    print(json.dumps({
        "exported": len(rows),
        "format": fmt,
        "output": os.path.abspath(path),
        "bytes": len(text.encode("utf-8")),
        "source": source,
        "network_used": used_network,
        "cache_entries_merged": len(entries),
    }, ensure_ascii=False, indent=2))
    return 0


def build_parser() -> argparse.ArgumentParser:
    """构造 CLI 解析器。"""
    parser = argparse.ArgumentParser(
        prog="oc_ferry.py",
        description="OC Ferry mobile: Zafiro Skill + loopback Python forwarder.")
    parser.add_argument("--config", default="",
                        help="Path to config.json (default: config.json next to this script).")
    parser.add_argument("--data-dir", default="",
                        help="Override data_dir from the config file.")
    sub = parser.add_subparsers(dest="command")
    sub.add_parser("doctor", help="Offline self-check (no network, no writes).")
    sub.add_parser("refresh", help="Fetch catalog, discover free models and probe them (network).")
    probe_parser = sub.add_parser("probe", help="Probe a single model (network).")
    probe_parser.add_argument("model", help="Model id, e.g. opencode/demo/free-model.")
    probe_parser.add_argument("--token", default="", help="Fixed probe token (testing).")
    sub.add_parser("import", help="Merge available models into the client models file (offline).")
    sub.add_parser("serve", help="Serve the OpenAI-compatible loopback endpoint.")
    sub.add_parser("status", help="Show cached probe status (offline).")
    sub.add_parser("cleanup", help="Remove only oc-ferry-managed entries (offline).")
    export_parser = sub.add_parser(
        "export", help="Export the free-model list to md/json/csv (offline by default).")
    export_parser.add_argument("--format", choices=list(EXPORT_FORMATS),
                               default="md", help="Document format (default: md).")
    export_parser.add_argument("--output", default="",
                               help="Output path (default: <data_dir>/free-models.<format>).")
    export_parser.add_argument("--catalog", default="",
                               help="Local catalog JSON, highest priority, offline.")
    export_parser.add_argument("--online", action="store_true",
                               help="Fetch the public catalog; no API key or quota needed.")
    export_parser.add_argument("--no-cache", action="store_true",
                               help="Do not merge probe-cache status columns.")
    return parser


def main(argv: Optional[List[str]] = None) -> int:
    """CLI 入口；import 本模块时零副作用。"""
    parser = build_parser()
    args = parser.parse_args(argv)
    if not args.command:
        parser.print_help()
        return 0
    try:
        config = load_config(args.config or None, args.data_dir)
    except (ConfigError, OSError, ValueError) as exc:
        print("config error: %s" % exc, file=sys.stderr)
        return 2

    # 纯逻辑命令绝不使用这个可注入函数；联网命令才把它传下去。
    http_call: HttpCall = urllib_http_call
    if args.command == "doctor":
        return cmd_doctor(config)
    if args.command == "refresh":
        return cmd_refresh(config, http_call)
    if args.command == "probe":
        return cmd_probe(config, http_call, args.model, args.token)
    if args.command == "import":
        return cmd_import(config)
    if args.command == "serve":
        return cmd_serve(config, http_call)
    if args.command == "status":
        return cmd_status(config)
    if args.command == "cleanup":
        return cmd_cleanup(config)
    if args.command == "export":
        # 只有显式 --online 才会用到这个可注入函数；其余路径零联网。
        return cmd_export(config, http_call, args.format, args.output,
                          args.catalog, args.online, args.no_cache)
    parser.print_help()
    return 0


if __name__ == "__main__":
    sys.exit(main())
