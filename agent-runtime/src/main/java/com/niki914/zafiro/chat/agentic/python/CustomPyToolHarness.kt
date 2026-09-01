package com.niki914.zafiro.chat.agentic.python

import com.niki914.zafiro.chat.agentic.python.CustomPyToolHarness.buildIntrospection
import com.niki914.zafiro.chat.agentic.python.CustomPyToolHarness.buildRunner
import java.util.Base64

/**
 * CustomPyTool 的两段代码拼接器：
 * - [buildRunner]：LLM 调用时的执行 harness——b64 解出参数 → 定义 main 的工具代码 → main(**args)。
 *   结果经 stdout 回传（runtime.py 的 OutputBudget 已做 50KB 截断）。
 * - [buildIntrospection]：write 校验时的签名反射——提取 main 的基本类型标注与 docstring，
 *   输出一行 JSON：{"error":...} 或 {"description":..., "schema":...}。
 *
 * 两段都用 Base64 嵌入内容，避免任何引号/三引号冲突。
 */
object CustomPyToolHarness {

    fun buildRunner(code: String, argumentsJson: String): String {
        val argsB64 = encode(argumentsJson.ifBlank { "{}" })
        val codeB64 = encode(code)
        return """
            |import base64, json as _json
            |_args = _json.loads(base64.b64decode('$argsB64').decode('utf-8'))
            |exec(base64.b64decode('$codeB64').decode('utf-8'), globals())
            |main(**_args)
        """.trimMargin()
    }

    fun buildIntrospection(code: String): String {
        val codeB64 = encode(code)
        return """
            |import base64, inspect, json
            |_code = base64.b64decode('$codeB64').decode('utf-8')
            |_ns = {}
            |try:
            |    exec(compile(_code, '<pytool>', 'exec'), _ns)
            |except SyntaxError as e:
            |    print(json.dumps({"error": "SyntaxError", "line": e.lineno, "message": e.msg}))
            |    raise SystemExit
            |except BaseException as e:
            |    print(json.dumps({"error": type(e).__name__, "message": str(e)}))
            |    raise SystemExit
            |_main = _ns.get("main")
            |if not callable(_main):
            |    print(json.dumps({"error": "MISSING_MAIN", "message": "Tool must define a callable main(...)."}))
            |    raise SystemExit
            |_TYPE_MAP = {"str": "string", "int": "integer", "float": "number", "bool": "boolean"}
            |_props, _required, _bad = {}, [], []
            |for _name, _param in inspect.signature(_main).parameters.items():
            |    _tname = getattr(_param.annotation, "__name__", None)
            |    if _tname not in _TYPE_MAP:
            |        _bad.append(_name)
            |        continue
            |    _prop = {"type": _TYPE_MAP[_tname]}
            |    if _param.default is not inspect.Parameter.empty:
            |        _prop["description"] = "default: %r" % (_param.default,)
            |    else:
            |        _required.append(_name)
            |    _props[_name] = _prop
            |if _bad:
            |    print(json.dumps({"error": "UNANNOTATED_PARAMS", "message": "Parameters need basic type annotations (str/int/float/bool): " + ", ".join(_bad)}))
            |    raise SystemExit
            |print(json.dumps({"description": (inspect.getdoc(_main) or "").strip(), "schema": {"type": "object", "properties": _props, "required": _required}}))
        """.trimMargin()
    }

    private fun encode(text: String): String =
        Base64.getEncoder().encodeToString(text.encodeToByteArray())
}
