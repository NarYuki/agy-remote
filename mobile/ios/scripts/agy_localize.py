#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Fill translations with the agy CLI (`agy -p`).

    agy_localize.py ios      [--all] [--only ja,de]   # Localizable.xcstrings files
    agy_localize.py android  [--all] [--only ja,de]   # res/values/*.xml (hand-written files)

Without --all only missing / untranslated entries are sent; with --all every entry is re-translated.
Batches of strings go to agy as JSON and come back as JSON; placeholders are checked before writing.
"""
import json, os, re, subprocess, sys, xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
IOS_CATALOGS = [ROOT / "mobile/ios/Sources/Resources/Localizable.xcstrings",
                ROOT / "mobile/ios/NotificationService/Localizable.xcstrings",
                ROOT / "mobile/ios/Watch/Resources/Localizable.xcstrings",
                ROOT / "mobile/ios/WatchWidgets/Localizable.xcstrings"]
ANDROID_RES = ROOT / "mobile/android/app/src/main/res"
# Android files generated from the iOS catalog are left to tools/convert_xcstrings.py.
ANDROID_GENERATED = {"strings_catalog.xml"}

LOCALES = {  # xcstrings code -> (Android values dir suffix, language name for the prompt)
    "ar": ("ar", "Arabic"), "de": ("de", "German"), "es": ("es", "Spanish"), "fr": ("fr", "French"),
    "he": ("iw", "Hebrew"), "hi": ("hi", "Hindi"), "it": ("it", "Italian"), "ja": ("ja", "Japanese"),
    "kk": ("kk", "Kazakh"), "ko": ("ko", "Korean"), "ms": ("ms", "Malay"), "nl": ("nl", "Dutch"),
    "pt-BR": ("pt-rBR", "Brazilian Portuguese"), "ru": ("ru", "Russian"), "th": ("th", "Thai"),
    "tr": ("tr", "Turkish"), "vi": ("vi", "Vietnamese"), "zh-Hans": ("b+zh+Hans", "Simplified Chinese"),
    "zh-Hant": ("b+zh+Hant", "Traditional Chinese"),
}
BATCH = 60
PH = re.compile(r"%(?:\d+\$)?[@dfsl]+|%lld|%%|\{[a-z_]+\}")

PROMPT = """You are localizing the UI of "AGY Remote", a mobile app that remote-controls the agy coding CLI \
running on the user's computer. Translate each English UI string into {lang}. Use the concise, natural \
wording a native {lang} iOS/Android app would use. Keep product names (AGY Remote, agy, agy-connector, \
iPhone, Android, Mac, QR) untranslated. Keep every placeholder (%@, %lld, %1$s, %d, %%) exactly, in an \
order that reads naturally. Keep leading/trailing punctuation such as "…" where it makes sense.
Reply with ONLY a JSON object that maps each input key to its translation, no commentary.

{payload}"""


def ask_agy(lang, items):
    payload = json.dumps(items, ensure_ascii=False, indent=0)
    out = subprocess.run(["agy", "-p", PROMPT.format(lang=lang, payload=payload), "--print-timeout", "600s"],
                         capture_output=True, text=True, cwd="/tmp")
    text = out.stdout
    m = re.search(r"\{.*\}", text, re.S)
    if not m:
        raise RuntimeError(f"agy gave no JSON for {lang}: {text[-400:]} {out.stderr[-400:]}")
    return json.loads(m.group(0))


def placeholders_ok(src, dst):
    return sorted(PH.findall(src)) == sorted(PH.findall(dst))


def translate(lang, pairs):
    """pairs: {key: english}. Returns {key: translation} for the ones that came back valid."""
    done, keys = {}, list(pairs)
    for i in range(0, len(keys), BATCH):
        chunk = {k: pairs[k] for k in keys[i:i + BATCH]}
        for attempt in range(3):
            try:
                got = ask_agy(lang, chunk)
                break
            except Exception as e:  # noqa: BLE001
                print(f"  retry {attempt + 1}: {e}", file=sys.stderr)
        else:
            continue
        for k, v in got.items():
            if k in chunk and isinstance(v, str) and v.strip() and placeholders_ok(chunk[k], v):
                done[k] = v
            elif k in chunk:
                print(f"  rejected {lang} {k!r}: {v!r}", file=sys.stderr)
        print(f"  {lang}: {min(i + BATCH, len(keys))}/{len(keys)}", file=sys.stderr)
    return done


def ios(all_, only):
    for path in IOS_CATALOGS:
        if not path.exists():
            continue
        cat = json.loads(path.read_text())
        src = cat.get("sourceLanguage", "en")
        for code, (_, lang) in LOCALES.items():
            if only and code not in only:
                continue
            todo = {}
            for key, entry in cat["strings"].items():
                if entry.get("shouldTranslate") is False or not key.strip():
                    continue
                en = (entry.get("localizations", {}).get(src, {}).get("stringUnit", {}).get("value")) or key
                if "variations" in entry.get("localizations", {}).get(src, {}):
                    continue  # plural/device variations: left as is
                unit = entry.get("localizations", {}).get(code, {}).get("stringUnit")
                if all_ or not unit or unit.get("state") != "translated":
                    todo[key] = en
            if not todo:
                continue
            print(f"{path.name} {code}: {len(todo)} strings", file=sys.stderr)
            for key, value in translate(lang, todo).items():
                cat["strings"][key].setdefault("localizations", {})[code] = {
                    "stringUnit": {"state": "translated", "value": value}}
            path.write_text(json.dumps(cat, ensure_ascii=False, indent=2, separators=(",", " : ")) + "\n")


def read_strings(path):
    tree = ET.parse(path)
    return {e.get("name"): (e.text or "") for e in tree.getroot().findall("string")
            if e.get("translatable") != "false"}


def esc(s):
    s = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    return s.replace("\\", "\\\\").replace("'", "\\'").replace('"', '\\"').replace("\n", "\\n")


def unesc(s):
    return s.replace("\\'", "'").replace('\\"', '"').replace("\\n", "\n").replace("\\\\", "\\")


def android(all_, only):
    for base in sorted((ANDROID_RES / "values").glob("*.xml")):
        if base.name in ANDROID_GENERATED:
            continue
        english = {k: unesc(v) for k, v in read_strings(base).items()}
        if not english:
            continue
        for code, (suffix, lang) in LOCALES.items():
            if only and code not in only:
                continue
            target = ANDROID_RES / f"values-{suffix}" / base.name
            have = read_strings(target) if target.exists() else {}
            todo = {k: v for k, v in english.items() if all_ or k not in have}
            if not todo:
                continue
            print(f"{base.name} {code}: {len(todo)} strings", file=sys.stderr)
            merged = {k: unesc(v) for k, v in have.items() if k in english}
            merged.update(translate(lang, todo))
            target.parent.mkdir(parents=True, exist_ok=True)
            lines = ['<?xml version="1.0" encoding="utf-8"?>', "<!-- Translated with the agy CLI (scripts/agy_localize.py). -->", "<resources>"]
            lines += [f'    <string name="{k}">{esc(merged[k])}</string>' for k in english if k in merged]
            target.write_text("\n".join(lines + ["</resources>", ""]))


if __name__ == "__main__":
    args = sys.argv[1:]
    if not args or args[0] not in ("ios", "android"):
        sys.exit(__doc__)
    only = set(args[args.index("--only") + 1].split(",")) if "--only" in args else set()
    (ios if args[0] == "ios" else android)("--all" in args, only)
