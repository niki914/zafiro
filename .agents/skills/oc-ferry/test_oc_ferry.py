# -*- coding: utf-8 -*-
"""OC Ferry 手机端 —— 全离线、可复现的 unittest 测试套件。

运行（工作区根目录）：
    python -m unittest discover -s .agents/skills/oc-ferry -p "test_*.py" -v

设计约束：
- 纯标准库；全程零真实联网。所有网络 I/O 通过注入 ``HttpCall`` 替身完成。
- 用 ``importlib.util.spec_from_file_location`` 从同目录加载 ``oc_ferry``，
  不依赖 cwd、不污染 sys.path 之外的模块。
- 每个用例用 ``tempfile.TemporaryDirectory()`` 隔离；HTTP 用例用真实回环
  端口 + 线程化服务，结束后 shutdown/server_close/join，不留端口与线程。
- 严格按需求验收口径写断言；本文件不修改任何源码或文档。
"""

import contextlib
import dataclasses
import glob
import http.client
import importlib.util
import io
import json
import os
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest import mock

# ---------------------------------------------------------------------------
# 从同目录加载被测模块（唯一入口）
# ---------------------------------------------------------------------------

_HERE = os.path.dirname(os.path.abspath(__file__))
_SOURCE = os.path.join(_HERE, "oc_ferry.py")
_CONFIG_EXAMPLE = os.path.join(_HERE, "config.example.json")

_spec = importlib.util.spec_from_file_location("oc_ferry", _SOURCE)
oc = importlib.util.module_from_spec(_spec)
sys.modules["oc_ferry"] = oc
_spec.loader.exec_module(oc)


# ---------------------------------------------------------------------------
# 测试替身 / 工厂 / 公共辅助
# ---------------------------------------------------------------------------

TOKEN = "tok-0123456789abcdef"
API_KEY = "test-api-key-0123456789"

FREE_COST = {"input": 0, "output": 0, "cache_read": 0, "cache_write": 0}


def _cost(**overrides):
    """构造四个价格字段齐全的 cost 子对象。"""
    cost = dict(FREE_COST)
    cost.update(overrides)
    return cost


def _catalog_nested(models):
    """provider.models 嵌套结构的目录。"""
    return {"demo": {"id": "demo", "models": models}}


def _catalog_flat(models):
    """扁平 id -> model 结构的目录。"""
    return models


def _read_call(token=TOKEN, name="Read", file_path=None, arguments=None):
    """构造一个合法的 Read tool_call（arguments 为 JSON 字符串）。"""
    if arguments is None:
        arguments = {"file_path": file_path or ("/external/probe-%s.txt" % token)}
    return {
        "id": "call_1",
        "type": "function",
        "function": {"name": name, "arguments": json.dumps(arguments)},
    }


def _completion(tool_calls=None, content=None):
    """构造一个最小 chat completion 响应体。"""
    message = {"role": "assistant"}
    if content is not None:
        message["content"] = content
    if tool_calls is not None:
        message["tool_calls"] = tool_calls
    return {"choices": [{"message": message}]}


def _http(payload, status=200, error="", headers=None, chunks=None):
    """构造 HttpResponse（payload 为 dict 时自动 JSON 序列化）。"""
    body = b"" if payload is None else (
        payload if isinstance(payload, bytes)
        else json.dumps(payload, ensure_ascii=False).encode("utf-8"))
    return oc.HttpResponse(status=status, headers=headers or {}, body=body,
                           chunks=chunks, error=error)


class ScriptedHttp:
    """按脚本依次返回响应，记录每次 HttpRequest；支持注入抛异常。

    ``default`` 用于脚本耗尽后的兜底（故意返回 no_action），配合 ``calls``
    长度断言即可证明「不该重试时没有多调一次」。
    """

    def __init__(self, responses, default=None):
        self.responses = list(responses)
        self.default = default if default is not None else _http(
            _completion(content="default text"))
        self.calls = []
        self._i = 0

    def __call__(self, request):
        self.calls.append(request)
        if self._i < len(self.responses):
            item = self.responses[self._i]
            self._i += 1
        else:
            item = self.default
        if isinstance(item, BaseException):
            raise item
        return item


def _explode(name):
    """返回一个「一被调用就抛异常」的假执行器。"""
    def _f(*args, **kwargs):
        raise AssertionError("禁止的执行路径被调用: %s" % name)
    return _f


@contextlib.contextmanager
def _no_execution():
    """把所有可能的本机执行/联网路径替换为会抛异常的假执行器。

    若 ApprovalFence 真的执行了 shell / 写文件 / 发起网络请求，
    任意一次调用都会让测试以 AssertionError 失败。
    """
    with contextlib.ExitStack() as stack:
        for module, attr, label in (
            (oc.os, "system", "os.system"),
            (oc.os, "popen", "os.popen"),
            (oc.os, "remove", "os.remove"),
            (oc.os, "unlink", "os.unlink"),
            (oc.shutil, "rmtree", "shutil.rmtree"),
            (oc.shutil, "copy2", "shutil.copy2"),
            (oc.tempfile, "mkstemp", "tempfile.mkstemp"),
            (oc.urllib.request, "urlopen", "urllib.request.urlopen"),
            (oc.socket, "create_connection", "socket.create_connection"),
        ):
            stack.enter_context(mock.patch.object(module, attr, _explode(label)))
        for attr in ("_atomic_write_text", "_atomic_write_json"):
            stack.enter_context(mock.patch.object(oc, attr, _explode(attr)))
        # 若实现引入了 subprocess 执行路径，同样封死
        stack.enter_context(mock.patch.object(oc, "subprocess",
                                             _explode("subprocess"),
                                             create=True))
        yield


def _make_config(tmp, **overrides):
    """生成一个已解析路径、已校验、全部落在临时目录的 FerryConfig。"""
    kwargs = dict(
        data_dir=tmp,
        upstream_base_url="https://upstream.example/v1",
        api_key=API_KEY,
    )
    kwargs.update(overrides)
    cfg = oc.FerryConfig(**kwargs).resolved_paths()
    cfg.validate()
    return cfg


# 四模型目录，用于注册表 / HTTP 端点测试
def _four_model_catalog():
    return _catalog_nested({
        "alpha": {"name": "Alpha", "cost": _cost(), "tool_call": True},
        "beta": {"name": "Beta", "cost": _cost(), "reasoning": True},
        "gamma": {"name": "Gamma", "cost": _cost()},
        "delta": {"name": "Delta", "cost": _cost()},
    })


class _BaseTest(unittest.TestCase):
    """为每个用例提供独立的临时目录。"""

    def setUp(self):
        self._tmp_obj = tempfile.TemporaryDirectory(prefix="oc-ferry-test-")
        self.tmp = self._tmp_obj.name
        self.addCleanup(self._tmp_obj.cleanup)

    def path(self, name):
        return os.path.join(self.tmp, name)


# ===========================================================================
# 1. 免费发现 discover_free_models
# ===========================================================================

class TestDiscoverFreeModels(_BaseTest):

    def test_all_zero_cost_is_free_nested_shape(self):
        """input/output/cache_read/cache_write 全 0 → 免费（provider.models 结构）。"""
        catalog = _catalog_nested({
            "m1": {"name": "M1", "cost": _cost()},
        })
        result = oc.discover_free_models(catalog)
        self.assertEqual(len(result), 1)
        item = result[0]
        self.assertEqual(item["id"], "opencode/demo/m1")
        self.assertEqual(item["upstream_model"], "demo/m1")
        self.assertEqual(item["provider"], "demo")
        self.assertEqual(item["name"], "M1")
        self.assertEqual(item["cost"], {"input": 0.0, "output": 0.0,
                                        "cache_read": 0.0, "cache_write": 0.0})
        self.assertTrue(item["id"].startswith("opencode/"))
        self.assertEqual(item["fingerprint"],
                         oc._capability_fingerprint(catalog["demo"]["models"]["m1"]))
        self.assertEqual(len(item["fingerprint"]), 16)

    def test_flat_catalog_supported(self):
        """扁平 id -> model 结构同样可发现，且 id 前缀/指纹正确。"""
        model = {"name": "Flat One", "cost": _cost(), "tool_call": True}
        catalog = _catalog_flat({"flat-one": model})
        result = oc.discover_free_models(catalog)
        self.assertEqual(len(result), 1)
        item = result[0]
        self.assertTrue(item["id"].startswith("opencode/"))
        self.assertEqual(item["name"], "Flat One")
        self.assertEqual(item["fingerprint"], oc._capability_fingerprint(model))
        self.assertEqual(item["cost"], {"input": 0.0, "output": 0.0,
                                        "cache_read": 0.0, "cache_write": 0.0})

    def test_top_level_price_fields_supported(self):
        """价格写在顶层（非 cost 子对象）也能识别。"""
        catalog = _catalog_flat({
            "m": {"name": "Top", "input": 0, "output": 0,
                  "cache_read": 0, "cache_write": 0},
        })
        self.assertEqual(len(oc.discover_free_models(catalog)), 1)

    def test_any_nonzero_cost_excluded(self):
        """任一价格字段非 0 即排除（四个字段逐个验证）。"""
        for key in oc.COST_KEYS:
            with self.subTest(key=key):
                catalog = _catalog_nested({
                    "m": {"name": "Paid", "cost": _cost(**{key: 0.001})},
                })
                self.assertEqual(oc.discover_free_models(catalog), [])

    def test_name_containing_free_but_paid_is_excluded(self):
        """名字含 free 但收费 → 必须排除（名字不参与免费判定）。"""
        catalog = _catalog_nested({
            "m": {"name": "FREE-by-name-only", "cost": _cost(input=0.5, output=2)},
        })
        self.assertEqual(oc.discover_free_models(catalog), [])

    def test_name_not_free_but_zero_cost_is_included(self):
        """名字不含 free 但价格全 0 → 仍应入选，证明判定只读价格。"""
        catalog = _catalog_nested({
            "m": {"name": "Premium Ultra Pro", "cost": _cost()},
        })
        result = oc.discover_free_models(catalog)
        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]["name"], "Premium Ultra Pro")

    def test_deprecated_bool_and_status_excluded(self):
        """deprecated: true / status='deprecated' 均排除。"""
        variants = [
            {"name": "Old", "cost": _cost(), "deprecated": True},
            {"name": "Old", "cost": _cost(), "deprecated": "2024-01-01"},
            {"name": "Old", "cost": _cost(), "status": "deprecated"},
            {"name": "Old", "cost": _cost(), "status": "DEPRECATED"},
        ]
        for model in variants:
            with self.subTest(model=model):
                self.assertEqual(oc.discover_free_models(_catalog_nested({"m": model})), [])

    def test_no_price_metadata_is_excluded(self):
        """完全没有可解析价格元数据的条目不算免费（避免误判）。"""
        catalog = _catalog_nested({
            "m": {"name": "No price", "tool_call": True},
        })
        self.assertEqual(oc.discover_free_models(catalog), [])

    def test_empty_and_malformed_catalog_do_not_crash(self):
        """空目录 / 畸形条目 / 非 dict 顶层都不能崩。"""
        for catalog in ({}, None, [], "junk", 42,
                        {"p": None}, {"p": "junk"}, {"p": 3},
                        {"p": {"models": None}},
                        {"p": {"models": {"m": None, "n": "junk", "o": 7}}}):
            with self.subTest(catalog=repr(catalog)):
                result = oc.discover_free_models(catalog)
                self.assertIsInstance(result, list)

    def test_mixed_catalog_only_returns_free_sorted(self):
        """混合目录只返回免费且按 id 稳定排序。"""
        catalog = _catalog_nested({
            "z-free": {"name": "Z", "cost": _cost()},
            "a-paid": {"name": "A", "cost": _cost(input=1)},
            "m-free": {"name": "M", "cost": _cost()},
            "b-old": {"name": "B", "cost": _cost(), "deprecated": True},
        })
        result = oc.discover_free_models(catalog)
        self.assertEqual([x["id"] for x in result],
                         ["opencode/demo/m-free", "opencode/demo/z-free"])

    def test_capability_fingerprint_changes_with_capability(self):
        """能力字段变化会改变指纹；无关字段（name）不改变指纹。"""
        base = {"cost": _cost(), "tool_call": True, "reasoning": True}
        changed = {"cost": _cost(), "tool_call": False, "reasoning": True}
        renamed = {"cost": _cost(), "tool_call": True, "reasoning": True,
                   "name": "Renamed"}
        self.assertNotEqual(oc._capability_fingerprint(base),
                            oc._capability_fingerprint(changed))
        self.assertEqual(oc._capability_fingerprint(base),
                         oc._capability_fingerprint(renamed))


