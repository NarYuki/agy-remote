#!/usr/bin/env python3
"""Converts the iOS string catalogs (Sources/Resources/*.xcstrings) into Android string resources.

Usage: python3 tools/convert_xcstrings.py ../ios/Sources/Resources app/src/main/res [app/src/main]
With the third argument, keys that no Kotlin file (R.string.x / "x" for Texts.get) or XML under it
references are left out, so unused iOS-only strings don't ship in the APK.
Writes values*/strings_catalog.xml for every language in the catalog. Resource names are derived
from the English key (lowercase, non-alphanumerics -> '_', max 48 chars) so they're stable.
"""
import json, re, sys, os
from xml.sax.saxutils import escape

LANG_DIRS = {"en": "values", "pt-BR": "values-pt-rBR", "zh-Hans": "values-b+zh+Hans",
             "zh-Hant": "values-b+zh+Hant", "he": "values-iw"}
INFOPLIST_NAMES = {"CFBundleDisplayName": "app_name", "NSCameraUsageDescription": "camera_usage",
                   "NSMicrophoneUsageDescription": "microphone_usage",
                   "NSPhotoLibraryUsageDescription": "photos_usage",
                   "NSSpeechRecognitionUsageDescription": "speech_usage",
                   "NSLocalNetworkUsageDescription": "local_network_usage"}

JAVA_KEYWORDS = set("""abstract assert boolean break byte case catch char class const continue default do double
else enum extends final finally float for goto if implements import instanceof int interface long native new
package private protected public return short static strictfp super switch synchronized this throw throws
transient try void volatile while true false null""".split())

def res_name(key):
    n = re.sub(r"%(\d\$)?(lld|@|d)", "x", key)
    n = re.sub(r"[^a-z0-9]+", "_", n.lower()).strip("_")[:48].strip("_")
    if not n or n[0].isdigit():
        n = "s_" + n
    # Android resource names become Java fields: a keyword (default, continue, …) can't be one.
    if n in JAVA_KEYWORDS:
        n += "_"
    return n

def fmt(v):
    """iOS format specifiers -> Java ones."""
    v = re.sub(r"%(\d+\$)?lld", lambda m: "%" + (m.group(1) or "") + "d", v)
    v = re.sub(r"%(\d+\$)?@", lambda m: "%" + (m.group(1) or "") + "s", v)
    return v

def android_escape(v):
    v = escape(v).replace("\\", "\\\\").replace("'", "\\'").replace('"', '\\"').replace("\n", "\\n")
    if v.startswith("@") or v.startswith("?"):
        v = "\\" + v
    return v

def has_args(v):
    return re.search(r"%(\d+\$)?[sd]", v) is not None

def plural_forms(loc):
    """Returns {quantity: text} for plural variations or substitutions, else None."""
    if "variations" in loc and "plural" in loc["variations"]:
        return {q: fmt(x["stringUnit"]["value"]) for q, x in loc["variations"]["plural"].items()}
    subs = loc.get("substitutions")
    if subs and "stringUnit" in loc:
        base = loc["stringUnit"]["value"]
        (name, sub), = subs.items()
        spec = "%" + str(sub.get("argNum", 1)) + "$" + sub.get("formatSpecifier", "lld")
        out = {}
        for q, x in sub["variations"]["plural"].items():
            piece = x["stringUnit"]["value"].replace("%arg", spec)
            out[q] = fmt(base.replace("%#@" + name + "@", piece))
        return out
    return None

def used_names(root):
    blob = []
    for d, _, files in os.walk(root):
        for f in files:
            p = os.path.join(d, f)
            if f.endswith(".kt") or (f.endswith(".xml") and "/res/values" not in p):
                blob.append(open(p, encoding="utf-8").read())
    blob = "\n".join(blob)
    return lambda n: re.search(r'(R\.(string|plurals)\.|@string/)' + re.escape(n) + r'\b|"' + re.escape(n) + '"', blob) is not None

def main(src, res, used=None):
    cat = json.load(open(os.path.join(src, "Localizable.xcstrings")))
    info = json.load(open(os.path.join(src, "InfoPlist.xcstrings")))
    langs = set()
    for c in (cat, info):
        for v in c["strings"].values():
            langs |= set(v.get("localizations", {}))
    per_lang = {l: [] for l in langs}
    seen = set()
    for key, entry in sorted(cat["strings"].items()):
        if not key.strip() or key.strip() in ("$",):
            continue
        name = res_name(key)
        if name in seen or (used and not used(name)):
            continue
        seen.add(name)
        locs = entry.get("localizations", {})
        for l in langs:
            loc = locs.get(l) or (locs.get("en") if l != "en" else None) or {}
            forms = plural_forms(loc)
            if not forms and "stringUnit" in loc and plural_forms(locs.get("en") or {}):
                # Languages without plural forms (ja, ko, zh, th, vi, ms) have one string on iOS;
                # Android needs a <plurals> too or it drops it and falls back to English.
                forms = {"other": fmt(loc["stringUnit"]["value"])}
            if forms:
                items = "".join(f'\n        <item quantity="{q}">{android_escape(t)}</item>' for q, t in forms.items())
                per_lang[l].append(f'    <plurals name="{name}">{items}\n    </plurals>')
                continue
            if "stringUnit" in loc:
                val = loc["stringUnit"]["value"]
            elif l == "en":
                val = key
            else:
                continue  # falls back to English
            val = fmt(val)
            extra = "" if has_args(val) or "%" not in val else ' formatted="false"'
            per_lang[l].append(f'    <string name="{name}"{extra}>{android_escape(val)}</string>')
    for key, entry in info["strings"].items():
        name = INFOPLIST_NAMES.get(key)
        if not name or (used and not used(name)):
            continue
        for l, loc in entry.get("localizations", {}).items():
            if "stringUnit" in loc:
                per_lang[l].append(f'    <string name="{name}">{android_escape(loc["stringUnit"]["value"])}</string>')
    for l, lines in per_lang.items():
        d = os.path.join(res, LANG_DIRS.get(l, "values-" + l))
        os.makedirs(d, exist_ok=True)
        # Names a hand-written file of this language already defines (Android translations of
        # catalog keys iOS hasn't translated yet) are left out, so the build has no duplicates.
        own = set()
        for f in os.listdir(d):
            if f.startswith("strings") and f.endswith(".xml") and f != "strings_catalog.xml":
                own |= set(re.findall(r'<(?:string|plurals) name="([^"]+)"', open(os.path.join(d, f), encoding="utf-8").read()))
        lines = [x for x in lines if re.search(r'name="([^"]+)"', x).group(1) not in own]
        with open(os.path.join(d, "strings_catalog.xml"), "w") as f:
            f.write("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!-- Generated by tools/convert_xcstrings.py from the iOS string catalogs. Do not edit. -->\n<resources>\n")
            f.write("\n".join(lines) + "\n</resources>\n")
    print(len(seen), "keys,", len(langs), "languages")

if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], used_names(sys.argv[3]) if len(sys.argv) > 3 else None)
