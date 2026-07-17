# 开放学习库数据

本目录是发布用数据包，安装时解压到 `/var/lib/family-learning/`。

| 文件 | 用途 |
|------|------|
| `characters.tar.gz` | 汉字笔顺 |
| `dictionary.tar.gz` | 英汉词典分片 |
| `poetry.jsonl.gz` | 古诗词正文 |
| `poetry-idx.tar.gz` | 古诗词分片索引（按标题/作者首字） |
| `textbooks.json` | 教材 PDF 路径/链接（不存 PDF 文件） |
| `english-kids.tar.gz` | 儿童英语图片/音频 |

从已解压原料重新生成：

```bash
python3 scripts/import-datasets.py hanzi /path/to/makemeahanzi-master datasets/characters
python3 scripts/import-datasets.py ecdict /path/to/ecdict-master datasets/dictionary
python3 scripts/import-datasets.py poetry /path/to/chinese-poetry-master datasets/poetry.jsonl
python3 scripts/import-datasets.py english /path/to/english-for-kids-master datasets/english-kids
# 仅重建诗词分片索引：
python3 scripts/import-datasets.py poetry-index /var/lib/family-learning/datasets/poetry.jsonl
```