# ===========================================================================
# 2. 体检：build_probe_body / judge_probe / probe_model
# ===========================================================================

class TestBuildProbeBody(_BaseTest):

    def test_five_tools_parallel_false_and_token_in_file_path(self):
        body = oc.build_probe_body("opencode/demo/m1", TOKEN)
        names = [t["function"]["name"] for t in body["tools"]]
        self.assertEqual(names, ["Read", "Write", "Bash", "Glob", "WebSearch"])
        self.assertIs(body["parallel_tool_calls"], False)
        self.assertEqual(body["model"], "opencode/demo/m1")
        self.assertEqual(body["tool_choice"], "auto")
        self.assertIs(body["stream"], False)
        # 随机 token 必须落在要求读取的 file_path 里
        user_text = body["messages"][1]["content"]
        self.assertIn(TOKEN, user_text)
        self.assertIn("/external/probe-%s.txt" % TOKEN, user_text)
        self.assertIn("Read", user_text)
        # 系统提示应说明信封协议，且不含任何真实执行指令
        self.assertIn(oc.ENVELOPE_KEY, body["messages"][0]["content"])

    def test_each_call_returns_fresh_tool_definitions(self):
        """每次深拷贝，避免共享可变全局状态。"""
        first = oc.build_probe_body("m", "t1")
        first["tools"][0]["function"]["name"] = "MUTATED"
        second = oc.build_probe_body("m", "t2")
        self.assertEqual(second["tools"][0]["function"]["name"], "Read")

    def test_token_is_random_when_not_supplied_by_probe_model(self):
        cfg = _make_config(self.tmp)
        seen = []

        def capture(request):
            seen.append(json.loads(request.body.decode("utf-8")))
            return _http(_completion(tool_calls=[_read_call(token="")]))

        # token 未提供时由 probe_model 随机生成（两次调用应不同）
        r1 = oc.probe_model("m", cfg, capture)
        r2 = oc.probe_model("m", cfg, capture)
        self.assertNotEqual(r1.token, r2.token)
        self.assertTrue(r1.token and r2.token)


class TestJudgeProbe(_BaseTest):

    def test_ok_single_read_with_token(self):
        verdict = oc.judge_probe(
            _completion(tool_calls=[_read_call()]), TOKEN)
        self.assertEqual(verdict.code, oc.VERDICT_OK)
        self.assertEqual(verdict.status, oc.STATUS_AVAILABLE)
        self.assertIs(verdict.retryable, False)
        self.assertIs(verdict.tools_enabled, True)
        self.assertEqual(verdict.http_status_hint, 200)

    def test_no_action_text_only(self):
        for payload in (_completion(content="hello"),
                        _completion(tool_calls=[]),
                        _completion(tool_calls=None),
                        _completion(content="", tool_calls=[])):
            with self.subTest(payload=payload):
                v = oc.judge_probe(payload, TOKEN)
                self.assertEqual(v.code, oc.VERDICT_NO_ACTION)
                self.assertEqual(v.status, oc.STATUS_CHAT_ONLY)
                self.assertIs(v.retryable, True)
                self.assertIs(v.tools_enabled, False)
                self.assertEqual(v.http_status_hint, 502)

    def test_probe_mismatch_call_count_name_token(self):
        cases = [
            _completion(tool_calls=[_read_call(), _read_call()]),          # 2 次
            _completion(tool_calls=[]),                                      # 0 次由 no_action 处理
            _completion(tool_calls=[_read_call(name="Write")]),             # 名字错
            _completion(tool_calls=[_read_call(file_path="/etc/passwd")]),  # token 不匹配
        ]
        for payload in cases[:1] + cases[2:]:
            with self.subTest(payload=payload):
                v = oc.judge_probe(payload, TOKEN)
                self.assertEqual(v.code, oc.VERDICT_PROBE_MISMATCH)
                self.assertEqual(v.status, oc.STATUS_OFFLINE)
                self.assertIs(v.retryable, True)
                self.assertIs(v.tools_enabled, False)
        # 0 次调用是 no_action，不是 mismatch
        self.assertEqual(oc.judge_probe(cases[1], TOKEN).code, oc.VERDICT_NO_ACTION)

    def test_invalid_model_output_variants_not_retryable(self):
        bad = [None, "text", 42, [], {},
               {"choices": []}, {"choices": "x"},
               {"choices": [1]}, {"choices": [{}]}]
        for payload in bad:
            with self.subTest(payload=payload):
                v = oc.judge_probe(payload, TOKEN)
                self.assertEqual(v.code, oc.VERDICT_INVALID_MODEL_OUTPUT)
                self.assertEqual(v.status, oc.STATUS_CHAT_ONLY)
                self.assertIs(v.retryable, False)
                self.assertIs(v.tools_enabled, False)

    def test_invalid_tool_call_variants_not_retryable(self):
        bad = [
            _completion(tool_calls="not-a-list"),
            _completion(tool_calls=[1]),
            _completion(tool_calls=[{}]),
            _completion(tool_calls=[{"function": {"name": ""}}]),
            _completion(tool_calls=[{"function": {"name": "Read",
                                                   "arguments": "{not json"}}]),
            _completion(tool_calls=[{"function": {"name": "Read",
                                                   "arguments": [1, 2]}}]),
            _completion(tool_calls=[{"function": {"name": "Read",
                                                   "arguments": "[1,2]"}}]),
        ]
        for payload in bad:
            with self.subTest(payload=payload):
                v = oc.judge_probe(payload, TOKEN)
                self.assertEqual(v.code, oc.VERDICT_INVALID_TOOL_CALL)
                self.assertEqual(v.status, oc.STATUS_CHAT_ONLY)
                self.assertIs(v.retryable, False)

    def test_arguments_as_dict_accepted(self):
        payload = _completion(tool_calls=[{
            "function": {"name": "Read",
                         "arguments": {"file_path": "/external/probe-%s.txt" % TOKEN}}
        }])
        self.assertEqual(oc.judge_probe(payload, TOKEN).code, oc.VERDICT_OK)


class TestProbeModelRetryPolicy(_BaseTest):

    def setUp(self):
        super().setUp()
        self.cfg = _make_config(self.tmp)
        self.t0 = 1_700_000_000.0

    def _run(self, http, **kw):
        return oc.probe_model("opencode/demo/m1", self.cfg, http,
                              token=TOKEN, fingerprint="fp-1",
                              now_fn=lambda: self.t0, **kw)

    def test_ok_first_attempt_no_retry(self):
        http = ScriptedHttp([_http(_completion(tool_calls=[_read_call()]))])
        r = self._run(http)
        self.assertEqual(len(http.calls), 1)
        self.assertEqual(r.status, oc.STATUS_AVAILABLE)
        self.assertEqual(r.verdict, oc.VERDICT_OK)
        self.assertEqual(r.attempts, 1)
        self.assertIs(r.tools_enabled, True)
        self.assertEqual(r.load_state, oc.LOAD_HEALTHY)
        self.assertEqual(r.token, TOKEN)
        self.assertEqual(r.fingerprint, "fp-1")
        self.assertEqual(r.checked_at, self.t0)
        # 请求体必须是标准体检体
        sent = json.loads(http.calls[0].body.decode("utf-8"))
        self.assertIs(sent["parallel_tool_calls"], False)
        self.assertEqual(len(sent["tools"]), 5)
        self.assertIn(TOKEN, sent["messages"][1]["content"])

    def test_no_action_retried_once_then_ok(self):
        http = ScriptedHttp([
            _http(_completion(content="text only")),
            _http(_completion(tool_calls=[_read_call()])),
        ])
        r = self._run(http)
        self.assertEqual(len(http.calls), 2)
        self.assertEqual(r.status, oc.STATUS_AVAILABLE)
        self.assertEqual(r.verdict, oc.VERDICT_OK)
        self.assertEqual(r.attempts, 2)

    def test_no_action_twice_becomes_chat_only(self):
        http = ScriptedHttp([
            _http(_completion(content="text")),
            _http(_completion(content="text")),
        ])
        r = self._run(http)
        self.assertEqual(len(http.calls), 2)
        self.assertEqual(r.status, oc.STATUS_CHAT_ONLY)
        self.assertEqual(r.verdict, oc.VERDICT_NO_ACTION)
        self.assertEqual(r.attempts, 2)
        self.assertIs(r.tools_enabled, False)

    def test_probe_mismatch_retried_once_then_ok(self):
        http = ScriptedHttp([
            _http(_completion(tool_calls=[_read_call(name="Write")])),
            _http(_completion(tool_calls=[_read_call()])),
        ])
        r = self._run(http)
        self.assertEqual(len(http.calls), 2)
        self.assertEqual(r.status, oc.STATUS_AVAILABLE)
        self.assertEqual(r.attempts, 2)

    def test_probe_mismatch_twice_becomes_offline(self):
        http = ScriptedHttp([
            _http(_completion(tool_calls=[_read_call(name="Bash")])),
            _http(_completion(tool_calls=[_read_call(name="Bash")])),
        ])
        r = self._run(http)
        self.assertEqual(len(http.calls), 2)
        self.assertEqual(r.status, oc.STATUS_OFFLINE)
        self.assertEqual(r.verdict, oc.VERDICT_PROBE_MISMATCH)
        self.assertEqual(r.attempts, 2)

    def test_timeout_not_retried(self):
        http = ScriptedHttp([_http(None, status=0, error="timeout: timed out")])
        r = self._run(http)
        self.assertEqual(len(http.calls), 1, "超时不得重试")
        self.assertEqual(r.status, oc.STATUS_OFFLINE)
        self.assertEqual(r.verdict, oc.VERDICT_TIMEOUT)
        self.assertEqual(r.attempts, 1)

    def test_network_error_not_retried(self):
        http = ScriptedHttp([_http(None, status=0, error="network: unreachable")])
        r = self._run(http)
        self.assertEqual(len(http.calls), 1, "网络错误不得重试")
        self.assertEqual(r.status, oc.STATUS_OFFLINE)
        self.assertEqual(r.verdict, oc.VERDICT_NETWORK)
        self.assertEqual(r.attempts, 1)

    def test_rate_limited_429_not_retried(self):
        http = ScriptedHttp([_http({"error": "slow down"}, status=429)])
        r = self._run(http)
        self.assertEqual(len(http.calls), 1)
        self.assertEqual(r.status, oc.STATUS_RATE_LIMITED)
        self.assertEqual(r.verdict, oc.VERDICT_RATE_LIMITED)
        self.assertEqual(r.load_state, oc.LOAD_LIMITED)
        self.assertEqual(r.attempts, 1)

    def test_http_500_not_retried(self):
        http = ScriptedHttp([_http({"error": "oops"}, status=500)])
        r = self._run(http)
        self.assertEqual(len(http.calls), 1)
        self.assertEqual(r.status, oc.STATUS_OFFLINE)
        self.assertEqual(r.verdict, oc.VERDICT_HTTP_ERROR)
        self.assertEqual(r.attempts, 1)

    def test_invalid_tool_call_not_retried(self):
        http = ScriptedHttp([_http(_completion(tool_calls=[{}]))])
        r = self._run(http)
        self.assertEqual(len(http.calls), 1, "格式类失败不得重试")
        self.assertEqual(r.status, oc.STATUS_CHAT_ONLY)
        self.assertEqual(r.verdict, oc.VERDICT_INVALID_TOOL_CALL)
        self.assertEqual(r.attempts, 1)

    def test_invalid_model_output_not_retried(self):
        http = ScriptedHttp([_http({"unexpected": 1})])
        r = self._run(http)
        self.assertEqual(len(http.calls), 1)
        self.assertEqual(r.status, oc.STATUS_CHAT_ONLY)
        self.assertEqual(r.verdict, oc.VERDICT_INVALID_MODEL_OUTPUT)
        self.assertEqual(r.attempts, 1)

    def test_invalid_json_body_not_retried(self):
        http = ScriptedHttp([_http(b"not-json", status=200)])
        r = self._run(http)
        self.assertEqual(len(http.calls), 1)
        self.assertEqual(r.verdict, oc.VERDICT_INVALID_MODEL_OUTPUT)
        self.assertEqual(r.attempts, 1)

    def test_http_call_exception_not_retried(self):
        """注入式 http_call 抛异常：优雅降级、不重试、异常不穿透调用方。"""
        http = ScriptedHttp([RuntimeError("injected transport failure")])
        # 若异常穿透，_run 会直接抛错，本用例即 ERROR；能拿到返回值即已降级
        r = self._run(http)
        self.assertEqual(len(http.calls), 1, "注入异常不得触发任何重试")
        self.assertEqual(r.status, oc.STATUS_OFFLINE)
        self.assertEqual(r.verdict, oc.VERDICT_NETWORK)
        self.assertFalse(r.tools_enabled)
        self.assertEqual(r.attempts, 1)

    def test_any_ok_returns_immediately_even_after_retryable(self):
        """任一次通过立即返回：第三次响应不应被消费。"""
        http = ScriptedHttp([
            _http(_completion(content="text")),
            _http(_completion(tool_calls=[_read_call()])),
            _http(_completion(content="should never be used")),
        ])
        r = self._run(http)
        self.assertEqual(len(http.calls), 2)
        self.assertEqual(r.status, oc.STATUS_AVAILABLE)
        self.assertEqual(r.attempts, 2)


