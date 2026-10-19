#!/usr/bin/env python3
"""Checks that every language file has the same keys as en_us.json, with the same number of %s in each."""
import json
import pathlib
import sys

lang = pathlib.Path(__file__).resolve().parents[2] / "common/src/main/resources/assets/tukuai/lang"
base = json.loads((lang / "en_us.json").read_text(encoding="utf-8"))
problems = []
for path in sorted(lang.glob("*.json")):
    texts = json.loads(path.read_text(encoding="utf-8"))
    for key in sorted(base.keys() - texts.keys()):
        problems.append(f"{path.name}: missing {key}")
    for key in sorted(texts.keys() - base.keys()):
        problems.append(f"{path.name}: {key} is not in en_us.json")
    for key in sorted(base.keys() & texts.keys()):
        if texts[key].count("%s") != base[key].count("%s"):
            problems.append(f"{path.name}: {key} has a different number of %s than en_us.json")
for problem in problems:
    print(problem)
sys.exit(1 if problems else 0)
