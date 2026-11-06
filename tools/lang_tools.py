"""Writes strings into the language files, keeping their order and layout.

update(strings, hk) takes {key: (zh_cn, zh_tw, en_us, ja_jp, ko_kr)}; zh_hk gets the zh_tw
text unless hk has its own. A key already in a file is replaced in place; new keys go, in
the order given, after the last key of the file that starts with `after`.
"""
import json

DIR = "common/src/main/resources/assets/tukuai/lang"
ORDER = ["zh_cn", "zh_tw", "en_us", "ja_jp", "ko_kr"]


def update(strings, hk, after="tukuai.guide.mahjong"):
    for lang in ORDER + ["zh_hk"]:
        path = f"{DIR}/{lang}.json"
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        index = 1 if lang == "zh_hk" else ORDER.index(lang)
        new = {}
        for key, texts in strings.items():
            text = hk.get(key, texts[index]) if lang == "zh_hk" else texts[index]
            if key in data:
                data[key] = text
            else:
                new[key] = text
        out = {}
        last = max((i for i, key in enumerate(data) if key.startswith(after)), default=len(data) - 1)
        for i, (key, value) in enumerate(data.items()):
            out[key] = value
            if i == last:
                out.update(new)
        with open(path, "w", encoding="utf-8") as f:
            f.write(json.dumps(out, ensure_ascii=False, indent=2) + "\n")