# ===========================================================================
# 3. 缓存 ProbeCache：双 TTL / 指纹 / 跨实例 / 损坏降级
# ===========================================================================

class TestProbeCache(_BaseTest):

    def setUp(self):
        super().setUp()
        self.path = self.path("probe_cache.json")
        self.t0 = 1_700_000_000.0
        self.cache = oc.ProbeCache(self.path, success_ttl_minutes=360,
                                   load_ttl_minutes=10)
        self.result = oc.ProbeResult(
            model_id="opencode/demo/m1", status=oc.STATUS_AVAILABLE,
            verdict=oc.VERDICT_OK, attempts=1, tools_enabled=True,
            load_state=oc.LOAD_HEALTHY, checked_at=self.t0,
            fingerprint="fp-1")

    def test_put_get_roundtrip_and_cross_instance(self):
        self.cache.put(self.result, "fp-1")
        # 重新构造实例（模拟跨进程 / 重新读盘）
        other = oc.ProbeCache(self.path, 360, 10)
        entry = other.get("opencode/demo/m1", "fp-1", now=self.t0)
        self.assertIsNotNone(entry)
        self.assertEqual(entry["status"], oc.STATUS_AVAILABLE)
        self.assertEqual(entry["verdict"], oc.VERDICT_OK)
        self.assertIs(entry["tools_enabled"], True)
        self.assertEqual(len(other.all_entries()), 1)

    def test_success_ttl_is_6_hours(self):
        self.cache.put(self.result, "fp-1")
        self.assertIsNotNone(self.cache.get("opencode/demo/m1", "fp-1",
                                            now=self.t0 + 360 * 60 - 1))
        self.assertIsNone(self.cache.get("opencode/demo/m1", "fp-1",
                                         now=self.t0 + 360 * 60 + 1))

    def test_load_ttl_is_10_minutes(self):
        self.cache.put(self.result, "fp-1")
        self.assertEqual(self.cache.get_load("opencode/demo/m1", now=self.t0),
                         oc.LOAD_HEALTHY)
        self.assertIsNone(self.cache.get_load("opencode/demo/m1",
                                              now=self.t0 + 10 * 60 + 1))
        # 负载过期不影响 6 小时的判定有效期
        self.assertIsNotNone(self.cache.get("opencode/demo/m1", "fp-1",
                                            now=self.t0 + 10 * 60 + 1))

    def test_put_load_refreshes_only_load_timestamp(self):
        self.cache.put(self.result, "fp-1")
        t1 = self.t0 + 5 * 60
        self.cache.put_load("opencode/demo/m1", oc.LOAD_LIMITED, now=t1)
        self.assertEqual(self.cache.get_load("opencode/demo/m1", now=t1),
                         oc.LOAD_LIMITED)
        # checked_at 未被改动 → 判定仍按 6h 失效
        entry = self.cache.all_entries()["opencode/demo/m1"]
        self.assertEqual(entry["checked_at"], self.t0)
        self.assertIsNotNone(self.cache.get("opencode/demo/m1", "fp-1", now=t1))
        self.assertIsNone(self.cache.get_load("opencode/demo/m1",
                                              now=t1 + 10 * 60 + 1))

    def test_fingerprint_change_invalidates(self):
        self.cache.put(self.result, "fp-1")
        self.assertIsNone(self.cache.get("opencode/demo/m1", "fp-2", now=self.t0))
        self.assertIsNotNone(self.cache.get("opencode/demo/m1", "fp-1", now=self.t0))

    def test_rate_limited_uses_10min_load_ttl(self):
        rl = oc.ProbeResult(model_id="opencode/demo/rl",
                            status=oc.STATUS_RATE_LIMITED,
                            verdict=oc.VERDICT_RATE_LIMITED,
                            load_state=oc.LOAD_LIMITED,
                            checked_at=self.t0, fingerprint="fp")
        self.cache.put(rl, "fp")
        self.assertIsNotNone(self.cache.get("opencode/demo/rl", "fp", now=self.t0))
        self.assertIsNone(self.cache.get("opencode/demo/rl", "fp",
                                         now=self.t0 + 10 * 60 + 1))

    def test_clock_rollback_invalidates(self):
        self.cache.put(self.result, "fp-1")
        self.assertIsNone(self.cache.get("opencode/demo/m1", "fp-1",
                                         now=self.t0 - 1))

    def test_corrupt_json_safe_degradation_and_backup_preserves_raw_bytes(self):
        raw = "{ this is not json ]"
        with open(self.path, "w", encoding="utf-8") as handle:
            handle.write(raw)
        degraded = oc.ProbeCache(self.path, 360, 10)
        self.assertEqual(degraded.all_entries(), {})
        self.assertIs(degraded.corrupted, True)
        self.assertIsNone(degraded.get("anything", "fp", now=self.t0))
        # 写入后损坏文件被备份，原始字节不丢；缓存恢复可用
        degraded.put(self.result, "fp-1")
        backups = glob.glob(self.path + ".corrupt-*")
        self.assertEqual(len(backups), 1)
        with open(backups[0], "r", encoding="utf-8") as handle:
            self.assertEqual(handle.read(), raw)
        self.assertIsNotNone(degraded.get("opencode/demo/m1", "fp-1", now=self.t0))
        self.assertIs(degraded.corrupted, False)

    def test_partially_malformed_entries_do_not_lose_user_data(self):
        """entries 里混入非 dict 值时，合法条目必须完整保留。"""
        good = {"model_id": "good", "fingerprint": "fp", "status": "available",
                "tools_enabled": True, "checked_at": self.t0,
                "load_checked_at": self.t0, "load_state": "healthy"}
        doc = {"schema": 1, "entries": {"good": good, "bad": "junk", "num": 42,
                                        "nil": None}}
        with open(self.path, "w", encoding="utf-8") as handle:
            json.dump(doc, handle)
        cache = oc.ProbeCache(self.path, 360, 10)
        entries = cache.all_entries()
        self.assertIn("good", entries)
        self.assertEqual(entries["good"]["status"], "available")
        self.assertNotIn("bad", entries)
        self.assertNotIn("num", entries)

    def test_wrong_schema_root_is_treated_as_corrupt(self):
        with open(self.path, "w", encoding="utf-8") as handle:
            handle.write("[1, 2, 3]")
        cache = oc.ProbeCache(self.path, 360, 10)
        self.assertEqual(cache.all_entries(), {})
        self.assertIs(cache.corrupted, True)

    def test_put_load_on_missing_entry_is_noop(self):
        self.cache.put_load("does-not-exist", oc.LOAD_LIMITED, now=self.t0)
        self.assertEqual(self.cache.all_entries(), {})

    def test_all_entries_returns_copies(self):
        self.cache.put(self.result, "fp-1")
        snapshot = self.cache.all_entries()
        snapshot["opencode/demo/m1"]["status"] = "MUTATED"
        self.assertEqual(self.cache.all_entries()["opencode/demo/m1"]["status"],
                         oc.STATUS_AVAILABLE)


# ===========================================================================
# 4. 安全写入 merge_models_file / cleanup_models_file（最重要）
# ===========================================================================

