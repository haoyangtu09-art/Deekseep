#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"

python - <<'PY'
import json
import pathlib
import re
import sys

root = pathlib.Path("src/com/dsmod/probe")
catalog_source = (root / "UiLanguageCatalog.java").read_text(encoding="utf-8")
string_literal = re.compile(r'"(?:\\.|[^"\\])*"')
cjk = re.compile(r'[\u3400-\u9fff]')


def decode_java(literal):
    try:
        return json.loads(literal)
    except json.JSONDecodeError:
        body = literal[1:-1]
        escapes = {
            "n": "\n", "r": "\r", "t": "\t", "b": "\b", "f": "\f",
            "\\": "\\", '"': '"', "'": "'",
        }

        def replace(match):
            token = match.group(1)
            if token.startswith("u"):
                return chr(int(token[1:], 16))
            return escapes.get(token, token)

        return re.sub(r'\\(u[0-9a-fA-F]{4}|.)', replace, body)


def joined_literals(source):
    return "".join(decode_java(value) for value in string_literal.findall(source))


def catalog_entries(source):
    entries = []
    for match in re.finditer(r'\badd\s*\(', source):
        cursor = match.end()
        depth = 1
        in_string = False
        escaped = False
        comma = None
        while cursor < len(source) and depth:
            char = source[cursor]
            if in_string:
                if escaped:
                    escaped = False
                elif char == "\\":
                    escaped = True
                elif char == '"':
                    in_string = False
            else:
                if char == '"':
                    in_string = True
                elif char == '(':
                    depth += 1
                elif char == ')':
                    depth -= 1
                elif char == ',' and depth == 1 and comma is None:
                    comma = cursor
            cursor += 1
        if comma is None:
            continue
        chinese = joined_literals(source[match.end():comma])
        english = joined_literals(source[comma + 1:cursor - 1])
        if chinese:
            entries.append((chinese, english))
    return sorted(entries, key=lambda pair: len(pair[0]), reverse=True)


entries = catalog_entries(catalog_source)
exact = dict(entries)


def translate(value):
    if value in exact:
        return exact[value]
    for chinese, english in entries:
        if chinese in value:
            value = value.replace(chinese, english)
    return value


bad_english = [(chinese, english) for chinese, english in entries if cjk.search(english)]
if bad_english:
    print("English catalog values containing CJK:", file=sys.stderr)
    for chinese, english in bad_english[:20]:
        print(repr(chinese), "=>", repr(english), file=sys.stderr)
    raise SystemExit(1)

ui_sources = [
    "DeekseepUi.java", "AccountUi.java", "AccountManager.java",
    "AccountCredentialCodec.java", "ChatEditorUi.java", "ChatSearchUi.java",
    "DeekseepTools.java", "SettingsActivity.java", "LocalApiKeepAliveService.java",
]
uncovered = []
for name in ui_sources:
    source = (root / name).read_text(encoding="utf-8")
    for literal in string_literal.findall(source):
        value = decode_java(literal)
        if cjk.search(value) and cjk.search(translate(value)):
            uncovered.append((name, value, translate(value)))

if uncovered:
    print("Chinese UI literals without complete English coverage:", file=sys.stderr)
    for name, value, translated in sorted(set(uncovered))[:80]:
        print(f"{name}: {value!r} -> {translated!r}", file=sys.stderr)
    raise SystemExit(1)

wiring = {
    "Main.java": ["UiLanguage.refreshHost(act);", "visionDescribePrompt()"],
    "DeekseepUi.java": ["showLanguagePicker", "UiLanguage.MODE_AUTO",
                         "UiLanguage.MODE_CHINESE", "UiLanguage.MODE_ENGLISH"],
    "LocalApiGateway.java": ["OpenAiToolBridge.addInstructions", "UiLanguage.text"],
}
for name, required in wiring.items():
    source = (root / name).read_text(encoding="utf-8")
    for marker in required:
        if marker not in source:
            raise SystemExit(f"{name} is missing language wiring marker: {marker}")

print(f"Language catalog regression passed ({len(entries)} translations, "
      f"{len(ui_sources)} UI sources)")
PY
