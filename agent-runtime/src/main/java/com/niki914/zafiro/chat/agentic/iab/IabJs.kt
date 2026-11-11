package com.niki914.zafiro.chat.agentic.iab

/**
 * IAB（内置浏览器）的纯 JS 载荷构造器。
 *
 * 设计原则（对应 ZCode IAB 的 evaluate/query/input 能力面）：
 * 1. 每个构造器产出一个「自包含 IIFE + JSON.stringify 结果」的 JS 字符串，
 *    统一结果信封 `{ok, ...}` / `{ok:false, error}`，由 [IabManager] 解析；
 * 2. 零 Android 依赖（纯字符串逻辑），可在 JVM 单测中直接验证；
 * 3. 注入的字符串参数一律先经 [jsQuote] 转义成 JS 单引号字面量、再存入
 *    本地变量后使用，防止不可信输入（agent 传入的 selector / value）破坏 JS。
 */
object IabJs {

    /** 把 Kotlin 字符串变成 JS 单引号字符串字面量（转义 \ ' \n \r \t 及 <0x20 控制符）。 */
    fun jsQuote(value: String): String {
        val sb = StringBuilder(value.length + 8)
        sb.append('\'')
        for (c in value) {
            when (c) {
                '\'' -> sb.append("\\'")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\u0008' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> {
                    if (c.code < 0x20) sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
                }
            }
        }
        sb.append('\'')
        return sb.toString()
    }

    /**
     * 任意表达式求值信封（对应 ZCode 的 evaluate）：
     * - awaitPromise=false：同步 IIFE 直接求值表达式；返回值（经 __conv 归一后 JSON.stringify）
     *   作为 evaluateJavascript 的回调值——Android 会把 JS 字符串结果再包一层引号
     *   （"\"{...}\""），由管理器侧 unwrapJsonStringLiteral 剥回；
     * - awaitPromise=true：IIFE 立即返回 "__ZAFIRO_EVAL_PENDING__:<id>"，实际结果
     *   异步写入 window.__zafiroEvalResults[id]（同样 JSON.stringify），由
     *   [evaluatePollEnvelope] 轮询取回并删除。这样 Promise 的异步结果不会以 "{}" 形式丢失；
     * - 结果经 __conv 归一：DOM 节点 → 结构化描述符，NodeList/Array → 逐元素归一（截 200），
     *   普通对象 → 深 JSON 兜底；
     * - 运行期异常折进 {ok:false,error}；纯语法错会让 evaluateJavascript 返回 null，
     *   由管理器侧按 JS_ENGINE_ERROR 处理。
     */
    fun evaluateEnvelope(expression: String, awaitPromise: Boolean): String {
        val valStmt = if (awaitPromise) "await (async () => { ${expression} })()" else "(${expression})"
        val conv = """
            const __conv = (v) => {
                if (v === null) return null;
                if (v === undefined) return "undefined";
                const t = typeof v;
                if (t === "string" || t === "number" || t === "boolean") return v;
                if (t === "function" || t === "symbol" || t === "bigint") return String(v);
                if (typeof Node !== "undefined" && v instanceof Node) {
                  var rect = null;
                  try { var r = v.getBoundingClientRect(); rect = { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) }; } catch (e) {}
                  return { node: v.tagName ? v.tagName.toLowerCase() : "#node", id: v.id || "", class: v.className || "", text: (v.textContent || "").slice(0, 500), rect: rect };
                }
                if (Array.isArray(v) || (typeof NodeList !== "undefined" && v instanceof NodeList)) {
                  return Array.from(v).slice(0, 200).map(__conv);
                }
                try { return JSON.parse(JSON.stringify(v)); } catch (e) { try { return String(v); } catch (e2) { return "<unserializable>"; } }
              };
        """.trimIndent()
        // 两个 IIFE 变体都不走 trimIndent：模板里插值了已 trimIndent 的 $conv
        // （行从列 0 起）会让外层最小公共缩进归 0、trimIndent 变空操作，源缩进会
        // 原样漏进载荷。改成第一行即内容行、无插值的裸字符串拼接，保证列 0 输出。
        val header = "(function() {\n$conv\n"
        val tail = "\n})()"
        return if (!awaitPromise) {
            header +
                "  try {\n" +
                "    const __val = ${valStmt};\n" +
                "    return JSON.stringify({ ok: true, value: __conv(__val) });\n" +
                "  } catch (e) {\n" +
                "    return JSON.stringify({ ok: false, error: String((e && e.message) ? e.message : e) });\n" +
                "  }\n" + tail
        } else {
            header +
                "  const __id = 'zafiro-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2);\n" +
                "  window.__zafiroEvalResults = window.__zafiroEvalResults || {};\n" +
                "  const __results = window.__zafiroEvalResults;\n" +
                "  (async () => {\n" +
                "    try {\n" +
                "      const __val = ${valStmt};\n" +
                "      __results[__id] = JSON.stringify({ ok: true, value: __conv(__val) });\n" +
                "    } catch (e) {\n" +
                "      __results[__id] = JSON.stringify({ ok: false, error: String((e && e.message) ? e.message : e) });\n" +
                "    }\n" +
                "  })();\n" +
                "  return '__ZAFIRO_EVAL_PENDING__:' + __id;\n" + tail
        }
    }

    /** 轮询 [evaluateEnvelope]（awaitPromise=true）写入 window.__zafiroEvalResults 的结果。 */
    internal fun evaluatePollEnvelope(id: String): String {
        val safeId = id.replace("\\", "\\\\").replace("'", "\\'")
        return """
            (function() {
              const __results = window.__zafiroEvalResults;
              const __value = __results ? __results['$safeId'] : undefined;
              if (__value === undefined) return null;
              delete __results['$safeId'];
              return __value;
            })()
        """.trimIndent()
    }

    /** 控制台钩子：幂等安装（重复注入直接返回 "installed"），把 console.* 折进 500 条环形缓冲。 */
    fun consoleHook(): String = """
        (function(){
          if (window.__zafiroConsoleHook) return "installed";
          window.__zafiroConsoleHook = true;
          var ring = [];
          var push = function(level, args){
            try {
              var s = Array.prototype.map.call(args, function(a){
                try { return (typeof a === "object" && a !== null) ? JSON.stringify(a) : String(a); } catch (e) { return String(a); }
              }).join(" ");
              ring.push({ t: Date.now(), level: level, msg: s.slice(0, 2000) });
              if (ring.length > 500) ring.splice(0, ring.length - 500);
            } catch (e) {}
          };
          var install = function(){
            if (!window.__zafiroConsole) {
              window.__zafiroConsole = ring;
              ["log","info","warn","error","debug"].forEach(function(m){
                var orig = console[m];
                if (orig) console[m] = function(){ push(m, arguments); return orig.apply(console, arguments); };
              });
            }
          };
          if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", install);
          else install();
          return "installed";
        })()
    """.trimIndent()

    /** 控制台读取：最近 [limit] 条（未安装钩子时 files 为空数组）。 */
    fun consoleRead(limit: Int): String =
        "JSON.stringify((function(){ var r = window.__zafiroConsole || []; return { count: r.length, lines: r.slice(-${limit.coerceIn(1, 500)}).map(function(e){ return e.level + \": \" + e.msg; }) }; })())"

    /** querySelector：CSS 选择器命中列表（tag/id/text/attrs 结构，截 [limit] 个）。 */
    fun querySelector(selector: String, limit: Int): String {
        val sel = jsQuote(selector)
        val n = limit.coerceIn(1, 500)
        return """
            (function(){
              try {
                var sel = ${sel};
                var all = document.querySelectorAll(sel);
                var nodes = Array.from(all).slice(0, ${n}).map(function(n){
                  var attrs = {};
                  for (var i = 0; i < n.attributes.length; i++) { var a = n.attributes[i]; attrs[a.name] = String(a.value).slice(0, 200); }
                  return { tag: n.tagName.toLowerCase(), id: n.id || "", text: (n.textContent || "").trim().slice(0, 300), attrs: attrs };
                });
                return JSON.stringify({ ok: true, count: all.length, nodes: nodes });
              } catch (e) { return JSON.stringify({ ok: false, error: String(e) }); }
            })()
        """.trimIndent()
    }

    /** 正文文本（innerText，截断 [limit] 字符）。 */
    fun pageText(limit: Int): String = """
        (function(){ try { var t = document.body ? document.body.innerText : (document.documentElement ? document.documentElement.innerText : ""); t = (t || "").replace(/\n{3,}/g, "\n\n"); return JSON.stringify({ ok: true, text: t.slice(0, ${limit.coerceIn(0, 200_000)}) }); } catch (e) { return JSON.stringify({ ok: false, error: String(e) }); } })()
    """.trimIndent()

    /** 整页 HTML（outerHTML，截断 [limit] 字符）。 */
    fun pageHtml(limit: Int): String = """
        (function(){ try { var h = document.documentElement ? document.documentElement.outerHTML : ""; return JSON.stringify({ ok: true, html: h.slice(0, ${limit.coerceIn(0, 200_000)}) }); } catch (e) { return JSON.stringify({ ok: false, error: String(e) }); } })()
    """.trimIndent()

    /** 按选择器点击：focus + click + blur（兼容 React 合成事件）。 */
    fun click(selector: String): String {
        val sel = jsQuote(selector)
        return """
            (function(){
              try {
                var sel = ${sel};
                var el = document.querySelector(sel);
                if (!el) return JSON.stringify({ ok: false, error: "selector not found: " + sel });
                el.focus && el.focus();
                el.click();
                return JSON.stringify({ ok: true, tag: el.tagName.toLowerCase(), id: el.id || "" });
              } catch (e) { return JSON.stringify({ ok: false, error: String(e) }); }
            })()
        """.trimIndent()
    }

    /** 按可见文本点击（先精确匹配，退化到 startsWith；作用域限定可交互元素，避免命中外层容器）。 */
    fun clickText(text: String): String {
        val want = jsQuote(text)
        return """
            (function(){
              try {
                var want = ${want};
                var cands = Array.from(document.querySelectorAll("a,button,input[type=submit],input[type=button],label,[role=button],[role=menuitem]")).filter(function(el){
                  var t = (el.textContent || "").trim();
                  return t === want || t.indexOf(want) === 0;
                });
                if (!cands.length) cands = Array.from(document.querySelectorAll("span,div")).filter(function(el){
                  return el.children.length === 0 && (el.textContent || "").trim() === want;
                });
                if (!cands.length) return JSON.stringify({ ok: false, error: "no element with text: " + want });
                var el = cands[cands.length - 1];
                el.scrollIntoView && el.scrollIntoView({ block: "center" });
                el.click();
                return JSON.stringify({ ok: true, tag: el.tagName.toLowerCase(), text: (el.textContent || "").trim().slice(0, 120) });
              } catch (e) { return JSON.stringify({ ok: false, error: String(e) }); }
            })()
        """.trimIndent()
    }

    /**
     * 坐标点击（r16 点 6a：elementFromPoint 命中测试 + Pointer/MouseEvent 序列）。
     * 命中元素上派发 pointerdown/mousedown/pointerup/mouseup/click（PointerEvent
     * 可用时用之，否则回退 MouseEvent）。
     *
     * 结果含几何与可交互性线索（r16 点 6a）：`rect`（元素当前视口包围盒，
     * 取不到为 null）、`interactive`（命中元素带 role / tabindex / aria 属性 /
     * contentEditable / onclick 或计算样式 cursor:pointer 之一即 true）、
     * `ariaRole`（命中元素的 role 属性，无则空串）。agent 可用这些字段
     * 判断坐标是否落在可交互目标上，再决定是否补做选择器点击。
     */
    fun clickAt(x: Int, y: Int): String = """
        (function(){
          try {
            var x = ${x}; var y = ${y};
            var el = document.elementFromPoint(x, y);
            if (!el) return JSON.stringify({ ok: false, error: "no element at point (" + x + ", " + y + ")" });
            var opts = { bubbles: true, cancelable: true, clientX: x, clientY: y };
            ["pointerdown","mousedown","pointerup","mouseup","click"].forEach(function(name){
              var Ctor = (name.indexOf("pointer") === 0 && typeof PointerEvent !== "undefined") ? PointerEvent : MouseEvent;
              el.dispatchEvent(new Ctor(name, opts));
            });
            var ariaRole = "";
            try { ariaRole = el.getAttribute("role") || ""; } catch (e) {}
            var interactive = false;
            try {
              interactive = !!(
                el.hasAttribute && (el.hasAttribute("role") || el.hasAttribute("aria-label") || el.hasAttribute("onclick")) ||
                (el.tabIndex !== undefined && el.tabIndex > -1) ||
                (el.contentEditable === "true") ||
                (typeof getComputedStyle !== "undefined" && getComputedStyle(el).cursor === "pointer")
              );
            } catch (e) {}
            var cls = "";
            try { cls = (typeof el.className === "string") ? el.className : (el.getAttribute("class") || ""); } catch (e) {}
            var rect = null;
            try { var r = el.getBoundingClientRect(); rect = { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) }; } catch (e) {}
            return JSON.stringify({ ok: true, tag: el.tagName.toLowerCase(), id: el.id || "", class: cls.slice(0, 120), rect: rect, interactive: interactive, ariaRole: ariaRole });
          } catch (e) { return JSON.stringify({ ok: false, error: String(e) }); }
        })()
    """.trimIndent()

    /**
     * r16 点 6：未受信指针流原语（isTrusted === false；纯 JS 派发，
     * 对受信手势有要求的目标可能无效——见 BrowserBuiltin 的 IAB_POINTER_UNTRUSTED 提示）。
     *
     * 四个构造器共用 [__zev] 信封与 PointerEvent 序列约定：
     * - [pointerDrag] 拖拽：pointerdown → N 次线性插值 pointermove → pointerup，
     *   每帧 target 经 elementFromPoint 实时解析（HTML5 拖拽要求 move 落在
     *   同一节点链上）；
     * - [swipe] 方向滑动：以上传的锚点为起点，按 direction + distancePx 计算终点；
     * - [draw] 自由绘：按上传的压力序列逐点走 pointerdown → N × pointermove → pointerup；
     * - [canvasTouch] canvas 触摸：touchstart/touchmove/touchend/touchcancel +
     *   changedTouches，dispatch 前先压制 contextmenu（长按不弹原生菜单）。
     * 全部事件带 pointerId:1 / pointerType:'touch' / isPrimary:true / pressure:0.5 /
     * timestamp，bubbles:true / cancelable:true。
     */

    private fun pointerEventChain(label: String, coords: List<Pair<Int, Int>>, openSteps: Int, closeSteps: Int): String {
        val xys = if (coords.isEmpty()) "[]" else "[" + coords.map { "\"${it.first},${it.second}\"" }.joinToString(",") + "]"
        val o = openSteps.coerceIn(0, 50)
        val c = closeSteps.coerceIn(0, 50)
        return """
            (function(){
              try {
                var xys = ${xys};
                if (!xys.length) return JSON.stringify({ ok: false, error: "no coordinates" });
                var pts = xys.map(function(s){ var p = s.split(","); return { x: parseInt(p[0], 10), y: parseInt(p[1], 10) }; });
                var open = ${o}; var close = ${c};
                var total = pts.length - 1;
                var closeN = Math.min(close, total);
                var seq = [["pointerdown", pts[0].x, pts[0].y, 0.5]];
                for (var i = 1; i <= total - closeN; i++) {
                  seq.push(["pointermove", pts[i].x, pts[i].y, 0.5]);
                }
                seq.push(["pointerup", pts[pts.length - 1].x, pts[pts.length - 1].y, 0.1]);
                var Ctor = (typeof PointerEvent !== "undefined") ? PointerEvent : null;
                var last = null;
                for (var s = 0; s < seq.length; s++) {
                  var ev = seq[s];
                  var cx = ev[1], cy = ev[2];
                  var tgt = document.elementFromPoint(cx, cy) || document.body;
                  var init = { bubbles: true, cancelable: true, pointerId: 1, pointerType: "touch", isPrimary: true, pressure: ev[3], clientX: cx, clientY: cy, timestamp: Date.now() };
                  var e2;
                  if (Ctor) { e2 = new Ctor(ev[0], init); }
                  else { e2 = new Event(ev[0], { bubbles: true, cancelable: true }); e2.clientX = cx; e2.clientY = cy; e2.pointerId = 1; e2.pointerType = "touch"; e2.isPrimary = true; }
                  tgt.dispatchEvent(e2);
                  last = tgt;
                }
                return JSON.stringify({ ok: true, kind: "${label}", steps: seq.length, tag: last ? last.tagName.toLowerCase() : "", id: last && last.id ? last.id : "" });
              } catch (e) { return JSON.stringify({ ok: false, error: String(e) }); }
            })()
        """.trimIndent()
    }

    /** 拖拽：从 (x1,y1) 到 (x2,y2)，中间按 [midSteps] 线性插值（默认 16 帧）。 */
    fun pointerDrag(x1: Int, y1: Int, x2: Int, y2: Int, midSteps: Int): String =
        pointerEventChain(
            label = "drag",
            coords = listOf(x1 to y1, x2 to y2),
            openSteps = midSteps,
            closeSteps = 0,
        )

    /** 方向滑动：锚点 (x,y) 起，按 [direction](left/right/up/down) + [distancePx] 计算终点。 */
    fun swipe(direction: String, x: Int, y: Int, distancePx: Int, midSteps: Int): String {
        val dir = jsQuote(direction.lowercase())
        val dist = distancePx.coerceIn(0, 4096)
        val steps = midSteps.coerceIn(2, 60)
        return """
            (function(){
              try {
                var dir = ${dir}; var x0 = ${x}; var y0 = ${y}; var dist = ${dist};
                var dx = 0, dy = 0;
                if (dir === "left") { dx = -dist; }
                else if (dir === "right") { dx = dist; }
                else if (dir === "up") { dy = -dist; }
                else if (dir === "down") { dy = dist; }
                else { return JSON.stringify({ ok: false, error: "unknown direction: " + dir }); }
                var n = ${steps};
                var pts = [];
                for (var i = 0; i <= n; i++) { pts.push(x0 + dx * i / n + "," + (y0 + dy * i / n)); }
                var xys = "[" + pts.join(",") + "]";
                var Ctor = (typeof PointerEvent !== "undefined") ? PointerEvent : null;
                var seq = [];
                for (var j = 0; j < pts.length; j++) {
                  var p = xys[j]; var c = p.split(","); var cx = parseInt(c[0], 10); var cy = parseInt(c[1], 10);
                  var tgt = document.elementFromPoint(cx, cy) || document.body;
                  var name = j === 0 ? "pointerdown" : (j === pts.length - 1 ? "pointerup" : "pointermove");
                  var init = { bubbles: true, cancelable: true, pointerId: 1, pointerType: "touch", isPrimary: true, pressure: (name === "pointerdown" ? 0.8 : (name === "pointerup" ? 0.2 : 0.5)), clientX: cx, clientY: cy, timestamp: Date.now() };
                  var e2;
                  if (Ctor) { e2 = new Ctor(name, init); }
                  else { e2 = new Event(name, { bubbles: true, cancelable: true }); e2.clientX = cx; e2.clientY = cy; e2.pointerId = 1; e2.pointerType = "touch"; e2.isPrimary = true; }
                  tgt.dispatchEvent(e2);
                }
                return JSON.stringify({ ok: true, direction: dir, distancePx: dist, steps: pts.length });
              } catch (e) { return JSON.stringify({ ok: false, error: String(e) }); }
            })()
        """.trimIndent()
    }

    /** 自由绘 / 刮动：[points] 为 [[x,y,pressure], …]（pressure 缺省 0.5）。 */
    fun draw(points: List<List<Double>>): String {
        val ptsJson = if (points.isEmpty()) "[]" else "[" + points.joinToString(",") { p ->
            val x = (p[0]).toInt(); val y = (p.getOrElse(1) { 0.0 }).toInt(); val pr = (p.getOrElse(2) { 0.5 }).coerceIn(0.0, 1.0)
            "[$x,$y,$pr]"
        } + "]"
        return """
            (function(){
              try {
                var pts = ${ptsJson};
                if (!pts.length) return JSON.stringify({ ok: false, error: "no points" });
                var Ctor = (typeof PointerEvent !== "undefined") ? PointerEvent : null;
                for (var i = 0; i < pts.length; i++) {
                  var p = pts[i]; var cx = p[0], cy = p[1], pr = p[2];
                  var tgt = document.elementFromPoint(cx, cy) || document.body;
                  var name = i === 0 ? "pointerdown" : (i === pts.length - 1 ? "pointerup" : "pointermove");
                  var init = { bubbles: true, cancelable: true, pointerId: 1, pointerType: "touch", isPrimary: true, pressure: (name === "pointerup" ? 0.1 : pr), clientX: cx, clientY: cy, timestamp: Date.now() };
                  var e2;
                  if (Ctor) { e2 = new Ctor(name, init); }
                  else { e2 = new Event(name, { bubbles: true, cancelable: true }); e2.clientX = cx; e2.clientY = cy; e2.pointerId = 1; e2.pointerType = "touch"; e2.isPrimary = true; }
                  tgt.dispatchEvent(e2);
                }
                var last = pts[pts.length - 1];
                var lt = document.elementFromPoint(last[0], last[1]);
                return JSON.stringify({ ok: true, points: pts.length, tag: lt ? lt.tagName.toLowerCase() : "" });
              } catch (e) { return JSON.stringify({ ok: false, error: String(e) }); }
            })()
        """.trimIndent()
    }

    /**
     * canvas 触摸：[type] ∈ start/move/end/cancel；[points] 为 [[x,y], …] 多点
     * （多点触摸的 changedTouches）。dispatch 前先压制 contextmenu，避免长按弹
     * 出原生菜单打断手势。
     */
    fun canvasTouch(type: String, points: List<List<Int>>): String {
        val t = jsQuote(type.lowercase())
        val ptsJson = if (points.isEmpty()) "[]" else "[" + points.joinToString(",") { p -> "[${p[0]},${p.getOrElse(1) { 0}}]" } + "]"
        return """
            (function(){
              try {
                var type = ${t};
                var typeMap = { start: "touchstart", move: "touchmove", end: "touchend", cancel: "touchcancel" };
                var name = typeMap[type];
                if (!name) return JSON.stringify({ ok: false, error: "unknown canvas_touch type: " + type });
                var pts = ${ptsJson};
                if (!pts.length) return JSON.stringify({ ok: false, error: "no points" });
                var touches = pts.map(function(p, i){ return { identifier: i, target: null, clientX: p[0], clientY: p[1], pageX: p[0], pageY: p[1], radiusX: 1, radiusY: 1, force: 0.5, startX: p[0], startY: p[1], startPageX: p[0], startPageY: p[1] }; });
                var changed = touches;
                var active = (name === "touchend" || name === "touchcancel") ? [] : touches;
                var target = document.elementFromPoint(pts[0][0], pts[0][1]) || document.body;
                // 压制 contextmenu：长按 600ms 后浏览器可能弹原生菜单，先拦一层。
                target.addEventListener("contextmenu", function(e){ e.preventDefault(); }, false);
                var init = { bubbles: true, cancelable: true, target: target, touches: active, targetTouches: active, changedTouches: changed };
                var e = document.createEvent ? null : new TouchEvent(name, init);
                if (!e) {
                  e = document.createEvent("TouchEvent");
                  e.initTouchEvent(name, true, true, null, [], [], [], []);
                  e.targetTouches = active; e.changedTouches = changed;
                }
                target.dispatchEvent(e);
                return JSON.stringify({ ok: true, type: name, points: pts.length });
              } catch (e2) { return JSON.stringify({ ok: false, error: String(e2) }); }
            })()
        """.trimIndent()
    }

    /** 填输入框（React 兼容：走原型 value setter + input/change 事件）。 */
    fun fill(selector: String, value: String): String {
        val sel = jsQuote(selector)
        val valQuoted = jsQuote(value)
        return """
            (function(){
              try {
                var sel = ${sel};
                var el = document.querySelector(sel);
                if (!el) return JSON.stringify({ ok: false, error: "selector not found: " + sel });
                el.focus && el.focus();
                var isTa = typeof HTMLTextAreaElement !== "undefined" && el instanceof HTMLTextAreaElement;
                var proto = isTa ? HTMLTextAreaElement.prototype : (typeof HTMLInputElement !== "undefined" && el instanceof HTMLInputElement ? HTMLInputElement.prototype : HTMLSelectElement.prototype);
                var desc = Object.getOwnPropertyDescriptor(proto, "value");
                var v = ${valQuoted};
                if (desc && desc.set) desc.set.call(el, v); else el.value = v;
                el.dispatchEvent(new Event("input", { bubbles: true }));
                el.dispatchEvent(new Event("change", { bubbles: true }));
                el.blur && el.blur();
                return JSON.stringify({ ok: true, tag: el.tagName.toLowerCase(), value: String(el.value).slice(0, 200) });
              } catch (e) { return JSON.stringify({ ok: false, error: String(e) }); }
            })()
        """.trimIndent()
    }

    /** 勾选/取消勾选（property + change，兼容合成事件框架）。 */
    fun setChecked(selector: String, checked: Boolean): String {
        val sel = jsQuote(selector)
        return """
            (function(){
              try {
                var sel = ${sel};
                var el = document.querySelector(sel);
                if (!el) return JSON.stringify({ ok: false, error: "selector not found: " + sel });
                el.checked = ${checked};
                el.dispatchEvent(new Event("change", { bubbles: true }));
                return JSON.stringify({ ok: true, checked: el.checked, tag: el.tagName.toLowerCase() });
              } catch (e) { return JSON.stringify({ ok: false, error: String(e) }); }
            })()
        """.trimIndent()
    }

    /** 下拉选择（value + change；按 value/text/trim(text) 三向匹配）。 */
    fun selectOption(selector: String, option: String): String {
        val sel = jsQuote(selector)
        val opt = jsQuote(option)
        return """
            (function(){
              try {
                var sel = ${sel};
                var el = document.querySelector(sel);
                if (!el || el.tagName.toUpperCase() !== "SELECT") return JSON.stringify({ ok: false, error: "selector must resolve to a <select>" });
                var opts = Array.from(el.options || []);
                var want = ${opt};
                var target = opts.filter(function(o){ return o.value === want || o.text === want || o.text.trim() === want; })[0];
                if (!target) return JSON.stringify({ ok: false, error: "no option matching: " + want + " (have: " + opts.map(function(o){ return o.value; }).join(",") + ")" });
                el.value = target.value;
                el.dispatchEvent(new Event("change", { bubbles: true }));
                return JSON.stringify({ ok: true, value: el.value });
              } catch (e) { return JSON.stringify({ ok: false, error: String(e) }); }
            })()
        """.trimIndent()
    }

    /**
     * 按键（对应 ZCode keypress）：在 document.activeElement（退化 body）上派发
     * keydown/keyup；单字母键额外派发 keypress；Enter 命中 button 时补 click。
     * 限制（如实）：JS 合成按键无法替代真实 IME 输入，仅对监听 keydown/keyup 的表单有效。
     */
    fun keypress(keys: String): String {
        val key = jsQuote(keys)
        val singleLetter = keys.length == 1 && keys[0].isLetter()
        return """
            (function(){
              try {
                var key = ${key};
                var el = document.activeElement || document.body;
                var down = new KeyboardEvent("keydown", { key: key, bubbles: true, cancelable: true });
                el.dispatchEvent(down);
                ${if (singleLetter) "el.dispatchEvent(new KeyboardEvent(\"keypress\", { key: key, bubbles: true, cancelable: true }));" else "/* special key: no keypress */"}
                if (key === "Enter" && el.tagName === "BUTTON") el.click();
                el.dispatchEvent(new KeyboardEvent("keyup", { key: key, bubbles: true, cancelable: true }));
                return JSON.stringify({ ok: true, key: key, target: el.tagName.toLowerCase() });
              } catch (e) { return JSON.stringify({ ok: false, error: String(e) }); }
            })()
        """.trimIndent()
    }

    /** 滚动到坐标。 */
    fun scroll(x: Int, y: Int): String = """
        (function(){ window.scrollTo(${x}, ${y}); return JSON.stringify({ ok: true, scrollX: window.scrollX, scrollY: window.scrollY }); })()
    """.trimIndent()

    /** 表单提交（requestSubmit 优先，兼容旧引擎）；selector 命中 input/button 时自动上溯所在 form。 */
    fun submitForm(selector: String): String {
        val sel = jsQuote(selector)
        return """
            (function(){
              try {
                var sel = ${sel};
                var el = document.querySelector(sel);
                if (!el) return JSON.stringify({ ok: false, error: "selector not found: " + sel });
                var form = el.tagName === "FORM" ? el : el.form;
                if (!form) return JSON.stringify({ ok: false, error: "element is not inside a form" });
                if (form.requestSubmit && el.tagName !== "FORM") form.requestSubmit(el); else form.submit();
                return JSON.stringify({ ok: true, action: String(form.action || "") });
              } catch (e) { return JSON.stringify({ ok: false, error: String(e) }); }
            })()
        """.trimIndent()
    }

    /** 触发文件输入框的原生选择器（配合 onShowFileChooser；[selector] 须命中 input[type=file]）。 */
    fun uploadClick(selector: String): String {
        val sel = jsQuote(selector)
        return """
            (function(){
              try {
                var sel = ${sel};
                var el = document.querySelector(sel);
                if (!el) return JSON.stringify({ ok: false, error: "selector not found: " + sel });
                if (el.tagName.toUpperCase() !== "INPUT" || (el.type || "").toLowerCase() !== "file") return JSON.stringify({ ok: false, error: "selector is not an input[type=file]" });
                el.click();
                return JSON.stringify({ ok: true });
              } catch (e) { return JSON.stringify({ ok: false, error: String(e) }); }
            })()
        """.trimIndent()
    }

    /** 读取文件输入框当前文件数（1 = 文件已被 WebView 接受）。 */
    fun uploadFileCount(selector: String): String {
        val sel = jsQuote(selector)
        return """
            (function(){
              try {
                var sel = ${sel};
                var el = document.querySelector(sel);
                if (!el) return JSON.stringify({ ok: false, error: "selector not found: " + sel });
                return JSON.stringify({ ok: true, files: el.files ? el.files.length : -1 });
              } catch (e) { return JSON.stringify({ ok: false, error: String(e) }); }
            })()
        """.trimIndent()
    }

    /**
     * 验证墙（CAPTCHA / 人机校验）探测：命中时返回 kind/hint，并尽量给出可点击的
     * 按钮选择器与输入框描述，供 agent 提示用户手动完成或做有界复检。
     * 覆盖：reCAPTCHA v2（iframe 内嵌，顶层探测不到 frame 本体，读 iframe URL 的 ? 参数判 kind）、
     * hCaptcha、Geetest、Turnstile、通用 "verify/verification" 关键词、iframe + "captcha" 启发式。
     * 只读探测，不点击、不改动页面；未命中返回 {ok:true, detected:false}。
     */
    fun captchaProbe(): String = """
        (function(){
          try {
            function vis(el){ try { var r = el.getBoundingClientRect(); var s = getComputedStyle(el); return r.width > 20 && r.height > 20 && s.display !== "none" && s.visibility !== "hidden"; } catch (e) { return false; } }
            var found = { detected: false, kind: "", hints: [], selector: "", inputCount: 0, inputLabel: "" };
            // 1) reCAPTCHA / hCaptcha iframe（顶层查不到 frame 本体，读 iframe src 的 ? 参数判 kind）
            var ifr = Array.from(document.querySelectorAll("iframe"));
            for (var i = 0; i < ifr.length; i++) {
              var src = (ifr[i].src || ifr[i].getAttribute("src") || "").toLowerCase();
              var id = ifr[i].id || "";
              if (src.indexOf("recaptcha") >= 0 || id.indexOf("recaptcha") >= 0) {
                found.detected = true; found.kind = "recaptcha";
                found.hints.push("reCAPTCHA is shown in the page (usually a 'I am not a robot' checkbox or a sliding image puzzle).");
                found.selector = "#recaptcha-anchor, .recaptcha-checkbox, iframe[src*=recaptcha]";
              } else if (src.indexOf("hcaptcha") >= 0 || id.indexOf("hcaptcha") >= 0) {
                found.detected = true; found.kind = "hcaptcha";
                found.hints.push("hCaptcha is shown in the page (checkbox or puzzle).");
                found.selector = "#h-captcha iframe, .h-captcha";
              } else if (src.indexOf("geetest") >= 0 || src.indexOf("captcha") >= 0 || id.indexOf("captcha") >= 0) {
                found.detected = true; found.kind = found.kind || "iframe_captcha";
                found.hints.push("An embedded captcha iframe (" + (id || "unknown host") + ") is shown.");
                found.selector = found.selector || ifr[i].id;
              }
            }
            // 2) Geetest 顶层元素
            var ge = document.querySelector(".geetest_slider, .geetest_widget, .geetest_panel, [class*=geetest]");
            if (ge && vis(ge)) {
              found.detected = true; found.kind = found.kind || "geetest";
              found.hints.push("Geetest slider captcha is shown (drag the slider to verify).");
              found.selector = found.selector || ".geetest_slider_button, .geetest_btn";
            }
            // 3) Turnstile
            var ts = document.querySelector("[data-sitekey], [class*=cf-turnstile]");
            if (ts && vis(ts)) {
              found.detected = true; found.kind = found.kind || "turnstile";
              found.hints.push("Cloudflare Turnstile verification is shown.");
              found.selector = found.selector || "[class*=cf-turnstile]";
            }
            // 4) 关键词（标题 / 正文 / aria-label / 占位符）
            var kw = /(verify|captcha|recaptcha|hcaptcha|geetest|human verification|prove you are (not )?a human|confirm you are|i am not a robot|滑动验证|验证码|拖动|人机)/i;
            if (!found.detected) {
              var hits = [];
              var title = (document.title || "");
              if (kw.test(title)) hits.push("page title: " + title.slice(0, 80));
              var nodes = Array.from(document.querySelectorAll("h1,h2,h3,h4,p,span,div,button,label,li,section")).slice(0, 4000);
              for (var j = 0; j < nodes.length && hits.length < 3; j++) {
                var t = (nodes[j].textContent || "").trim();
                if (t && t.length < 120 && kw.test(t) && vis(nodes[j])) hits.push("text: " + t.slice(0, 80));
              }
              var labs = Array.from(document.querySelectorAll("[aria-label],[placeholder]")).slice(0, 2000);
              for (var k = 0; k < labs.length && hits.length < 3; k++) {
                var lab = ((labs[k].getAttribute("aria-label") || "") + " " + (labs[k].getAttribute("placeholder") || ""));
                if (kw.test(lab) && vis(labs[k])) hits.push("label: " + lab.trim().slice(0, 80));
              }
              if (hits.length) { found.detected = true; found.kind = found.kind || "keyword"; found.hints = found.hints.concat(hits.slice(0, 3)); }
            }
            // 5) 可交互验证输入框（计数 + 首个可见标签，供 agent 引导用户）
            var inputs = Array.from(document.querySelectorAll("input:not([type=hidden]), textarea"));
            for (var m = 0; m < inputs.length; m++) {
              var it = inputs[m];
              var label = (it.getAttribute("aria-label") || it.id || it.name || "").toLowerCase();
              if (kw.test(label) || /slider|captcha/.test((it.className || "").toLowerCase())) {
                found.inputCount++;
                if (!found.inputLabel && (it.getAttribute("aria-label") || it.id || it.name)) found.inputLabel = (it.getAttribute("aria-label") || it.id || it.name);
                if (!found.selector) found.selector = it.id || ("input[type=" + (it.type || "text") + "]");
              }
            }
            if (found.detected && found.inputCount > 0 && found.selector && found.selector.indexOf("recaptcha") < 0) {
              found.hints.push(found.inputCount + " verification input(s) visible" + (found.inputLabel ? " (label: " + found.inputLabel.slice(0, 40) + ")" : "") + ".");
            }
            return JSON.stringify({ ok: true, detected: found.detected, kind: found.kind, hints: found.hints, selector: found.selector, inputCount: found.inputCount, inputLabel: found.inputLabel });
          } catch (e) { return JSON.stringify({ ok: false, error: String(e) }); }
        })()
    """.trimIndent()

    /** 桌面模式：开启 → 写 meta viewport width=1280；关闭 → 移除该 meta。 */
    fun desktopViewport(on: Boolean): String = if (on) """
        (function(){
          var meta = document.querySelector('meta[name="viewport"]');
          if (!meta) { meta = document.createElement("meta"); meta.name = "viewport"; document.head.appendChild(meta); }
          meta.setAttribute("content", "width=1280");
          return JSON.stringify({ ok: true });
        })()
    """ else """
        (function(){
          var meta = document.querySelector('meta[name="viewport"]');
          if (meta) { document.head.removeChild(meta); return JSON.stringify({ ok: true }); }
          return JSON.stringify({ ok: true, note: "no meta viewport to remove" });
        })()
    """
}