class TestSafeModelsWrite(_BaseTest):

    def setUp(self):
        super().setUp()
        self.file = self.path("models.json")
        self.marker = {"managed": True, "schema": 1, "model_id": "m"}
        self.managed = [
            {"id": "OC · Managed A", "name": "OC · Managed A",
             oc.MANAGED_MARKER: {"managed": True, "schema": 1,
                                 "model_id": "opencode/demo/a"}},
            {"id": "OC · Managed B", "name": "OC · Managed B",
             oc.MANAGED_MARKER: {"managed": True, "schema": 1,
                                 "model_id": "opencode/demo/b"}},
        ]

    def _write_raw(self, text):
        with open(self.file, "w", encoding="utf-8", newline="") as handle:
            handle.write(text)

    def _read_raw(self):
        with open(self.file, "r", encoding="utf-8", newline="") as handle:
            return handle.read()

    @staticmethod
    def _lf(text):
        """统一换行符后再比较字节块（Windows 文本写入会转成 CRLF）。"""
        return text.replace("\r\n", "\n")

    def _canonical_array(self, entries):
        return json.dumps(entries, ensure_ascii=False, indent=2,
                          sort_keys=True) + "\n"

    def _managed_entry(self, name="OC · M", marker=True):
        return {"id": name, "name": name,
                oc.MANAGED_MARKER: {"managed": marker, "model_id": "opencode/demo/m"}}

    def test_array_shape_preserves_user_entry_bytes(self):
        """裸数组形态：用户条目的字节块逐字节不变，且保持数组形态。"""
        user = {"id": "user/manual", "name": "手写 · 保留",
                "extra": {"nested": [1, 2, 3], "flag": True},
                "unicode": "中文 \"引号\" \\反斜杠"}
        self._write_raw(self._canonical_array([user]))
        original = self._read_raw()

        report = oc.merge_models_file(self.file, self.managed)
        self.assertIs(report.changed, True)
        self.assertEqual(report.kept, 1)
        self.assertEqual(report.added, 2)
        self.assertEqual(report.removed, 0)

        new_raw = self._read_raw()
        doc = json.loads(new_raw)
        self.assertIsInstance(doc, list, "必须保持裸数组形态")
        self.assertEqual(len(doc), 3)
        self.assertEqual(doc[0], user, "用户条目内容必须逐字节等价（语义完全一致）")
        self.assertNotIn(oc.MANAGED_MARKER, doc[0])

        # 逐字节校验：用户条目的规范化文本块原样出现在新文件中
        block = json.dumps(user, ensure_ascii=False, indent=2, sort_keys=True)
        indented = "\n".join("  " + line for line in block.splitlines())
        self.assertIn(self._lf(indented).encode("utf-8"),
                      self._lf(new_raw).encode("utf-8"))
        self.assertIn("中文".encode("utf-8"), new_raw.encode("utf-8"))
        self.assertNotEqual(new_raw, original)  # 确实新增了受管条目

    def test_object_shape_preserved_with_other_top_level_keys(self):
        """{"models": [...]} 形态：保持形态 + 顶层其它键原样保留。"""
        user = {"id": "user/manual", "name": "手写", "extra": {"x": [1, 2]}}
        top = {"version": 7, "custom": {"keep": True, "list": [1, "二"]},
               "models": [user]}
        self._write_raw(json.dumps(top, ensure_ascii=False, indent=2,
                                   sort_keys=True) + "\n")

        report = oc.merge_models_file(self.file, [self._managed_entry()])
        self.assertIs(report.changed, True)
        doc = json.loads(self._read_raw())
        self.assertIsInstance(doc, dict)
        self.assertEqual(doc["version"], 7)
        self.assertEqual(doc["custom"], {"keep": True, "list": [1, "二"]})
        self.assertEqual(len(doc["models"]), 2)
        self.assertEqual(doc["models"][0], user)

        # 对象形态下用户条目字节块（缩进 4）同样保留
        block = json.dumps(user, ensure_ascii=False, indent=2, sort_keys=True)
        indented = "\n".join("    " + line for line in block.splitlines())
        self.assertIn(self._lf(indented).encode("utf-8"),
                      self._lf(self._read_raw()).encode("utf-8"))

    def test_arbitrary_user_formatting_semantically_preserved(self):
        """用户手写文件可任意排版；条目内容不得被改动 / 注入标记。"""
        user = {"id": "user/手寫", "name": "KEEP-ME",
                "options": {"temperature": 0.7, "headers": {"X-A": "b"}},
                "tags": ["a", "中文"]}
        raw = '{"models": [\n  ' + json.dumps(user, ensure_ascii=False) + \
              '\n], "note": "do not touch"}\n'
        self._write_raw(raw)
        oc.merge_models_file(self.file, [self._managed_entry()])
        doc = json.loads(self._read_raw())
        self.assertEqual(doc["note"], "do not touch")
        self.assertEqual(doc["models"][0], user)
        self.assertNotIn(oc.MANAGED_MARKER, doc["models"][0])

    def test_user_entry_with_oc_prefix_is_not_treated_as_managed(self):
        """用户条目即便 id 以 'OC · ' 开头，只要没标记就绝不被删除。"""
        user = {"id": "OC · Mine", "name": "OC · Mine", "custom": 1}
        self._write_raw(self._canonical_array([user]))
        report = oc.cleanup_models_file(self.file)
        self.assertIs(report.changed, False)
        self.assertEqual(json.loads(self._read_raw()), [user])

    def test_only_marker_decides_add_and_remove(self):
        """重复 merge 只替换自己标记的条目，用户条目始终保留。"""
        user = {"id": "user/1", "name": "U"}
        self._write_raw(self._canonical_array([user, self._managed_entry("OC · Old")]))
        report = oc.merge_models_file(self.file, self.managed)
        self.assertEqual(report.kept, 1)
        self.assertEqual(report.removed, 1)
        self.assertEqual(report.added, 2)
        doc = json.loads(self._read_raw())
        self.assertEqual(doc[0], user)
        self.assertEqual(len(doc), 3)
        self.assertFalse(any(x.get("id") == "OC · Old" for x in doc))

    def test_no_rewrite_when_content_semantically_unchanged(self):
        """内容语义不变时不重写（mtime + 写入次数 + 不产生新备份）。"""
        # 先造一个已存在的用户文件，再 merge，确保备份确实生成
        user = {"id": "user/1", "name": "U"}
        self._write_raw(self._canonical_array([user]))
        oc.merge_models_file(self.file, self.managed)
        first_mtime = os.stat(self.file).st_mtime_ns
        backup = self.file + ".oc-ferry.bak"
        self.assertTrue(os.path.isfile(backup))
        backup_mtime = os.stat(backup).st_mtime_ns

        real = oc._atomic_write_json
        with mock.patch.object(oc, "_atomic_write_json",
                               wraps=real) as spy:
            report = oc.merge_models_file(self.file, self.managed)
            self.assertEqual(spy.call_count, 0, "内容未变时不得落盘")
        self.assertIs(report.changed, False)
        self.assertEqual(report.reason, "content unchanged; no write performed")
        self.assertEqual(report.backup_path, "")
        self.assertEqual(os.stat(self.file).st_mtime_ns, first_mtime)
        self.assertEqual(os.stat(backup).st_mtime_ns, backup_mtime)

    def test_backup_created_before_write_contains_original_bytes(self):
        user = {"id": "user/1", "name": "U"}
        self._write_raw(self._canonical_array([user]))
        original = self._read_raw()
        report = oc.merge_models_file(self.file, [self._managed_entry()])
        self.assertEqual(report.backup_path, self.file + ".oc-ferry.bak")
        with open(report.backup_path, "r", encoding="utf-8", newline="") as handle:
            self.assertEqual(handle.read(), original)

    def test_backup_can_be_disabled(self):
        self._write_raw(self._canonical_array([{"id": "user/1"}]))
        oc.merge_models_file(self.file, [self._managed_entry()], backup=False)
        self.assertFalse(os.path.exists(self.file + ".oc-ferry.bak"))

    def test_corrupt_file_raises_models_file_error_without_overwrite(self):
        """结构损坏 → ModelsFileError，且绝不覆盖原文件。"""
        bad_docs = [
            "{ not json",
            '{"models": "not-an-array"}',
            "42",
            '"a string"',
        ]
        for raw in bad_docs:
            with self.subTest(raw=raw):
                self._write_raw(raw)
                with self.assertRaises(oc.ModelsFileError):
                    oc.merge_models_file(self.file, [self._managed_entry()])
                self.assertEqual(self._read_raw(), raw, "损坏文件不得被覆盖")
                with self.assertRaises(oc.ModelsFileError):
                    oc.cleanup_models_file(self.file)
                self.assertEqual(self._read_raw(), raw)

    def test_cleanup_removes_only_managed_entries(self):
        user = {"id": "user/1", "name": "U", "custom": {"keep": True}}
        other = {"id": "user/2", "name": "U2"}
        self._write_raw(self._canonical_array(
            [user, self.managed[0], other, self.managed[1]]))
        report = oc.cleanup_models_file(self.file)
        self.assertIs(report.changed, True)
        self.assertEqual(report.removed, 2)
        self.assertEqual(report.kept, 2)
        self.assertEqual(json.loads(self._read_raw()), [user, other])

        # 二次 cleanup 无变化，不重写
        mtime = os.stat(self.file).st_mtime_ns
        again = oc.cleanup_models_file(self.file)
        self.assertIs(again.changed, False)
        self.assertEqual(os.stat(self.file).st_mtime_ns, mtime)

    def test_cleanup_preserves_object_shape_and_other_keys(self):
        user = {"id": "user/1"}
        doc = {"version": 3, "models": [user, self._managed_entry()], "note": "keep"}
        self._write_raw(json.dumps(doc, ensure_ascii=False, indent=2,
                                   sort_keys=True) + "\n")
        oc.cleanup_models_file(self.file)
        result = json.loads(self._read_raw())
        self.assertIsInstance(result, dict)
        self.assertEqual(result["version"], 3)
        self.assertEqual(result["note"], "keep")
        self.assertEqual(result["models"], [user])

    def test_cleanup_missing_file_is_noop(self):
        report = oc.cleanup_models_file(self.file)
        self.assertIs(report.changed, False)
        self.assertFalse(os.path.exists(self.file))

    def test_merge_creates_object_shape_for_missing_file(self):
        report = oc.merge_models_file(self.file, [self._managed_entry()])
        self.assertIs(report.created, True)
        self.assertIs(report.changed, True)
        doc = json.loads(self._read_raw())
        self.assertIsInstance(doc, dict)
        self.assertEqual(len(doc["models"]), 1)
        # 新建文件不需要备份
        self.assertEqual(report.backup_path, "")

    def test_object_shape_without_models_key_is_handled(self):
        doc = {"version": 1, "note": "keep"}
        self._write_raw(json.dumps(doc, ensure_ascii=False) + "\n")
        oc.merge_models_file(self.file, [self._managed_entry()])
        result = json.loads(self._read_raw())
        self.assertEqual(result["version"], 1)
        self.assertEqual(result["note"], "keep")
        self.assertEqual(len(result["models"]), 1)


# ===========================================================================
# 5. 审批围栏 ApprovalFence
# ===========================================================================

class TestApprovalFence(_BaseTest):

    def setUp(self):
        super().setUp()
        self.fence = oc.ApprovalFence()

    def test_decode_valid_and_invalid_envelopes(self):
        good = json.dumps({oc.ENVELOPE_KEY: 1, "type": "text", "content": "hi"})
        env = self.fence.decode(good)
        self.assertIs(env.accepted, True)
        self.assertEqual(env.kind, oc.KIND_TEXT)
        for bad in ("", "   ", "plain text", "{}", "[1,2]",
                    json.dumps({"type": "text"})):
            with self.subTest(bad=bad):
                self.assertIs(self.fence.decode(bad).accepted, False)

    def test_code_fence_stripped(self):
        good = '```json\n{"%s": 1, "type": "text"}\n```' % oc.ENVELOPE_KEY
        self.assertIs(self.fence.decode(good).accepted, True)

    def test_encode_text_and_external_tool_call_roundtrip(self):
        text = self.fence.encode_text("你好 world")
        env = self.fence.decode(text)
        self.assertIs(env.accepted, True)
        self.assertEqual(env.kind, oc.KIND_TEXT)
        self.assertEqual(env.payload["content"], "你好 world")

        ext = self.fence.encode_external_tool_call("bash", {"command": "ls"}, "blocked")
        env = self.fence.decode(ext)
        self.assertEqual(env.kind, oc.KIND_EXTERNAL_TOOL_CALL)
        self.assertEqual(env.payload["tool"], "bash")
        self.assertIn("client", env.payload["note"].lower())

    def test_is_local_action_keywords(self):
        for tool, args in (
            ("Bash", {"command": "ls -la"}),
            ("shell", {}),
            ("exec", {"cmd": "id"}),
            ("Write", {"file_path": "/tmp/x"}),
            ("read_file", {"path": "/etc/passwd"}),
            ("fetch", {"url": "http://localhost:8080/"}),
            ("ping", {"host": "127.0.0.1"}),
            ("termux", {}),
            ("adb", {"cmd": "shell"}),
            ("run", {"command": "sudo rm -rf /"}),
            ("eval", {"code": "eval(1+1)"}),
        ):
            with self.subTest(tool=tool, args=args):
                self.assertIs(self.fence.is_local_action(tool, args), True)

    def test_benign_client_tool_is_not_local_action(self):
        self.assertIs(self.fence.is_local_action("search", {"query": "weather"}), False)
        self.assertIs(self.fence.is_local_action("translate", {"text": "hi"}), False)

    def test_evaluate_plain_text_normalized_into_text_envelope(self):
        result = self.fence.evaluate({"content": "just plain text"})
        self.assertIs(result.allowed, False)
        self.assertEqual(result.kind, oc.KIND_NORMALIZED)
        self.assertEqual(result.envelope["type"], oc.KIND_TEXT)
        self.assertEqual(result.envelope["content"], "just plain text")
        self.assertEqual(result.local_actions_rejected, 0)

    def test_evaluate_dangerous_tool_calls_rejected_and_delegated(self):
        message = {"content": None, "tool_calls": [
            {"function": {"name": "Bash",
                          "arguments": json.dumps({"command": "rm -rf /tmp/x"})}},
            {"function": {"name": "Write",
                          "arguments": json.dumps({"file_path": "/tmp/x"})}},
        ]}
        result = self.fence.evaluate(message)
        self.assertIs(result.allowed, False)
        self.assertEqual(result.kind, oc.KIND_EXTERNAL_TOOL_CALL)
        self.assertEqual(result.local_actions_rejected, 2)
        self.assertEqual(result.envelope["type"], oc.KIND_EXTERNAL_TOOL_CALL)
        for req in result.envelope["requests"]:
            self.assertIs(req["blocked_local"], True)
            self.assertIs(req["delegated"], True)

    def test_evaluate_envelope_with_local_action_rejected(self):
        envelope = json.dumps({oc.ENVELOPE_KEY: 1, "type": "tool_request",
                               "tool": "shell",
                               "arguments": {"command": "id"}})
        result = self.fence.evaluate({"content": envelope})
        self.assertIs(result.allowed, False)
        self.assertEqual(result.kind, oc.KIND_EXTERNAL_TOOL_CALL)
        self.assertGreaterEqual(result.local_actions_rejected, 1)

    def test_evaluate_localhost_in_arguments_rejected(self):
        envelope = json.dumps({oc.ENVELOPE_KEY: 1, "type": "tool_request",
                               "tool": "http_get",
                               "arguments": {"url": "http://localhost:41980/v1"}})
        result = self.fence.evaluate({"content": envelope})
        self.assertIs(result.allowed, False)
        self.assertEqual(result.kind, oc.KIND_EXTERNAL_TOOL_CALL)

    def test_evaluate_benign_client_tool_call_allowed_as_tool_request(self):
        message = {"content": None, "tool_calls": [
            {"function": {"name": "search",
                          "arguments": json.dumps({"query": "weather"})}}
        ]}
        result = self.fence.evaluate(message)
        self.assertIs(result.allowed, True)
        self.assertEqual(result.kind, oc.KIND_TOOL_REQUEST)
        self.assertIs(result.envelope["requests"][0]["blocked_local"], False)
        self.assertIs(result.envelope["requests"][0]["delegated"], True)

    def test_evaluate_valid_text_envelope_allowed(self):
        envelope = json.dumps({oc.ENVELOPE_KEY: 1, "type": "text", "content": "ok"})
        result = self.fence.evaluate({"content": envelope})
        self.assertIs(result.allowed, True)
        self.assertEqual(result.kind, oc.KIND_TEXT)

    def test_rewrite_strips_dangerous_tool_calls(self):
        response = _completion(content="run it", tool_calls=[
            {"id": "c1", "function": {"name": "Bash",
                                      "arguments": '{"command":"rm -rf /"}'}}
        ])
        original_calls = response["choices"][0]["message"]["tool_calls"]
        result = self.fence.evaluate(response["choices"][0]["message"])
        self.assertIs(result.allowed, False)
        rewritten = self.fence.rewrite(response, result)
        # 不修改原对象
        self.assertIsNot(rewritten, response)
        self.assertEqual(response["choices"][0]["message"]["tool_calls"], original_calls)
        message = rewritten["choices"][0]["message"]
        self.assertNotIn("tool_calls", message, "危险 tool_calls 必须被剥离")
        content = json.loads(message["content"])
        self.assertEqual(content["type"], oc.KIND_EXTERNAL_TOOL_CALL)
        self.assertIs(rewritten["oc_ferry_fence"]["allowed"], False)
        self.assertEqual(rewritten["oc_ferry_fence"]["local_actions_rejected"], 1)

    def test_rewrite_allowed_returns_same_object(self):
        response = _completion(content="hi")
        result = oc.FenceResult(allowed=True, kind=oc.KIND_TEXT)
        self.assertIs(self.fence.rewrite(response, result), response)

    def test_no_execution_path_is_ever_invoked(self):
        """核心不变量：注入会抛异常的假执行器；任何本机动作执行都会让用例失败。"""
        fake = mock.Mock(side_effect=AssertionError("假执行器被调用"))
        messages = [
            {"content": "please run shell rm -rf / and exec sudo", "tool_calls": [
                {"function": {"name": "Bash",
                              "arguments": '{"command":"rm -rf /"}'}},
                {"function": {"name": "Write",
                              "arguments": '{"file_path":"/tmp/pwn"}'}},
            ]},
            {"content": json.dumps({oc.ENVELOPE_KEY: 1, "type": "tool_request",
                                    "tool": "localhost",
                                    "arguments": {"url": "http://127.0.0.1"}})},
            {"content": "plain text"},
        ]
        with _no_execution():
            self.fence.executor = fake           # 注入假执行器（若实现有执行器必被使用）
            self.fence._executor = fake
            self.fence.run = fake
            for message in messages:
                result = self.fence.evaluate(message)
                rewritten = self.fence.rewrite(_completion(content="x"), result)
                self.assertIsInstance(rewritten, dict)
        self.assertEqual(fake.call_count, 0,
                         "审批围栏不得调用任何执行器（执行权必须留在客户端）")


