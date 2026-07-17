#!/usr/bin/env python3
"""从已解压的本地资源目录生成开放学习库索引。"""
import argparse, csv, hashlib, json, shutil, subprocess
from pathlib import Path

SKIP_POETRY = {"rank", "strains", "loader", "images", "御定全唐詩"}


def json_lines(path):
    """逐行读取 JSONL，坏行跳过。"""
    with path.open(encoding="utf-8") as f:
        for line in f:
            try:
                yield json.loads(line)
            except (ValueError, TypeError):
                pass


def simplify_file(path):
    """整文件繁转简；无 opencc 时保持原样。"""
    tmp = path.with_suffix(path.suffix + ".simp")
    try:
        subprocess.run(
            ["opencc", "-c", "t2s.json", "-i", str(path), "-o", str(tmp)],
            check=True, capture_output=True, text=True,
        )
        tmp.replace(path)
    except (FileNotFoundError, subprocess.CalledProcessError):
        if tmp.exists():
            tmp.unlink()


def dedupe_jsonl(path):
    """按整行 SHA-1 去重，保持首次出现顺序。"""
    seen = set()
    tmp = path.with_suffix(path.suffix + ".dedup")
    with path.open(encoding="utf-8") as src, tmp.open("w", encoding="utf-8") as out:
        for line in src:
            digest = hashlib.sha1(line.encode()).digest()
            if digest in seen:
                continue
            seen.add(digest)
            out.write(line)
    tmp.replace(path)


def hanzi(source, target):
    """生成汉字笔顺单字 JSON。"""
    target.mkdir(parents=True, exist_ok=True)
    for old in target.glob("*.json"):
        old.unlink()
    dictionary = {x.get("character"): x for x in json_lines(source / "dictionary.txt")}
    for graphic in json_lines(source / "graphics.txt"):
        char = graphic.get("character")
        if not char:
            continue
        item = dictionary.get(char, {})
        item.update(graphic)
        (target / f"{ord(char):x}.json").write_text(
            json.dumps(item, ensure_ascii=False, separators=(",", ":")), encoding="utf-8"
        )


def ecdict(source, target):
    """按词头两字母分片写入英汉词典。"""
    csv_path = source if source.is_file() else source / "ecdict.csv"
    target.mkdir(parents=True, exist_ok=True)
    for old in target.glob("*.jsonl"):
        old.unlink()
    handles = {}
    try:
        with csv_path.open(encoding="utf-8", newline="") as f:
            for item in csv.DictReader(f):
                word = (item.get("word") or "").lower()
                key = "".join(c if c.isalpha() and c.isascii() else "_" for c in word[:2])
                if not key:
                    continue
                if key not in handles:
                    handles[key] = (target / f"{key}.jsonl").open("w", encoding="utf-8")
                row = {k: item.get(k, "") for k in ("word", "phonetic", "definition", "translation", "tag", "exchange")}
                handles[key].write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")
    finally:
        for out in handles.values():
            out.close()


def iter_poetry_items(path):
    """兼容列表 JSON 与蒙学嵌套结构。"""
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (ValueError, OSError):
        return
    if isinstance(data, list):
        for item in data:
            if isinstance(item, dict):
                yield item
        return
    if not isinstance(data, dict):
        return
    for block in data.get("content") or []:
        if not isinstance(block, dict):
            continue
        nested = block.get("content")
        if isinstance(nested, list):
            for item in nested:
                if isinstance(item, dict):
                    yield item
        elif block.get("title") or block.get("chapter"):
            yield block


def normalize_poem(item):
    """统一成 title/author/paragraphs。"""
    title = item.get("title") or item.get("chapter") or item.get("rhythmic")
    if not title:
        return None
    paragraphs = item.get("paragraphs") or item.get("content")
    if not paragraphs:
        return None
    if isinstance(paragraphs, str):
        paragraphs = [paragraphs]
    if not isinstance(paragraphs, list):
        return None
    author = item.get("author") or ""
    if isinstance(author, str):
        author = author.replace("（唐）", "").replace("(唐)", "").strip()
    keep = {"title": title, "author": author, "paragraphs": paragraphs}
    if item.get("rhythmic"):
        keep["rhythmic"] = item["rhythmic"]
    return keep


