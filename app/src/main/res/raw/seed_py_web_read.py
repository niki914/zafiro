# Seed tool: fetch a URL and extract readable article text (companion to py_web_search,
# use when search only gives snippets and the full page is needed).
# ponytail: article-container selectors break on site redesigns; Agent can self-update this script.
import re
import html
import requests


def _clean(s: str) -> str:
    s = re.sub(r'[ \t\u00a0\u3000]+', ' ', s)
    s = re.sub(r'\n\s*\n+', '\n', s)
    return '\n'.join(line.strip() for line in s.split('\n') if line.strip())


def _extract_article(raw: str) -> str:
    # 1) <article> block
    m = re.search(r'<article\b[\s\S]*?</article>', raw, re.I)
    if m:
        return m.group(0)

    # 2) common article container div, depth-aware closing match
    m = re.search(
        r'<div\b[^>]*(?:art_content|artibody|article-content|article_content|article|content)[^>]*>',
        raw, re.I,
    )
    if m:
        start = m.start()
        depth = 0
        i = start
        while i < len(raw):
            op = re.match(r'<div\b[^>]*>', raw[i:], re.I)
            cl = re.match(r'</div\s*>', raw[i:], re.I)
            if cl:
                depth -= 1
                if depth <= 0:
                    return raw[start:i + cl.end()]
                i += cl.end()
                continue
            if op:
                tag = op.group(0)
                if not tag.rstrip().endswith('/>'):
                    depth += 1
                i += op.end()
                continue
            i += 1
        return raw[start:]

    # 3) <body> fallback
    m = re.search(r'<body\b[\s\S]*?</body>', raw, re.I)
    if m:
        return m.group(0)

    return raw


def _decoded_text(r) -> str:
    enc = None
    m = re.search(r'charset=["\']?([\w-]+)', r.headers.get('Content-Type', ''), re.I)
    if m:
        enc = m.group(1)
    if not enc:
        m = re.search(r'<meta[^>]+charset=["\']?([\w-]+)', r.text[:2000], re.I)
        if m:
            enc = m.group(1)
    candidates = [enc, 'utf-8', 'gb18030']
    for c in candidates:
        if not c:
            continue
        try:
            r.encoding = c
            return r.text
        except Exception:
            continue
    return r.text


def main(url: str, keyword: str = "", max_chars: int = 6000, timeout: int = 25):
    """抓取网页并提取可读正文。可选 keyword：只返回关键词附近的一整段文本（定位到后前后各取一半 max_chars）。
    当 py_web_search 只给摘要、需要读全文时使用。"""
    headers = {
        'User-Agent': 'Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 '
                      '(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36',
        'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
    }
    r = requests.get(url, headers=headers, timeout=timeout)
    r.raise_for_status()
    raw = _decoded_text(r)

    # strip noisy blocks
    raw = re.sub(r'<(script|style|noscript|iframe|svg)[\s\S]*?</\1>', ' ', raw, flags=re.I)
    raw = re.sub(r'<head[\s\S]*?</head>', ' ', raw, flags=re.I)

    body = _extract_article(raw)
    body = re.sub(r'<(script|style|noscript)[\s\S]*?</\1>', ' ', body, flags=re.I)

    text = re.sub(r'<[^>]+>', '\n', body)
    text = html.unescape(text)
    text = _clean(text)

    if keyword:
        idx = text.lower().find(keyword.lower())
        if idx >= 0:
            start = max(0, idx - max_chars // 2)
            text = text[start:start + max_chars]
        else:
            text = text[:max_chars]
    else:
        text = text[:max_chars]

    print(text)