# ===========================================================================
# 6. HTTP 端点（真实回环端口 + 注入 http_call）
# ===========================================================================

class TestHTTPEndpoints(_BaseTest):

    def setUp(self):
        super().setUp()
        self._servers = []

    def _start(self, state):
        server = oc.FerryHTTPServer(("127.0.0.1", 0), oc.FerryHandler, state)
        port = server.server_address[1]
        thread = threading.Thread(
            target=server.serve_forever, kwargs={"poll_interval": 0.02},
            daemon=True)
        thread.start()
        self._servers.append((server, thread))
        self.addCleanup(self._stop_all)
        return port

    def _stop_all(self):
        for server, thread in self._servers:
            server.shutdown()
            server.server_close()
            thread.join(timeout=3)
        self._servers = []

    def _make_state(self, catalog=None, statuses=None, http_call=None,
                    **config_overrides):
        catalog = catalog or _four_model_catalog()
        statuses = statuses or [oc.STATUS_AVAILABLE, oc.STATUS_CHAT_ONLY,
                                oc.STATUS_RATE_LIMITED, oc.STATUS_OFFLINE]
        models = oc.discover_free_models(catalog)
        self.assertEqual(len(models), len(statuses))
        cfg = _make_config(self.tmp, **config_overrides)
        state = oc.FerryState(cfg, http_call or ScriptedHttp([]),
                              api_key=API_KEY, models=models)
        now = time.time()
        for model, status in zip(models, statuses):
            state.cache.put(oc.ProbeResult(
                model_id=model["id"], status=status,
                verdict=oc.VERDICT_OK if status == oc.STATUS_AVAILABLE else "seed",
                tools_enabled=(status == oc.STATUS_AVAILABLE),
                load_state=(oc.LOAD_LIMITED if status == oc.STATUS_RATE_LIMITED
                            else oc.LOAD_HEALTHY),
                checked_at=now, fingerprint=model["fingerprint"]),
                model["fingerprint"])
        return state, models

    def _request(self, port, method, path, body=None, headers=None):
        conn = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
        hdrs = {"Connection": "close"}
        if headers:
            hdrs.update(headers)
        payload = None
        if body is not None:
            payload = (body if isinstance(body, bytes)
                       else json.dumps(body).encode("utf-8"))
            hdrs.setdefault("Content-Type", "application/json")
        try:
            conn.request(method, path, body=payload, headers=hdrs)
            resp = conn.getresponse()
            data = resp.read()
            return resp.status, dict((k.lower(), v) for k, v in resp.getheaders()), data
        finally:
            conn.close()

    def _auth(self):
        return {"Authorization": "Bearer " + API_KEY}

    def _json(self, data):
        return json.loads(data.decode("utf-8"))

    # ---------------- /health ----------------

    def test_health_no_auth_and_no_secret_leak(self):
        state, _ = self._make_state()
        port = self._start(state)
        status, _, data = self._request(port, "GET", "/health")
        self.assertEqual(status, 200)
        payload = self._json(data)
        self.assertEqual(payload["status"], "ok")
        self.assertEqual(payload["service"], "oc-ferry")
        raw = json.dumps(payload)
        self.assertNotIn(API_KEY, raw, "/health 不得泄露本机 api_key")
        self.assertNotIn("upstream_api_key", raw, "/health 不得泄露上游密钥")
        self.assertNotIn("oc-ferry-test-", raw)

    def test_health_new_non_sensitive_fields(self):
        """增量：/health 提供 4 个非敏感字段，且不含任何路径/凭据泄露。"""
        state, _ = self._make_state()
        port = self._start(state)
        status, _, data = self._request(port, "GET", "/health")
        self.assertEqual(status, 200)
        payload = self._json(data)
        for key in ("upstream_configured", "upstream_host",
                    "cache_entries", "panel_path"):
            self.assertIn(key, payload, "/health 缺少新字段 %s" % key)
        self.assertIs(payload["upstream_configured"], True)
        self.assertEqual(payload["panel_path"], "/panel")
        self.assertIsInstance(payload["cache_entries"], int)
        # upstream_host 只能是 host[:port]，不能带路径
        self.assertNotIn("/v1", payload["upstream_host"])
        raw = json.dumps(payload)
        self.assertNotIn(API_KEY, raw)
        self.assertNotIn("upstream_api_key", raw)
        self.assertNotIn("oc-ferry-test-", raw)

    def test_health_with_external_origin_still_usable(self):
        """/health 不在 /v1/*、/admin/* 保护范围 → 外部 Origin 仍 200。"""
        state, _ = self._make_state()
        port = self._start(state)
        status, _, _ = self._request(
            port, "GET", "/health",
            headers={"Origin": "https://evil.example.com"})
        self.assertEqual(status, 200)

    # ---------------- /v1/models ----------------

    def test_models_lists_only_available_and_chat_only(self):
        state, _ = self._make_state()
        port = self._start(state)
        status, _, data = self._request(port, "GET", "/v1/models",
                                        headers=self._auth())
        self.assertEqual(status, 200)
        payload = self._json(data)
        self.assertEqual(payload["object"], "list")
        ids = [item["id"] for item in payload["data"]]
        self.assertEqual(sorted(ids), ["OC · Alpha", "OC · Beta"])
        for item in payload["data"]:
            self.assertEqual(item["object"], "model")
            self.assertEqual(item["owned_by"], "oc-ferry")
            self.assertIn("oc_ferry", item)
        # 限流 / 下线模型不得出现
        self.assertNotIn("OC · Gamma", ids)
        self.assertNotIn("OC · Delta", ids)

    # ---------------- 鉴权 / Origin / 413 / 并发 ----------------

    def test_wrong_key_401(self):
        state, _ = self._make_state()
        port = self._start(state)
        for path, method in (("/v1/models", "GET"),
                             ("/admin/selection", "GET"),
                             ("/admin/import", "POST")):
            with self.subTest(path=path):
                status, _, data = self._request(
                    port, method, path, body={} if method == "POST" else None,
                    headers={"Authorization": "Bearer wrong-key"})
                self.assertEqual(status, 401)
                self.assertEqual(self._json(data)["error"]["type"], "unauthorized")

    def test_missing_key_401(self):
        state, _ = self._make_state()
        port = self._start(state)
        status, _, _ = self._request(port, "GET", "/v1/models")
        self.assertEqual(status, 401)

    def test_cross_origin_origin_403_on_data_endpoints(self):
        """数据接口（/v1/*、/admin/*）携带非同源 Origin → 403。"""
        state, _ = self._make_state()
        port = self._start(state)
        headers = dict(self._auth())
        headers["Origin"] = "https://evil.example.com"
        for path, method in (("/v1/models", "GET"),
                             ("/v1/chat/completions", "POST"),
                             ("/admin/selection", "GET"),
                             ("/admin/status", "GET")):
            with self.subTest(path=path):
                status, _, data = self._request(
                    port, method, path, body={} if method == "POST" else None,
                    headers=headers)
                self.assertEqual(status, 403)
                self.assertEqual(self._json(data)["error"]["type"],
                                 "forbidden_origin")

    def test_same_origin_is_allowed_on_data_endpoints(self):
        """同源 Origin（127.0.0.1 / localhost + 端口一致）+ 正确 Bearer → 200。

        测试用临时端口，故把 config.port 同步为实际监听端口（生产环境
        serve 本就绑定 config.port，二者天然一致）。
        """
        state, _ = self._make_state()
        port = self._start(state)
        state.config.port = port
        for origin in ("http://127.0.0.1:%d" % port,
                       "http://localhost:%d" % port):
            headers = dict(self._auth())
            headers["Origin"] = origin
            for path, method in (("/v1/models", "GET"),
                                 ("/admin/selection", "GET"),
                                 ("/admin/status", "GET")):
                with self.subTest(origin=origin, path=path):
                    status, _, _ = self._request(
                        port, method, path,
                        body={} if method == "POST" else None, headers=headers)
                    self.assertEqual(status, 200,
                                     "同源 Origin 不得被 403: %s %s" % (origin, path))

    def test_null_origin_and_port_mismatch_403_on_data_endpoints(self):
        """Origin: null / 端口不一致 / scheme 非 http(s) 均视为非同源 → 403。"""
        state, _ = self._make_state()
        port = self._start(state)
        state.config.port = port
        bad_origins = (
            "null",
            "http://127.0.0.1:%d" % (port + 1),
            "ftp://127.0.0.1:%d" % port,
            "http://evil.example.com:%d" % port,
        )
        for origin in bad_origins:
            headers = dict(self._auth())
            headers["Origin"] = origin
            with self.subTest(origin=origin):
                status, _, data = self._request(
                    port, "GET", "/v1/models", headers=headers)
                self.assertEqual(status, 403,
                                 "非同源 Origin 应被 403: %s" % origin)
                self.assertEqual(self._json(data)["error"]["type"],
                                 "forbidden_origin")

    def test_body_over_8mb_returns_413(self):
        """Content-Length 超过默认 8MB → 413，且不会读满请求体。"""
        state, _ = self._make_state()
        port = self._start(state)
        conn = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
        try:
            conn.putrequest("POST", "/v1/chat/completions")
            conn.putheader("Authorization", "Bearer " + API_KEY)
            conn.putheader("Content-Type", "application/json")
            conn.putheader("Connection", "close")
            conn.putheader("Content-Length", str(oc.DEFAULT_MAX_BODY_BYTES + 1))
            conn.endheaders()
            conn.send(b"{}")
            resp = conn.getresponse()
            data = resp.read()
            self.assertEqual(resp.status, 413)
            self.assertEqual(json.loads(data.decode("utf-8"))["error"]["type"],
                             "payload_too_large")
        finally:
            conn.close()

    def test_concurrency_limit_returns_503(self):
        """max_inflight=1 时，第二个并发请求被 503 拒绝。"""
        entered = threading.Event()
        release = threading.Event()
        ok_payload = _completion(content="done")

        def blocking(request):
            entered.set()
            release.wait(timeout=5)
            return _http(ok_payload)

        state, models = self._make_state(
            statuses=[oc.STATUS_AVAILABLE, oc.STATUS_AVAILABLE,
                      oc.STATUS_AVAILABLE, oc.STATUS_AVAILABLE],
            http_call=blocking, max_inflight=1)
        port = self._start(state)
        outcome = {}

        def slow_request():
            outcome["status"] = self._request(
                port, "POST", "/v1/chat/completions",
                body={"model": "OC · Alpha", "messages": [{"role": "user",
                                                           "content": "hi"}]},
                headers=self._auth())[0]

        worker = threading.Thread(target=slow_request, daemon=True)
        worker.start()
        try:
            self.assertTrue(entered.wait(timeout=3), "首个请求未进入 http_call")
            status, _, data = self._request(port, "GET", "/health")
            self.assertEqual(status, 503)
            self.assertEqual(self._json(data)["error"]["type"], "overloaded")
        finally:
            release.set()
            worker.join(timeout=5)
        self.assertEqual(outcome.get("status"), 200)

    # ---------------- /v1/chat/completions 非流式围栏 ----------------

    def test_nonstream_fence_rewrites_dangerous_tool_calls(self):
        upstream_payload = _completion(content="run rm -rf /", tool_calls=[
            {"id": "c1", "type": "function",
             "function": {"name": "Bash",
                          "arguments": json.dumps({"command": "rm -rf /tmp/x"})}}
        ])
        http = ScriptedHttp([_http(upstream_payload)])
        state, models = self._make_state(http_call=http)
        port = self._start(state)
        status, _, data = self._request(
            port, "POST", "/v1/chat/completions",
            body={"model": "OC · Alpha", "messages": [{"role": "user",
                                                       "content": "hi"}]},
            headers=self._auth())
        self.assertEqual(status, 200)
        self.assertEqual(len(http.calls), 1)
        payload = self._json(data)
        message = payload["choices"][0]["message"]
        self.assertNotIn("tool_calls", message, "危险 tool_calls 必须被剥离")
        envelope = json.loads(message["content"])
        self.assertEqual(envelope["type"], oc.KIND_EXTERNAL_TOOL_CALL)
        self.assertIs(payload["oc_ferry_fence"]["allowed"], False)
        self.assertEqual(payload["oc_ferry_fence"]["local_actions_rejected"], 1)
        self.assertEqual(payload["oc_ferry"]["fence"], oc.KIND_EXTERNAL_TOOL_CALL)
        # 上游 model 必须被正确映射，且不含客户端 key
        sent = json.loads(http.calls[0].body.decode("utf-8"))
        self.assertEqual(sent["model"], oc._upstream_model_for(
            models[0], state.config))
        self.assertNotIn(API_KEY, json.dumps(http.calls[0].headers))

    def test_nonstream_plain_text_normalized(self):
        http = ScriptedHttp([_http(_completion(content="plain answer"))])
        state, _ = self._make_state(http_call=http)
        port = self._start(state)
        status, _, data = self._request(
            port, "POST", "/v1/chat/completions",
            body={"model": "OC · Alpha", "messages": []}, headers=self._auth())
        self.assertEqual(status, 200)
        envelope = json.loads(self._json(data)["choices"][0]["message"]["content"])
        self.assertEqual(envelope["type"], oc.KIND_TEXT)
        self.assertEqual(envelope["content"], "plain answer")

    def test_chat_only_model_strips_tools_from_upstream(self):
        http = ScriptedHttp([_http(_completion(content="answer"))])
        state, models = self._make_state(http_call=http)
        port = self._start(state)
        # OC · Beta 是 chat_only
        status, _, _ = self._request(
            port, "POST", "/v1/chat/completions",
            body={"model": "OC · Beta", "messages": [],
                  "tools": [{"type": "function"}], "tool_choice": "auto",
                  "parallel_tool_calls": True, "functions": [{"name": "f"}]},
            headers=self._auth())
        self.assertEqual(status, 200)
        sent = json.loads(http.calls[0].body.decode("utf-8"))
        for key in ("tools", "tool_choice", "parallel_tool_calls", "functions"):
            self.assertNotIn(key, sent)

    def test_unavailable_or_unknown_model_returns_404(self):
        http = ScriptedHttp([])
        state, _ = self._make_state(http_call=http)
        port = self._start(state)
        for model in ("OC · Gamma", "OC · Delta", "does-not-exist"):
            with self.subTest(model=model):
                status, _, data = self._request(
                    port, "POST", "/v1/chat/completions",
                    body={"model": model, "messages": []}, headers=self._auth())
                self.assertEqual(status, 404)
        self.assertEqual(len(http.calls), 0, "不可用模型不得触发上游请求")

    # ---------------- 流式 SSE ----------------

    def test_stream_sse_contains_done(self):
        chunks = [
            b'data: {"choices":[{"delta":{"content":"hi"}}]}\n\n',
            b"data: [DONE]\n\n",
        ]
        http = ScriptedHttp([_http(None, status=200,
                                   headers={"content-type": "text/event-stream"},
                                   chunks=chunks)])
        state, _ = self._make_state(http_call=http)
        port = self._start(state)
        status, headers, data = self._request(
            port, "POST", "/v1/chat/completions",
            body={"model": "OC · Alpha", "messages": [], "stream": True},
            headers=self._auth())
        self.assertEqual(status, 200)
        self.assertIn(b"data: [DONE]", data)
        self.assertIn(b'"content":"hi"', data)
        self.assertEqual(headers.get("x-oc-ferry-fence"), "stream-passthrough")
        self.assertIn("text/event-stream", headers.get("content-type", ""))
        self.assertIs(http.calls[0].stream, True)

    # ---------------- /admin/* 四个路由 ----------------

    def test_admin_refresh_with_injected_catalog_is_fully_offline(self):
        """POST /admin/refresh 带 {"catalog": {...}} 离线注入目录，零真实联网。"""
        catalog = _catalog_nested({
            "free1": {"name": "Free1", "cost": _cost()},
            "free2": {"name": "Free2", "cost": _cost()},
            "paid": {"name": "Paid", "cost": _cost(input=1)},
        })

        probe_calls = []

        def probing(request):
            probe_calls.append(request)
            sent = json.loads(request.body.decode("utf-8"))
            user = sent["messages"][1]["content"]
            token = user.split("probe-")[1].split(".txt")[0]
            return _http(_completion(tool_calls=[_read_call(token=token)]))

        state, _ = self._make_state(http_call=probing)
        port = self._start(state)
        status, _, data = self._request(
            port, "POST", "/admin/refresh",
            body={"catalog": catalog}, headers=self._auth())
        self.assertEqual(status, 200)
        summary = self._json(data)
        self.assertEqual(summary["discovered"], 2)
        self.assertEqual(summary["probed"], 2)
        self.assertEqual(summary["status"]["available"], 2)
        self.assertIn("quota_notice", summary)
        # 快照已落盘，注册表已刷新；确实只有注入的 probe 请求、零真实联网
        self.assertEqual(len(state.registry.all_models()), 2)
        self.assertEqual(len(probe_calls), 2)

    def test_admin_probe_single_model(self):
        http = ScriptedHttp([_http(_completion(tool_calls=[_read_call()]))])
        state, models = self._make_state(http_call=http)
        port = self._start(state)
        status, _, data = self._request(
            port, "POST", "/admin/probe",
            body={"model": "OC · Alpha", "token": TOKEN}, headers=self._auth())
        self.assertEqual(status, 200)
        result = self._json(data)
        self.assertEqual(result["verdict"], oc.VERDICT_OK)
        self.assertEqual(result["status"], oc.STATUS_AVAILABLE)
        self.assertEqual(result["token"], TOKEN)
        self.assertEqual(len(http.calls), 1)

    def test_admin_import_writes_models_file(self):
        state, _ = self._make_state()
        port = self._start(state)
        status, _, data = self._request(port, "POST", "/admin/import",
                                        body={}, headers=self._auth())
        self.assertEqual(status, 200)
        report = self._json(data)
        self.assertEqual(report["added"], 2)
        with open(state.config.models_file, "r", encoding="utf-8") as handle:
            doc = json.load(handle)
        self.assertEqual(len(doc["models"]), 2)
        for entry in doc["models"]:
            self.assertIs(entry[oc.MANAGED_MARKER]["managed"], True)

    def test_admin_selection_get_and_post(self):
        state, models = self._make_state()
        port = self._start(state)
        status, _, data = self._request(port, "GET", "/admin/selection",
                                        headers=self._auth())
        self.assertEqual(status, 200)
        self.assertEqual(self._json(data)["selection"], "")

        status, _, data = self._request(
            port, "POST", "/admin/selection",
            body={"model": "OC · Alpha"}, headers=self._auth())
        self.assertEqual(status, 200)
        self.assertEqual(self._json(data)["selection"], models[0]["id"])

        status, _, data = self._request(port, "GET", "/admin/selection",
                                        headers=self._auth())
        self.assertEqual(self._json(data)["selection"], models[0]["id"])

        # 取消选中 + 未知模型 400
        status, _, data = self._request(
            port, "POST", "/admin/selection", body={"clear": True},
            headers=self._auth())
        self.assertEqual(self._json(data)["selection"], "")
        status, _, _ = self._request(
            port, "POST", "/admin/selection", body={"model": "unknown"},
            headers=self._auth())
        self.assertEqual(status, 400)

    def test_unknown_route_404(self):
        state, _ = self._make_state()
        port = self._start(state)
        status, _, _ = self._request(port, "GET", "/nope", headers=self._auth())
        self.assertEqual(status, 404)

    # -------------------------------------------------------------------
    # Round 2：手机端控制面板 / 新增管理接口 / 最终路由集合
    # 口径 = 当前最终源码 + docs/OC_FERRY_MOBILE.md §9 + SKILL.md + README.md
    # -------------------------------------------------------------------

    def _panel_html_bytes(self):
        with open(os.path.join(_HERE, "panel.html"), "rb") as handle:
            return handle.read()

    def test_panel_no_bearer_returns_200_html(self):
        """GET /panel 免 Bearer、免 Origin → 200 + text/html 的零数据静态页。"""
        state, _ = self._make_state()
        port = self._start(state)
        status, headers, data = self._request(port, "GET", "/panel")
        self.assertEqual(status, 200)
        self.assertIn("text/html", headers.get("content-type", ""))
        self.assertEqual(data, self._panel_html_bytes())

    def test_root_alias_returns_panel(self):
        """GET / 与 /panel 返回同一页面。"""
        state, _ = self._make_state()
        port = self._start(state)
        status, headers, data = self._request(port, "GET", "/")
        self.assertEqual(status, 200)
        self.assertIn("text/html", headers.get("content-type", ""))
        self.assertEqual(data, self._panel_html_bytes())

    def test_panel_with_external_origin_and_wrong_bearer_still_200(self):
        """面板不做 Origin 检查，错误 Bearer 也不影响静态页交付。"""
        state, _ = self._make_state()
        port = self._start(state)
        for hdrs in ({"Origin": "https://evil.example.com"},
                     {"Authorization": "Bearer wrong-key"},
                     {"Origin": "null", "Authorization": "Bearer wrong-key"},
                     {"Origin": "https://evil.example.com",
                      "Authorization": "Bearer " + API_KEY}):
            with self.subTest(headers=hdrs):
                status, _, data = self._request(port, "GET", "/panel",
                                                headers=hdrs)
                self.assertEqual(status, 200)
                self.assertEqual(data, self._panel_html_bytes())

    def test_panel_page_contains_no_live_data(self):
        """静态页零数据：不含本机 key、上游 key、模型 id 或运行时状态值。"""
        state, _ = self._make_state()
        port = self._start(state)
        status, _, data = self._request(port, "GET", "/panel")
        self.assertEqual(status, 200)
        text = data.decode("utf-8")
        for secret in (API_KEY, "upstream_api_key=", "opencode/demo/alpha",
                       "OC · Alpha", "test-api-key", "models_registered\":",
                       "cache_entries\":", "panel_unavailable"):
            self.assertNotIn(secret, text,
                             "面板静态页不得包含真实数据: %s" % secret)

    def test_panel_missing_file_returns_503_json(self):
        """panel.html 缺失/不可读 → 503 JSON panel_unavailable，不崩溃。"""
        state, _ = self._make_state()
        port = self._start(state)
        # 只改模块级常量模拟文件缺失，不动真实文件（保证隔离/幂等）
        with mock.patch.object(oc, "PANEL_FILENAME",
                               "__definitely_missing_panel__.html"):
            status, headers, data = self._request(port, "GET", "/panel")
        self.assertEqual(status, 503)
        self.assertIn("application/json", headers.get("content-type", ""))
        payload = self._json(data)
        self.assertEqual(payload["error"]["type"], "panel_unavailable")

    def test_panel_bypasses_concurrency_limit(self):
        """并发配额耗尽时，静态面板仍可打开（数据接口才是 503）。"""
        entered = threading.Event()
        release = threading.Event()

        def blocking(request):
            entered.set()
            release.wait(timeout=5)
            return _http(_completion(content="done"))

        state, _ = self._make_state(
            statuses=[oc.STATUS_AVAILABLE] * 4,
            http_call=blocking, max_inflight=1)
        port = self._start(state)
        outcome = {}

        def slow_request():
            outcome["status"] = self._request(
                port, "POST", "/v1/chat/completions",
                body={"model": "OC · Alpha", "messages": []},
                headers=self._auth())[0]

        worker = threading.Thread(target=slow_request, daemon=True)
        worker.start()
        try:
            self.assertTrue(entered.wait(timeout=3))
            status, _, _ = self._request(port, "GET", "/panel")
            self.assertEqual(status, 200, "面板在并发满时仍必须可打开")
            status, _, data = self._request(port, "GET", "/v1/models",
                                            headers=self._auth())
            self.assertEqual(status, 503)
            self.assertEqual(self._json(data)["error"]["type"], "overloaded")
        finally:
            release.set()
            worker.join(timeout=5)
        self.assertEqual(outcome.get("status"), 200)

    def test_admin_status_four_bucket_counts(self):
        """GET /admin/status 返回全量模型 + 四档计数 + selection + cache_entries。"""
        state, models = self._make_state()
        port = self._start(state)
        status, _, data = self._request(port, "GET", "/admin/status",
                                        headers=self._auth())
        self.assertEqual(status, 200)
        payload = self._json(data)
        counts = payload["counts"]
        self.assertEqual(set(counts), {
            oc.STATUS_AVAILABLE, oc.STATUS_CHAT_ONLY,
            oc.STATUS_RATE_LIMITED, oc.STATUS_OFFLINE})
        self.assertEqual(counts[oc.STATUS_AVAILABLE], 1)
        self.assertEqual(counts[oc.STATUS_CHAT_ONLY], 1)
        self.assertEqual(counts[oc.STATUS_RATE_LIMITED], 1)
        self.assertEqual(counts[oc.STATUS_OFFLINE], 1)
        self.assertEqual(len(payload["models"]), 4)
        self.assertEqual(payload["cache_entries"], 4)
        self.assertIn("selection", payload)
        self.assertEqual(payload["panel_path"], "/panel")
        by_id = dict((item["id"], item) for item in payload["models"])
        self.assertEqual(by_id[models[0]["id"]]["status"], oc.STATUS_AVAILABLE)
        self.assertEqual(by_id[models[2]["id"]]["status"], oc.STATUS_RATE_LIMITED)

    def test_admin_status_requires_bearer_but_not_origin_fence(self):
        """/admin/status 属受保护路径：缺 Bearer→401，非同源 Origin→403。"""
        state, _ = self._make_state()
        port = self._start(state)
        status, _, _ = self._request(port, "GET", "/admin/status")
        self.assertEqual(status, 401)
        status, _, _ = self._request(
            port, "GET", "/admin/status",
            headers={"Authorization": "Bearer " + API_KEY,
                     "Origin": "https://evil.example.com"})
        self.assertEqual(status, 403)

    def test_admin_cleanup_route_removed_returns_404(self):
        """路由收敛：不再有 POST /admin/cleanup（清场只走 CLI / serve 退出）。"""
        state, _ = self._make_state()
        port = self._start(state)
        status, _, data = self._request(port, "POST", "/admin/cleanup",
                                        body={}, headers=self._auth())
        self.assertEqual(status, 404)
        self.assertEqual(self._json(data)["error"]["type"], "not_found")

    def test_selection_clear_explicit(self):
        """POST /admin/selection {"clear": true} 与空 model 均取消选中。"""
        state, models = self._make_state()
        port = self._start(state)
        self._request(port, "POST", "/admin/selection",
                      body={"model": "OC · Alpha"}, headers=self._auth())
        for body in ({"clear": True}, {"model": ""}):
            with self.subTest(body=body):
                status, _, data = self._request(
                    port, "POST", "/admin/selection", body=body,
                    headers=self._auth())
                self.assertEqual(status, 200)
                self.assertEqual(self._json(data)["selection"], "")
                status, _, data = self._request(
                    port, "GET", "/admin/selection", headers=self._auth())
                self.assertEqual(self._json(data)["selection"], "")

    def test_route_set_is_eight_data_endpoints_plus_panel(self):
        """路由收敛核对：8 个数据/管理接口 + 1 个面板入口，无多余路由。"""
        state, _ = self._make_state()
        port = self._start(state)
        h = self._auth()
        expected = [
            ("GET", "/health", None, 200),
            ("GET", "/panel", None, 200),
            ("GET", "/", None, 200),
            ("GET", "/v1/models", None, 200),
            ("GET", "/admin/selection", None, 200),
            ("GET", "/admin/status", None, 200),
            ("POST", "/admin/selection", {"clear": True}, 200),
            ("POST", "/v1/chat/completions",
             {"model": "does-not-exist", "messages": []}, 404),
        ]
        for method, path, body, expected_status in expected:
            with self.subTest(method=method, path=path):
                status, _, _ = self._request(port, method, path, body=body,
                                             headers=h)
                self.assertEqual(status, expected_status)
        # 被移除的路由
        status, _, _ = self._request(port, "POST", "/admin/cleanup", body={},
                                     headers=h)
        self.assertEqual(status, 404)


