#!/usr/bin/env python3
"""从 ECDICT 筛选约 5000 常用词，用 espeak-ng 合成美音 mp3，并补儿童图卡分类元数据。"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import tarfile
import tempfile
from pathlib import Path

TAG_PRIORITY = {
    "zk": 100,
    "gk": 90,
    "cet4": 80,
    "ky": 70,
    "cet6": 60,
    "ielts": 50,
    "toefl": 40,
    "gre": 20,
}

TAG_LABEL = {
    "zk": "中考",
    "gk": "高考",
    "cet4": "四级",
    "cet6": "六级",
    "ky": "考研",
    "ielts": "雅思",
    "toefl": "托福",
    "gre": "GRE",
}

# 主题词表：一词可多标签
THEME_WORDS = {
    "水果": "apple banana orange grape pear peach lemon mango cherry strawberry watermelon pineapple kiwi coconut blueberry".split(),
    "动物": "cat dog bird fish horse pig rabbit sheep frog lion mouse turtle dolphin giraffe chicken chick cow bear tiger duck goose snake".split(),
    "衣服": "skirt pants blouse dress boot shirt coat shoe sock hat jacket glove scarf".split(),
    "情绪": "sad angry happy tired surprised scared smile laugh cry love fear hope".split(),
    "动作": "cry dance dive draw fly hug jump open play point ride run sing skip swim walk eat drink sleep read write".split(),
    "交通": "bus bicycle ship airplane car train helicopter motorcycle truck boat taxi metro".split(),
    "学习": "book pen pencil bag notebook chalk scissors ruler desk school teacher student class homework".split(),
    "颜色": "red blue green yellow black white pink purple brown orange gray".split(),
    "家庭": "father mother brother sister baby family home house room kitchen".split(),
    "身体": "hand head eye ear nose mouth foot arm leg hair face".split(),
    "天气": "sun rain snow wind cloud hot cold warm cool weather".split(),
    "食物": "bread milk rice egg cake water juice meat fish soup noodle".split(),
}

KIDS_CATEGORIES = {
    "动作": "cry dance dive draw fish fly hug jump open play point ride run sing skip swim".split(),
    "动物": "cat chick chicken dog horse pig rabbit sheep bird frog giraffe lion mouse turtle dolphin".split(),
    "衣服": "skirt pants blouse dress boot shirt coat shoe".split(),
    "情绪": "sad angry happy tired surprised scared smile laugh".split(),
    "交通": "bus bicycle ship airplane car train helicopter motorcycle".split(),
    "学习": "bag pen pencil notebook chalk scissors ruler book".split(),
}


def load_dictionary(dict_tar: Path):
    rows = []
    with tarfile.open(dict_tar, "r:gz") as tar:
        for member in tar.getmembers():
            if not member.name.endswith(".jsonl"):
                continue
            file = tar.extractfile(member)
            if not file:
                continue
            for line in file:
                try:
                    rows.append(json.loads(line))
                except json.JSONDecodeError:
                    continue
    return rows


def score_word(item: dict) -> int:
    word = item.get("word") or ""
    if not word.isalpha() or not word.islower():
        return -1
    if len(word) < 2 or len(word) > 14:
        return -1
    tags = set((item.get("tag") or "").split())
    if not tags & set(TAG_PRIORITY):
        return -1
    score = max(TAG_PRIORITY[t] for t in tags if t in TAG_PRIORITY)
    if item.get("phonetic"):
        score += 5
    score += max(0, 12 - len(word))
    return score


def theme_tags(word: str) -> list[str]:
    return [name for name, words in THEME_WORDS.items() if word in words]


def exam_tags(raw: str) -> list[str]:
    out = []
    for t in (raw or "").split():
        label = TAG_LABEL.get(t)
        if label and label not in out:
            out.append(label)
    return out


def letter_tag(word: str) -> str:
    return "字母" + word[0].upper()


def select_words(rows, limit: int):
    ranked = []
    for item in rows:
        s = score_word(item)
        if s < 0:
            continue
        ranked.append((s, item.get("word"), item))
    ranked.sort(key=lambda x: (-x[0], x[1]))
    seen = set()
    selected = []
    for _, word, item in ranked:
        if word in seen:
            continue
        seen.add(word)
        tags = exam_tags(item.get("tag") or "")
        tags.extend(theme_tags(word))
        tags.append(letter_tag(word))
        # 去重保序
        uniq = []
        for t in tags:
            if t not in uniq:
                uniq.append(t)
        selected.append(
            {
                "word": word,
                "phonetic": item.get("phonetic") or "",
                "translation": (item.get("translation") or "").split("\\n")[0].strip(),
                "tags": uniq,
            }
        )
        if len(selected) >= limit:
            break
    return selected


def synthesize(word: str, dest: Path):
    dest.parent.mkdir(parents=True, exist_ok=True)
    wav = dest.with_suffix(".wav")
    subprocess.run(
        ["espeak-ng", "-v", "en-us", "-s", "140", "-w", str(wav), word],
        check=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    # espeak wav 是 22050 mono s16le；lame -r 需要 raw，这里直接喂 wav 更稳
    subprocess.run(
        ["lame", "--silent", "-b", "24", "-m", "m", str(wav), str(dest)],
        check=True,
    )
    wav.unlink(missing_ok=True)


def build_vocab(dict_tar: Path, out_tar: Path, limit: int, work: Path):
    root = work / "english-vocab"
    audio_dir = root / "audio"
    if root.exists():
        shutil.rmtree(root)
    audio_dir.mkdir(parents=True)
    print("loading dictionary…")
    rows = load_dictionary(dict_tar)
    words = select_words(rows, limit)
    print(f"selected {len(words)} words, synthesizing US audio…")
    for i, item in enumerate(words, 1):
        mp3 = audio_dir / f"{item['word']}.mp3"
        try:
            synthesize(item["word"], mp3)
            item["audioPath"] = f"english/vocab/{item['word']}.mp3"
        except Exception as exc:  # noqa: BLE001
            item["audioPath"] = None
            print(f"audio failed {item['word']}: {exc}")
        if i % 200 == 0 or i == len(words):
            print(f"  {i}/{len(words)}")
    with (root / "words.jsonl").open("w", encoding="utf-8") as fh:
        for item in words:
            fh.write(json.dumps(item, ensure_ascii=False) + "\n")
    # 标签统计
    tag_count = {}
    for item in words:
        for t in item["tags"]:
            tag_count[t] = tag_count.get(t, 0) + 1
    meta = {"count": len(words), "tags": [{"id": k, "name": k, "count": v} for k, v in sorted(tag_count.items(), key=lambda x: (-x[1], x[0]))]}
    (root / "meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
    out_tar.parent.mkdir(parents=True, exist_ok=True)
    with tarfile.open(out_tar, "w:gz") as tar:
        tar.add(root, arcname="english-vocab")
    print(f"wrote {out_tar} ({out_tar.stat().st_size / 1024 / 1024:.1f} MB)")


def patch_kids(kids_tar: Path, out_tar: Path, work: Path):
    extract = work / "kids-extract"
    if extract.exists():
        shutil.rmtree(extract)
    extract.mkdir(parents=True)
    with tarfile.open(kids_tar, "r:gz") as tar:
        tar.extractall(extract)
    kids = extract / "english-kids"
    if not kids.is_dir():
        # 兼容已是 kids 目录
        kids = extract / "kids" if (extract / "kids").is_dir() else extract
    cards = []
    word_tags = {}
    for tag, words in KIDS_CATEGORIES.items():
        for w in words:
            word_tags.setdefault(w, []).append(tag)
    # fish 动作/动物都有；图可能是 fish / fish1
    for stem_path in list((kids / "img").glob("*")) + list((kids / "audio").glob("*")):
        stem = stem_path.stem.lower()
        if stem in {"star", "repeat", "success", "error", "screenshot", "educational", "clothes", "emotions", "audio", "rotate", "star-win", "failure", "correct"}:
            continue
        word = "fish" if stem == "fish1" else stem
        tags = list(dict.fromkeys(word_tags.get(word, []) + theme_tags(word)))
        if not tags:
            tags = ["其他"]
        cards.append({"word": word, "stem": stem, "tags": tags})
    # 去重：同一 word 合并 tags，优先保留有图的 stem
    merged = {}
    for card in cards:
        cur = merged.get(card["word"])
        if not cur:
            merged[card["word"]] = card
            continue
        for t in card["tags"]:
            if t not in cur["tags"]:
                cur["tags"].append(t)
        if cur["stem"] == card["word"] or (card["stem"] != "fish1" and cur["stem"] == "fish1"):
            cur["stem"] = card["stem"]
    cards = sorted(merged.values(), key=lambda x: x["word"])
    (kids / "cards.json").write_text(json.dumps({"cards": cards}, ensure_ascii=False, indent=2), encoding="utf-8")
    # 打包为 english-kids/
    out_tar.parent.mkdir(parents=True, exist_ok=True)
    with tarfile.open(out_tar, "w:gz") as tar:
        tar.add(kids, arcname="english-kids")
    print(f"patched kids -> {out_tar} ({out_tar.stat().st_size / 1024 / 1024:.1f} MB), cards={len(cards)}")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--dict", type=Path, default=Path("datasets/dictionary.tar.gz"))
    p.add_argument("--kids", type=Path, default=Path("datasets/english-kids.tar.gz"))
    p.add_argument("--out-vocab", type=Path, default=Path("datasets/english-vocab.tar.gz"))
    p.add_argument("--out-kids", type=Path, default=Path("datasets/english-kids.tar.gz"))
    p.add_argument("--limit", type=int, default=5000)
    p.add_argument("--skip-audio", action="store_true")
    p.add_argument("--kids-only", action="store_true")
    a = p.parse_args()
    work = Path(tempfile.mkdtemp(prefix="en-build-"))
    try:
        if not a.kids_only:
            if a.skip_audio:
                raise SystemExit("skip-audio 仅用于调试，正式包需要音频")
            build_vocab(a.dict, a.out_vocab, a.limit, work)
        patch_kids(a.kids, a.out_kids, work)
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    main()
