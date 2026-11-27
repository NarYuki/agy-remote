#!/usr/bin/env python3
"""Writes the open-source notices shown in Settings > License as structured JSON.

Usage:
  python3 tools/gen_licenses.py         (from android/) -> app/src/main/assets/licenses.json
  python3 tools/gen_licenses.py --ios   -> ../ios/Sources/Resources/licenses.json (mobile/ios/scripts/gen_ios_licenses.sh)

Needs cargo (offline is fine once the crates are fetched). Lists the Android/Kotlin libraries
(hand-maintained below; versions are read from app/build.gradle.kts) and every Rust crate linked
into libagy_p2p_jni.so (Android) or AgyP2PCore (iOS, agy-p2p-ffi).

Output:
  {
    "app": {"name", "licenseId", "url", "textKey"},
    "libraries": [{"name", "version", "group" ("android" | "rust" | "swift"), "licenseId" (SPDX
                   expression), "licenseKeys" (ids with a shared text), "author", "url",
                   "noticeKey" (the library's own LICENSE/NOTICE text, or null)}],
    "texts": {"Apache-2.0": ..., "MIT": ..., "AGPL-3.0": ..., "notice:<hash>": ...}
  }
Each licence text is stored once. A library's own LICENSE/NOTICE files become a notice only when
they differ from the shared text (copyright lines, NOTICE files, other licences).
"""
import hashlib, json, os, re, subprocess, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = os.path.dirname(os.path.dirname(ROOT))  # repo root (this file is mobile/android/tools/)
IOS = os.path.join(REPO, "mobile/ios")
P2P = os.path.join(REPO, "server")
GRADLE = os.path.join(ROOT, "app/build.gradle.kts")

# Kotlin / Java dependencies of app/build.gradle.kts (keep in sync when adding one; the script
# warns about implementation() lines that no entry covers).
# (name, maven coordinate whose version is shown, author, licence, url, covered prefixes)

# Attribution notices for Android libraries, keyed by name (shown as that library's notice).
ANDROID_NOTICES = {
    "AndroidLiquidGlass (Backdrop)":
        "ui/components/liquid/* adapted from the AndroidLiquidGlass catalog (Apache-2.0), modified for AGY Remote.\n"
        "Upstream: https://github.com/Kyant0/AndroidLiquidGlass\n"
        "Commit: 65ab177e90e5c1d8c62e70cf7755841982da65f6\n"
        "Copyright 2025 Kyant",
}
ANDROID_LIBS = [
    ("AndroidLiquidGlass (Backdrop)", "io.github.kyant0:backdrop", "Kyant", "Apache-2.0",
     "https://github.com/Kyant0/AndroidLiquidGlass", ["io.github.kyant0:backdrop"]),
    ("AndroidLiquidGlass (Shapes)", "io.github.kyant0:shapes", "Kyant", "Apache-2.0",
     "https://github.com/Kyant0/AndroidLiquidGlass", ["io.github.kyant0:shapes"]),
    ("Haze", "dev.chrisbanes.haze:haze", "Chris Banes", "Apache-2.0", "https://github.com/chrisbanes/haze",
     ["dev.chrisbanes.haze"]),
    ("Jetpack Compose", "androidx.compose:compose-bom", "The Android Open Source Project", "Apache-2.0",
     "https://developer.android.com/jetpack/androidx/releases/compose", ["androidx.compose"]),
    ("AndroidX Activity", "androidx.activity:activity-compose", "The Android Open Source Project", "Apache-2.0",
     "https://developer.android.com/jetpack/androidx/releases/activity", ["androidx.activity"]),
    ("AndroidX Fragment", "androidx.fragment:fragment-ktx", "The Android Open Source Project", "Apache-2.0",
     "https://developer.android.com/jetpack/androidx/releases/fragment", ["androidx.fragment"]),
    ("AndroidX Lifecycle", "androidx.lifecycle:lifecycle-runtime-compose", "The Android Open Source Project", "Apache-2.0",
     "https://developer.android.com/jetpack/androidx/releases/lifecycle", ["androidx.lifecycle"]),
    ("AndroidX Core", "androidx.core:core-ktx", "The Android Open Source Project", "Apache-2.0",
     "https://developer.android.com/jetpack/androidx/releases/core", ["androidx.core"]),
    ("CameraX", "androidx.camera:camera-camera2", "The Android Open Source Project", "Apache-2.0",
     "https://developer.android.com/jetpack/androidx/releases/camera", ["androidx.camera"]),
    ("Material Icons", "androidx.compose:compose-bom", "Google LLC", "Apache-2.0", "https://fonts.google.com/icons", []),
    ("Kotlin Standard Library", "kotlin", "JetBrains s.r.o. and Kotlin Programming Language contributors", "Apache-2.0",
     "https://github.com/JetBrains/kotlin", []),
    ("kotlinx.coroutines", "org.jetbrains.kotlinx:kotlinx-coroutines-android", "JetBrains s.r.o.", "Apache-2.0",
     "https://github.com/Kotlin/kotlinx.coroutines", ["org.jetbrains.kotlinx"]),
    ("Firebase Cloud Messaging", "com.google.firebase:firebase-bom", "Google LLC", "Apache-2.0",
     "https://github.com/firebase/firebase-android-sdk", ["com.google.firebase"]),
    ("zxing-cpp", "io.github.zxing-cpp:android", "Axel Waggershauser and zxing-cpp contributors", "Apache-2.0", "https://github.com/zxing-cpp/zxing-cpp",
     ["io.github.zxing-cpp"]),
]

