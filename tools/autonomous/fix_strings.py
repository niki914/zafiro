#!/usr/bin/env python3
"""After an upstream sync: dedupe default strings and replace Chinese values with upstream's own English (values-en).
Usage: python3 tools/autonomous/fix_strings.py <path-to-upstream-values-en-strings.xml>"""
import re, sys
p = 'app/src/main/res/values/strings.xml'
s = open(p).read()
seen = set()
def dd(m):
    k = m.group(1)
    if k in seen: return ''
    seen.add(k); return m.group(0)
s = re.sub(r'[ \t]*<string name="([^"]+)"[^>]*>.*?</string>[ \t]*\n', dd, s, flags=re.S)
en = open(sys.argv[1]).read() if len(sys.argv) > 1 else ''
enmap = {m.group(1): m.group(2) for m in re.finditer(r'<string name="([^"]+)"[^>]*>(.*?)</string>', en, re.S)}
cjk = re.compile(r'[\u4e00-\u9fff\u3040-\u30ff]')
left = []
def tr(m):
    k, v = m.group(1), m.group(2)
    if cjk.search(v):
        if k in enmap and not cjk.search(enmap[k]): return m.group(0).replace(v, enmap[k])
        left.append(k)
    return m.group(0)
s = re.sub(r'<string name="([^"]+)"[^>]*>(.*?)</string>', tr, s, flags=re.S)
open(p, 'w').write(s)
print('untranslated keys:', left)
