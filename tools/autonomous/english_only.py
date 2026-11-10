#!/usr/bin/env python3
"""Make the Android resources English-only. Idempotent; safe to re-run after every upstream merge.

Upstream ships Chinese as the default locale (values/) with translations in values-en, values-ja, values-es,
values-ru and values-b+zh+Hant. This fork ships English only:
  1. For every res/ directory that has values-en, the English strings become the default (values/).
     Default-only elements (non-translatable constants such as module_scope) are kept.
  2. All other language directories (including values-en itself) are deleted.
  3. Branding overrides are applied (app name).
  4. Fails (exit 1) if CJK text remains in any default values/*.xml, so a new upstream string that lacks an
     English translation cannot silently ship in Chinese.

After an upstream merge: `git checkout --theirs 'app/src/main/res/values*' ...` for conflicted resource files,
then run this script again. See UPSTREAM_SYNC.md.
"""
import glob
import os
import re
import shutil
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
CJK = re.compile(r"[぀-ヿ㐀-䶿一-鿿豈-﫿]")
LANG_DIR = re.compile(r"^values-(b\+)?[a-z]{2}(-r[A-Z]{2}|\+.*)?$")
ELEMENT = re.compile(
    r"[ \t]*<(string|plurals|string-array|array|integer-array)\b[^>]*?\bname=\"([^\"]+)\"[^>]*?(?:/>|>.*?</\1>)\n?",
    re.DOTALL,
)
OVERRIDES = {"app_name": "Zafiro Autonomous"}


def elements(text):
    return {m.group(2): m.group(0) for m in ELEMENT.finditer(text)}


def merge(default_text, en_text):
    """English file content plus any default-only elements (appended)."""
    en_names = elements(en_text)
    extras = [raw for name, raw in elements(default_text).items() if name not in en_names]
    if not extras:
        return en_text
    body = "".join(e if e.endswith("\n") else e + "\n" for e in extras)
    return en_text.replace("</resources>", body + "</resources>")


def apply_overrides(text):
    for name, value in OVERRIDES.items():
        text = re.sub(
            r'(<string\b[^>]*\bname="%s"[^>]*>)[^<]*(</string>)' % re.escape(name),
            lambda m: m.group(1) + value + m.group(2),
            text,
        )
    return text


def main():
    changed = 0
    res_dirs = [
        p for p in glob.glob(os.path.join(ROOT, "**", "src", "main", "res"), recursive=True)
        if os.sep + "build" + os.sep not in p
    ]
    for res in res_dirs:
        en_dir = os.path.join(res, "values-en")
        default_dir = os.path.join(res, "values")
        if os.path.isdir(en_dir):
            os.makedirs(default_dir, exist_ok=True)
            for name in os.listdir(en_dir):
                if not name.endswith(".xml"):
                    continue
                with open(os.path.join(en_dir, name), encoding="utf-8") as f:
                    en_text = f.read()
                target = os.path.join(default_dir, name)
                if os.path.exists(target):
                    with open(target, encoding="utf-8") as f:
                        en_text = merge(f.read(), en_text)
                with open(target, "w", encoding="utf-8", newline="\n") as f:
                    f.write(en_text)
                changed += 1
        for d in os.listdir(res):
            if LANG_DIR.match(d):
                shutil.rmtree(os.path.join(res, d))
                changed += 1
        strings = os.path.join(default_dir, "strings.xml")
        if os.path.exists(strings):
            with open(strings, encoding="utf-8") as f:
                before = f.read()
            after = apply_overrides(before)
            if after != before:
                with open(strings, "w", encoding="utf-8", newline="\n") as f:
                    f.write(after)

    bad = []
    for res in res_dirs:
        for path in glob.glob(os.path.join(res, "values", "*.xml")):
            with open(path, encoding="utf-8") as f:
                text = f.read()
            text = re.sub(r"<!--.*?-->", lambda m: "\n" * m.group(0).count("\n"), text, flags=re.DOTALL)
            for n, line in enumerate(text.splitlines(), 1):
                if CJK.search(line):
                    bad.append("%s:%d: %s" % (os.path.relpath(path, ROOT), n, line.strip()[:100]))
    print("english_only: touched %d paths" % changed)
    if bad:
        print("CJK text remains in default resources (missing English translation?):")
        print("\n".join(bad))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