LICENSE_FILES = ("LICENSE", "LICENCE", "COPYING", "NOTICE", "COPYRIGHT", "UNICODE", "UNLICENSE")

# The MIT licence body without its copyright line (every MIT crate adds its own).
MIT_TEXT = """Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE."""


def norm(s):
    return re.sub(r"\s+", " ", s).strip().lower()


def gradle_versions():
    src = open(GRADLE, encoding="utf-8").read()
    out = {}
    for m in re.finditer(r'"([\w.\-]+):([\w.\-]+):([\w.\-]+)"', src):
        out[f"{m.group(1)}:{m.group(2)}"] = m.group(3)
    root = open(os.path.join(ROOT, "build.gradle.kts"), encoding="utf-8").read()
    k = re.search(r'kotlin[\w.]*"\)\s*version\s*"([\w.\-]+)"', root)
    if k:
        out["kotlin"] = k.group(1)
    # Coordinates without a version (BOM-managed) and with one, for the coverage check.
    used = set(re.findall(r'implementation\("([\w.\-]+:[\w.\-]+)', src))
    return out, used


def android_libraries():
    versions, used = gradle_versions()
    covered = [p for lib in ANDROID_LIBS for p in lib[5]]
    for coord in sorted(used):
        if not any(coord.startswith(p) for p in covered):
            print(f"warning: {coord} is not in ANDROID_LIBS", file=sys.stderr)
    libs = []
    for name, coord, author, lic, url, _ in ANDROID_LIBS:
        libs.append({"name": name, "version": versions.get(coord, ""), "group": "android", "licenseId": lic,
                     "author": author, "url": url, "noticeKey": None})
    return libs


def rust_crates(target, root_name):
    meta = json.loads(subprocess.check_output(
        ["cargo", "metadata", "--format-version", "1", "--offline", "--filter-platform", target], cwd=P2P))
    pkgs = {p["id"]: p for p in meta["packages"]}
    nodes = {n["id"]: n for n in meta["resolve"]["nodes"]}
    root = next(p["id"] for p in meta["packages"] if p["name"] == root_name)
    seen, stack = set(), [root]
    while stack:
        i = stack.pop()
        if i in seen:
            continue
        seen.add(i)
        for d in nodes[i]["deps"]:
            if any(k["kind"] in (None, "normal") for k in d["dep_kinds"]):
                stack.append(d["pkg"])
    return sorted((pkgs[i] for i in seen if not pkgs[i]["name"].startswith("agy-")), key=lambda p: (p["name"].lower(), p["version"]))


