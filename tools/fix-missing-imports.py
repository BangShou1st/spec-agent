#!/usr/bin/env python3
"""Fix missing imports after package splits, driven by javac errors.

Parses Gradle/javac diagnostics of the form (Chinese or English locale):
    <file>:<line>: 错误: 找不到符号   /   error: cannot find symbol
        符号:   类 X            /   symbol:   class X
Then locates the unique class named X in the source tree and inserts the
import into <file> (after the package statement), unless already present.
"""
import os, re, subprocess, sys
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BACKEND = os.path.join(ROOT, "backend")
SRC = os.path.join(BACKEND, "src")

def index_classes():
    idx = defaultdict(list)  # simple name -> [fqn]
    for dirpath, _d, files in os.walk(SRC):
        for fn in files:
            if fn.endswith(".java"):
                rel = os.path.relpath(os.path.join(dirpath, fn), SRC).replace("\\", "/")
                parts = rel.split("/")
                pkg = ".".join(parts[2:-1])
                idx[fn[:-5]].append(pkg + "." + fn[:-5])
    return idx

def compile_errors():
    out = subprocess.run(
        ["cmd", "/c", "gradlew.bat", "compileJava", "compileTestJava", "--console", "plain"],
        cwd=BACKEND, capture_output=True, text=True, errors="replace", timeout=900)
    text = out.stdout + out.stderr
    errs = []
    lines = text.splitlines()
    for i, line in enumerate(lines):
        m = re.match(r"^(.*\.java):(\d+): .*?(?:错误|error): 找不到符号|cannot find symbol", line)
        m = re.match(r"^(.*\.java):(\d+): .*?(?:找不到符号|cannot find symbol)", line)
        if m:
            symbol = None
            for j in range(i + 1, min(i + 5, len(lines))):
                sm = re.search(r"(?:类|class)\s+([A-Za-z_]\w*)", lines[j])
                sm2 = re.search(r"(?:程序包|package)\s+([\w.]+)", lines[j])
                if sm:
                    symbol = sm.group(1)
                    break
                if sm2:
                    symbol = ("PKG", sm2.group(1))
                    break
            errs.append((m.group(1), int(m.group(2)), symbol))
    return errs, text

def main():
    idx = index_classes()
    errs, _ = compile_errors()
    print(f"{len(errs)} cannot-find-symbol errors")
    fixes = defaultdict(set)  # file -> {import line}
    unresolved = []
    for path, line, symbol in errs:
        if not symbol or isinstance(symbol, tuple):
            unresolved.append((path, line, symbol))
            continue
        cands = idx.get(symbol, [])
        if not cands:
            unresolved.append((path, line, symbol))
            continue
        if len(cands) > 1:
            # prefer same-module package heuristic: choose the one whose package
            # shares the longest prefix with the importing file's package
            rel = os.path.relpath(path, SRC).replace("\\", "/")
            fpkg = ".".join(rel.split("/")[2:-1])
            cands.sort(key=lambda c: -len(os.path.commonprefix([c.split("."), fpkg.split(".")])))
        fqn = cands[0]
        with open(path, encoding="utf-8") as fh:
            text = fh.read()
        if f"import {fqn};" in text:
            continue
        fixes[path].add(f"import {fqn};")
    for path, imports in fixes.items():
        with open(path, encoding="utf-8") as fh:
            text = fh.read()
        ins = "\n".join(sorted(imports))
        text = re.sub(r"^(package [\w.]+;)", r"\1\n\n" + ins, text, count=1, flags=re.M)
        with open(path, "w", encoding="utf-8", newline="") as fh:
            fh.write(text)
    print(f"added imports to {len(fixes)} files")
    if unresolved:
        print("UNRESOLVED:")
        for u in unresolved[:30]:
            print("  ", u)

if __name__ == "__main__":
    main()