# ===========================================================================
# 7. 零副作用 / 纯逻辑路径零联网 / 配置与 CLI
# ===========================================================================

_ZERO_SIDE_EFFECT_SCRIPT = r'''
import importlib.util, os, sys, socket, secrets, urllib.request
sys.dont_write_bytecode = True
tmp, src = sys.argv[1], sys.argv[2]
calls = []
def boom(name):
    def _f(*a, **k):
        calls.append(name)
        raise AssertionError("import-time side effect: " + name)
    return _f
socket.create_connection = boom("socket.create_connection")
urllib.request.urlopen = boom("urlopen")
secrets.token_urlsafe = boom("token_urlsafe")
secrets.token_hex = boom("token_hex")
os.system = boom("os.system")
os.popen = boom("os.popen")
env_dir = os.environ.get("OC_FERRY_DATA_DIR", "")
before = sorted(os.listdir(tmp)) if os.path.isdir(tmp) else []
spec = importlib.util.spec_from_file_location("oc_ferry", src)
mod = importlib.util.module_from_spec(spec)
sys.modules["oc_ferry"] = mod
spec.loader.exec_module(mod)
after = sorted(os.listdir(tmp)) if os.path.isdir(tmp) else []
assert not calls, calls
assert after == before, (before, after)
assert not os.path.isdir(env_dir), env_dir
assert mod.FerryConfig().data_dir == ""
assert callable(mod.discover_free_models)
print("OK")
'''