def own_notice(pkg):
    """The crate's LICENSE/NOTICE/COPYRIGHT files, minus copies of the shared Apache/MIT texts."""
    d = os.path.dirname(pkg["manifest_path"])
    parts = []
    for f in sorted(os.listdir(d)):
        up = f.upper()
        if not any(up.startswith(x) for x in LICENSE_FILES) or not os.path.isfile(os.path.join(d, f)):
            continue
        try:
            txt = open(os.path.join(d, f), encoding="utf-8").read().strip()
        except UnicodeDecodeError:
            continue
        n = norm(txt)
        if "apache license" in n[:300] and "version 2.0" in n[:400] and ("terms and conditions" in n):
            continue  # a copy of the Apache-2.0 text, shared
        if norm(MIT_TEXT) in n and len(n) - len(norm(MIT_TEXT)) < 40:
            continue  # bare MIT text with no copyright line
        parts.append(txt)
    return "\n\n".join(parts) or None


def spdx_ids(expr):
    return [t for t in re.split(r"[\s()/]+|\bOR\b|\bAND\b|\bWITH\b", expr) if t and t not in ("OR", "AND", "WITH")]


def build(ios):
    apache = open(os.path.join(ROOT, "third_party/AndroidLiquidGlass/LICENSE"), encoding="utf-8").read().strip()
    agpl = open(os.path.join(REPO, "LICENSE"), encoding="utf-8").read().strip()
    texts = {"Apache-2.0": apache, "MIT": MIT_TEXT, "AGPL-3.0": agpl}
    libs = [] if ios else android_libraries()
    # The GitHub mark on the setup page and in Settings comes from Octicons (MIT).
    octicons = "Copyright (c) 2023 GitHub Inc.\n\n" + MIT_TEXT
    texts["notice:octicons"] = octicons
    libs.append({"name": "Octicons (mark-github)", "version": "19", "group": "swift" if ios else "android",
                 "licenseId": "MIT", "author": "GitHub Inc.", "url": "https://github.com/primer/octicons",
                 "noticeKey": "notice:octicons"})
    for lib in libs:
        if lib["name"] in ANDROID_NOTICES:
            txt = ANDROID_NOTICES[lib["name"]]
            lib["noticeKey"] = "notice:" + hashlib.sha1(txt.encode()).hexdigest()[:12]
            texts[lib["noticeKey"]] = txt
    target, root = ("aarch64-apple-ios", "agy-p2p-ffi") if ios else ("aarch64-linux-android", "agy-p2p-jni")
    for p in rust_crates(target, root):
        expr = (p.get("license") or "").replace(" / ", " OR ").replace("/", " OR ")
        notice = own_notice(p)
        key = None
        if notice:
            key = "notice:" + hashlib.sha1(notice.encode()).hexdigest()[:12]
            texts[key] = notice
        author = ", ".join(re.sub(r"\s*<[^>]*>", "", a) for a in p.get("authors") or []) or None
        libs.append({"name": p["name"], "version": p["version"], "group": "rust", "licenseId": expr or "See notice",
                     "author": author, "url": p.get("repository") or p.get("homepage"), "noticeKey": key})
    for lib in libs:
        notice = norm(texts[lib["noticeKey"]]) if lib.get("noticeKey") else ""
        # Skip a shared text the library's own notice already reproduces (e.g. MIT with its copyright line).
        lib["licenseKeys"] = [i for i in dict.fromkeys(spdx_ids(lib["licenseId"]))
                              if i in texts and norm(texts[i])[:200] not in notice]
    return {
        "app": {"name": "AGY Remote", "licenseId": "AGPL-3.0", "textKey": "AGPL-3.0"},
        "libraries": libs,
        "texts": texts,
    }


def main():
    ios = "--ios" in sys.argv
    out = os.path.join(IOS, "Sources/Resources/licenses.json") if ios else os.path.join(ROOT, "app/src/main/assets/licenses.json")
    data = build(ios)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
    print(out, os.path.getsize(out), "bytes,", len(data["libraries"]), "libraries,", len(data["texts"]), "texts")


if __name__ == "__main__":
    main()