def poetry_paths(source):
    """蒙学与唐诗三百首优先，保证常见篇目排在前面。"""
    seen_paths = set()
    ordered = []
    for path in [
        source / "蒙学",
        source / "全唐诗" / "唐诗三百首.json",
        source / "宋词" / "ci.song.300.json",
    ]:
        if path.is_file():
            ordered.append(path)
            seen_paths.add(path.resolve())
        elif path.is_dir():
            for child in sorted(path.rglob("*.json")):
                ordered.append(child)
                seen_paths.add(child.resolve())
    for path in sorted(source.rglob("*.json")):
        if path.resolve() in seen_paths:
            continue
        if any(part in SKIP_POETRY for part in path.parts):
            continue
        ordered.append(path)
    return ordered


def _shard_name(text):
    """取首字 unicode 十六进制作为分片名；空则 other。"""
    if not text:
        return "other"
    return format(ord(text[0]), "x")


def build_poetry_index(poetry_path):
    """
    生成分片索引目录 poetry-idx/：
    - t/<首字>.tsv 按标题首字
    - a/<首字>.tsv 按作者首字
    每行：offset\\tlength\\ttitle\\tauthor\\tsnippet
    """
    base = poetry_path.parent / "poetry-idx"
    if base.exists():
        shutil.rmtree(base)
    title_dir = base / "t"
    author_dir = base / "a"
    title_dir.mkdir(parents=True)
    author_dir.mkdir(parents=True)
    handles = {}
    offset = 0
    try:
        with poetry_path.open("rb") as src:
            while True:
                raw = src.readline()
                if not raw:
                    break
                length = len(raw)
                body = raw[:-1] if raw.endswith(b"\n") else raw
                try:
                    item = json.loads(body.decode("utf-8"))
                except (ValueError, UnicodeDecodeError):
                    offset += length
                    continue
                title = str(item.get("title") or "").replace("\t", " ").replace("\n", " ")
                author = str(item.get("author") or "").replace("\t", " ").replace("\n", " ")
                paras = item.get("paragraphs") or []
                if isinstance(paras, list):
                    bits = [p if isinstance(p, str) else json.dumps(p, ensure_ascii=False) for p in paras[:2]]
                    snippet = " ".join(bits)
                else:
                    snippet = str(paras)
                snippet = snippet.replace("\t", " ").replace("\n", " ")[:80]
                row = f"{offset}\t{len(body)}\t{title}\t{author}\t{snippet}\n"
                for kind, text, folder in (("t", title, title_dir), ("a", author, author_dir)):
                    name = _shard_name(text)
                    key = (kind, name)
                    if key not in handles:
                        handles[key] = (folder / f"{name}.tsv").open("w", encoding="utf-8")
                    handles[key].write(row)
                offset += length
    finally:
        for out in handles.values():
            out.close()
    return base


def poetry(source, target):
    """导入诗词正文并生成检索索引。"""
    seen = set()
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open("w", encoding="utf-8") as out:
        for path in poetry_paths(source):
            for item in iter_poetry_items(path):
                keep = normalize_poem(item)
                if not keep:
                    continue
                packed = json.dumps(keep, ensure_ascii=False, separators=(",", ":"))
                digest = hashlib.sha1(packed.encode()).digest()
                if digest in seen:
                    continue
                seen.add(digest)
                out.write(packed + "\n")
    simplify_file(target)
    dedupe_jsonl(target)
    build_poetry_index(target)


def english(source, target):
    """只复制图片和音频到发布目录。"""
    assets = source / "assets" if (source / "assets").is_dir() else source
    if target.exists():
        shutil.rmtree(target)
    target.mkdir(parents=True, exist_ok=True)
    for path in assets.rglob("*"):
        if not path.is_file():
            continue
        if path.suffix.lower() not in {".mp3", ".wav", ".ogg", ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg"}:
            continue
        dest = target / path.relative_to(assets)
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, dest)


if __name__ == "__main__":
    p = argparse.ArgumentParser(description="生成开放学习库本地数据")
    p.add_argument("kind", choices=("hanzi", "ecdict", "poetry", "english", "poetry-index"))
    p.add_argument("source", type=Path)
    p.add_argument("target", type=Path, nargs="?", default=None)
    a = p.parse_args()
    if a.kind == "poetry-index":
        # source 即为 poetry.jsonl
        build_poetry_index(a.source)
    else:
        if a.target is None:
            raise SystemExit("需要 target 目录或文件")
        {"hanzi": hanzi, "ecdict": ecdict, "poetry": poetry, "english": english}[a.kind](a.source, a.target)