class TestZeroSideEffectsAndOfflineCommands(_BaseTest):

    def test_import_has_zero_side_effects_in_fresh_interpreter(self):
        env = dict(os.environ)
        env["OC_FERRY_DATA_DIR"] = self.path("must-not-be-created")
        env["HOME"] = self.tmp
        env["USERPROFILE"] = self.tmp
        proc = subprocess.run(
            [sys.executable, "-c", _ZERO_SIDE_EFFECT_SCRIPT, self.tmp, _SOURCE],
            capture_output=True, text=True, timeout=30, env=env)
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        self.assertIn("OK", proc.stdout)

    def test_offline_commands_never_call_network(self):
        """doctor/status/cleanup/import + 纯逻辑路径：注入一调用即抛异常的 http_call。"""
        cfg = _make_config(self.tmp)
        config_file = self.path("config.json")
        with open(config_file, "w", encoding="utf-8") as handle:
            json.dump({"data_dir": self.tmp, "upstream_base_url": "",
                       "api_key": API_KEY}, handle)

        def forbidden(request):
            raise AssertionError("纯逻辑路径竟然发起了网络调用")

        commands = [
            lambda: oc.cmd_doctor(cfg),
            lambda: oc.cmd_status(cfg),
            lambda: oc.cmd_cleanup(cfg),
            lambda: oc.cmd_import(cfg),
        ]
        mains = [
            lambda: oc.main(["--config", config_file, "doctor"]),
            lambda: oc.main(["--config", config_file, "status"]),
            lambda: oc.main(["--config", config_file, "cleanup"]),
            lambda: oc.main(["--config", config_file, "import"]),
        ]
        with mock.patch.object(oc, "urllib_http_call", forbidden), \
             mock.patch.object(oc.urllib.request, "urlopen", forbidden), \
             mock.patch.object(oc.socket, "create_connection", forbidden):
            with contextlib.redirect_stdout(io.StringIO()), \
                 contextlib.redirect_stderr(io.StringIO()):
                for fn in commands + mains:
                    self.assertEqual(fn(), 0)
                # 纯逻辑函数本身也零联网
                models = oc.discover_free_models(_four_model_catalog())
                body = oc.build_probe_body("m", TOKEN)
                verdict = oc.judge_probe(_completion(tool_calls=[_read_call()]), TOKEN)
                report = oc.merge_models_file(cfg.models_file, [])
                report2 = oc.cleanup_models_file(cfg.models_file)
                fence_result = oc.ApprovalFence().evaluate({"content": "shell rm -rf /"})
                oc.ApprovalFence().rewrite(_completion(content="x"), fence_result)
                cache = oc.ProbeCache(cfg.cache_file)
                cache.all_entries()
                cache.get("x", "fp")
                state = oc.FerryState(cfg, forbidden, api_key=API_KEY,
                                      models=models)
                state.available_models()
                state.status_snapshot()
                state.import_models()
                state.cleanup()
                state.get_selection()
        self.assertEqual(verdict.code, oc.VERDICT_OK)
        self.assertEqual(len(body["tools"]), 5)
        self.assertIsInstance(report, oc.WriteReport)
        self.assertIsInstance(report2, oc.WriteReport)


class TestConfigAndCLI(_BaseTest):

    def test_config_example_json_is_valid_and_field_set_matches_ferry_config(self):
        with open(_CONFIG_EXAMPLE, "r", encoding="utf-8") as handle:
            raw = json.load(handle)
        self.assertIsInstance(raw, dict)
        fields = set(f.name for f in dataclasses.fields(oc.FerryConfig))
        self.assertEqual(set(raw.keys()), fields,
                         "config.example.json 字段集合必须与 FerryConfig 完全一致")
        self.assertEqual(raw["host"], "127.0.0.1")
        self.assertEqual(raw["max_body_bytes"], 8 * 1024 * 1024)

    def test_load_config_from_example_resolves_paths(self):
        cfg = oc.load_config(_CONFIG_EXAMPLE, data_dir_override=self.tmp)
        self.assertEqual(cfg.data_dir, os.path.abspath(self.tmp))
        self.assertTrue(cfg.models_file.startswith(os.path.abspath(self.tmp)))
        self.assertTrue(cfg.api_key_file.startswith(os.path.abspath(self.tmp)))
        self.assertEqual(cfg.host, "127.0.0.1")
        self.assertEqual(cfg.port, 41980)

    def test_default_api_key_file_uses_hyphenated_name(self):
        """Round 2：默认 Bearer key 落点 <data_dir>/api-key（连字符），非 api_key。"""
        cfg = oc.FerryConfig(data_dir=self.tmp).resolved_paths()
        self.assertEqual(os.path.basename(cfg.api_key_file), "api-key")
        self.assertNotEqual(os.path.basename(cfg.api_key_file), "api_key")
        self.assertEqual(os.path.dirname(cfg.api_key_file),
                         os.path.abspath(self.tmp))
        custom = os.path.join(self.tmp, "my-key")
        self.assertEqual(
            oc.FerryConfig(data_dir=self.tmp,
                           api_key_file=custom).resolved_paths().api_key_file,
            custom, "显式指定的 api_key_file 不应被默认路径覆盖")

    def test_from_dict_ignores_unknown_and_coerces_types(self):
        cfg = oc.FerryConfig.from_dict({
            "port": "12345", "max_inflight": "9", "embed_api_key": 0,
            "upstream_model_map": {"a": "b"}, "unknown_key": "ignored",
            "data_dir": None,
        })
        self.assertEqual(cfg.port, 12345)
        self.assertEqual(cfg.max_inflight, 9)
        self.assertIs(cfg.embed_api_key, False)
        self.assertEqual(cfg.upstream_model_map, {"a": "b"})
        self.assertEqual(cfg.data_dir, "")
        self.assertFalse(hasattr(cfg, "unknown_key"))

    def test_validate_rejects_non_loopback_and_bad_values(self):
        base = dict(data_dir=self.tmp)
        bad = [
            {"host": "0.0.0.0"}, {"port": 0}, {"port": 70000},
            {"request_timeout": 0}, {"probe_concurrency": 0},
            {"max_inflight": 0}, {"max_body_bytes": 10},
            {"success_ttl_minutes": 0}, {"load_ttl_minutes": -1},
            {"upstream_base_url": "ftp://x"}, {"catalog_url": "not-a-url"},
        ]
        for override in bad:
            with self.subTest(override=override):
                kwargs = dict(base)
                kwargs.update(override)
                with self.assertRaises(oc.ConfigError):
                    oc.FerryConfig(**kwargs).validate()

    def test_cli_subcommands_parse(self):
        parser = oc.build_parser()
        commands = ["doctor", "refresh", "probe", "import", "serve",
                    "status", "cleanup", "export"]
        for command in commands:
            with self.subTest(command=command):
                argv = ["probe", "opencode/demo/m1", "--token", "T"] \
                    if command == "probe" else [command]
                args = parser.parse_args(argv)
                self.assertEqual(args.command, command)
        args = parser.parse_args(["--config", "c.json", "--data-dir", "d",
                                  "probe", "m", "--token", "T"])
        self.assertEqual(args.command, "probe")
        self.assertEqual(args.model, "m")
        self.assertEqual(args.token, "T")
        self.assertEqual(args.config, "c.json")
        self.assertEqual(args.data_dir, "d")
        # 子命令集合恰好 8 个（含文档导出命令 export）
        subs = [action for action in parser._actions
                if hasattr(action, "choices") and action.choices
                and "doctor" in action.choices]
        self.assertEqual(len(subs), 1)
        self.assertEqual(set(subs[0].choices.keys()), set(commands))
        exp = parser.parse_args(["export", "--format", "csv", "--online",
                                 "--output", "out.csv", "--no-cache"])
        self.assertEqual(exp.command, "export")
        self.assertEqual(exp.format, "csv")
        self.assertEqual(exp.output, "out.csv")
        self.assertTrue(exp.online)
        self.assertTrue(exp.no_cache)
        with self.assertRaises(SystemExit):
            parser.parse_args(["export", "--format", "pdf"])

    def test_main_without_command_returns_zero(self):
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(oc.main([]), 0)


class TestExport(unittest.TestCase):
    """文档导出（``export``）：全程离线，网络一律用注入的 HttpCall 替身。"""

    def _config(self, tmp):
        cfg = oc.FerryConfig(data_dir=tmp,
                             catalog_url="https://example.invalid/api.json")
        return cfg.resolved_paths()

    def _catalog(self, tmp):
        """写一份含 2 免费 / 1 收费 / 1 deprecated 的本地目录。"""
        path = os.path.join(tmp, "catalog.json")
        catalog = _catalog_nested({
            "free-a": {"name": "Free A", "cost": dict(FREE_COST)},
            "free-b": {"name": "Free B|pipe", "cost": dict(FREE_COST)},
            "paid": {"name": "Paid", "cost": _cost(input=1)},
            "old": {"name": "Old", "cost": dict(FREE_COST),
                    "deprecated": True},
        })
        with open(path, "w", encoding="utf-8") as handle:
            json.dump(catalog, handle)
        return path

    @staticmethod
    def _exploding():
        """返回一个「一被调用就抛异常」的 http_call 与调用记录。"""
        calls = []

        def call(request):
            calls.append(request)
            raise AssertionError("export must not hit the network")

        return call, calls

    def test_markdown_from_local_catalog_is_offline(self):
        with tempfile.TemporaryDirectory() as tmp:
            cfg, cat = self._config(tmp), self._catalog(tmp)
            out = os.path.join(tmp, "free.md")
            call, calls = self._exploding()
            with contextlib.redirect_stdout(io.StringIO()) as buf:
                rc = oc.cmd_export(cfg, call, "md", out, cat, False, False)
            self.assertEqual(rc, 0)
            self.assertEqual(calls, [])
            with open(out, "r", encoding="utf-8") as handle:
                text = handle.read()
            self.assertIn("# OC Ferry 免费模型清单", text)
            self.assertIn("opencode/demo/free-a", text)
            self.assertIn("opencode/demo/free-b", text)
            self.assertNotIn("opencode/demo/paid", text)
            self.assertNotIn("opencode/demo/old", text)
            self.assertIn("免费模型总数 | 2", text)
            self.assertIn("判定规则", text)
            self.assertIn("诚实交代", text)
            self.assertIn('"network_used": false', buf.getvalue())

    def test_json_and_csv_formats(self):
        with tempfile.TemporaryDirectory() as tmp:
            cfg, cat = self._config(tmp), self._catalog(tmp)
            call, calls = self._exploding()
            jpath = os.path.join(tmp, "free.json")
            cpath = os.path.join(tmp, "free.csv")
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(oc.cmd_export(cfg, call, "json", jpath,
                                               cat, False, True), 0)
                self.assertEqual(oc.cmd_export(cfg, call, "csv", cpath,
                                               cat, False, True), 0)
            self.assertEqual(calls, [])
            with open(jpath, "r", encoding="utf-8") as handle:
                payload = json.load(handle)
            self.assertEqual(payload["total"], 2)
            self.assertEqual(payload["providers"], 1)
            self.assertEqual(len(payload["models"]), 2)
            self.assertEqual(payload["counts"][oc.STATUS_UNKNOWN], 2)
            with open(cpath, "r", encoding="utf-8") as handle:
                lines = handle.read().strip().split("\n")
            self.assertEqual(len(lines), 3)
            self.assertTrue(lines[0].startswith("id,upstream_model,provider"))
            self.assertIn("Free B|pipe", lines[0] + "\n" + "\n".join(lines[1:]))

    def test_online_uses_injected_http_call(self):
        with tempfile.TemporaryDirectory() as tmp:
            cfg, cat = self._config(tmp), self._catalog(tmp)
            with open(cat, "rb") as handle:
                body = handle.read()
            calls = []

            def call(request):
                calls.append(request)
                return oc.HttpResponse(status=200, body=body)

            out = os.path.join(tmp, "free.md")
            with contextlib.redirect_stdout(io.StringIO()) as buf:
                rc = oc.cmd_export(cfg, call, "md", out, "", True, True)
            self.assertEqual(rc, 0)
            self.assertEqual(len(calls), 1)
            self.assertEqual(calls[0].method, "GET")
            self.assertEqual(calls[0].url, "https://example.invalid/api.json")
            self.assertIn('"network_used": true', buf.getvalue())

    def test_local_catalog_wins_over_online_flag(self):
        with tempfile.TemporaryDirectory() as tmp:
            cfg, cat = self._config(tmp), self._catalog(tmp)
            out = os.path.join(tmp, "free.md")
            call, calls = self._exploding()
            with contextlib.redirect_stdout(io.StringIO()):
                rc = oc.cmd_export(cfg, call, "md", out, cat, True, True)
            self.assertEqual(rc, 0)
            self.assertEqual(calls, [])

    def test_no_data_returns_one_without_network(self):
        with tempfile.TemporaryDirectory() as tmp:
            cfg = self._config(tmp)
            call, calls = self._exploding()
            with contextlib.redirect_stderr(io.StringIO()), \
                    contextlib.redirect_stdout(io.StringIO()):
                rc = oc.cmd_export(cfg, call, "md", "", "", False, True)
            self.assertEqual(rc, 1)
            self.assertEqual(calls, [])

    def test_rows_merge_cache_status_and_escape_pipe(self):
        with tempfile.TemporaryDirectory() as tmp:
            cat = self._catalog(tmp)
            with open(cat, "r", encoding="utf-8") as handle:
                models = oc.discover_free_models(json.load(handle))
            entries = {
                "opencode/demo/free-b": {
                    "status": oc.STATUS_AVAILABLE, "verdict": "ok",
                    "tools_enabled": True, "checked_at": 1794137335,
                },
            }
            rows = oc.build_export_rows(models, entries)
            by_id = dict((row["id"], row) for row in rows)
            self.assertEqual(by_id["opencode/demo/free-b"]["status"],
                             oc.STATUS_AVAILABLE)
            self.assertEqual(by_id["opencode/demo/free-b"]["status_label"], "可用")
            self.assertTrue(by_id["opencode/demo/free-b"]["tools"])
            self.assertEqual(by_id["opencode/demo/free-a"]["status"],
                             oc.STATUS_UNKNOWN)
            text = oc.render_markdown(rows, "local", "2026-11-08 20:00:00")
            self.assertIn("Free B\\|pipe", text)
            self.assertIn("可用", text)
            self.assertEqual(text.count("| 是 |"), 1)

    def test_write_export_creates_parent_and_leaves_no_temp(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "sub", "doc.md")
            oc.write_export(path, "hello\nworld\n")
            with open(path, "rb") as handle:
                self.assertEqual(handle.read(), b"hello\nworld\n")
            leftovers = [name for name in os.listdir(os.path.dirname(path))
                         if name.startswith(".oc-ferry-export-")]
            self.assertEqual(leftovers, [])

    def test_online_http_failure_is_reported(self):
        with tempfile.TemporaryDirectory() as tmp:
            cfg = self._config(tmp)
            calls = []

            def call(request):
                calls.append(request)
                return oc.HttpResponse(status=503, body=b"{}")

            with contextlib.redirect_stderr(io.StringIO()) as err, \
                    contextlib.redirect_stdout(io.StringIO()):
                rc = oc.cmd_export(cfg, call, "md", "", "", True, True)
            self.assertEqual(rc, 1)
            self.assertEqual(len(calls), 1)
            self.assertIn("HTTP 503", err.getvalue())


if __name__ == "__main__":
    unittest.main(verbosity=2)
