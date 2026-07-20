package cc.ccwu.familylearning.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * 开放学习库：本地 datasets 查询。
 * 列表类接口统一返回翻页结构；详情（笔顺动画等）按需再取。
 */
@Service
public class LibraryService {
    private static final int LIMIT = 50;
    private static final String TREE = "https://api.github.com/repos/TapXWorld/ChinaTextbook/git/trees/master?recursive=1";

    private final Path root;
    private final Path resourceRoot;
    private final ObjectMapper mapper;
    private volatile List<Map<String, Object>> vocabCache;
    /** 汉字列表缓存：文件名十六进制码点 -> 字符。 */
    private volatile List<String> characterCache;
    /** 精选诗词缓存（默认浏览，避免扫 30 万首全库）。 */
    private volatile List<JsonNode> featuredPoetryCache;

    public LibraryService(@Value("${family-learning.dataset-dir}") String dir,
                          @Value("${family-learning.resource-dir}") String resourceDir,
                          ObjectMapper mapper) {
        this.root = Paths.get(dir).toAbsolutePath().normalize();
        this.resourceRoot = Paths.get(resourceDir).toAbsolutePath().normalize();
        this.mapper = mapper;
    }

    /** 确保基础目录存在，避免首次查询报错。 */
    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(root.resolve("characters"));
        Files.createDirectories(root.resolve("dictionary"));
        Files.createDirectories(englishKidsRoot());
    }

    /** 数据/资源就绪状态，供前端提示缺包。 */
    public Map<String, Object> status() throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("characters", countFiles(root.resolve("characters"), ".json") > 0);
        result.put("dictionary", countFiles(root.resolve("dictionary"), ".jsonl") > 0);
        result.put("poetry", Files.isRegularFile(root.resolve("poetry.jsonl")));
        result.put("poetryIndex", Files.isDirectory(root.resolve("poetry-idx")));
        result.put("textbooks", Files.isRegularFile(root.resolve("textbooks.json")));
        Path kids = englishKidsRoot();
        result.put("english", Files.isDirectory(kids.resolve("img")) || Files.isDirectory(kids.resolve("audio")));
        result.put("englishCards", loadKidsCards().size());
        List<Map<String, Object>> vocab = loadVocab();
        result.put("vocab", !vocab.isEmpty());
        result.put("vocabCount", vocab.size());
        return result;
    }

    /** 儿童英语图卡（兼容旧调用，返回首屏列表）。 */
    public List<Map<String, Object>> englishKids(String query) throws IOException {
        Map<String, Object> page = englishKidsPage(query, "", 1, LIMIT);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
        return items == null ? Collections.emptyList() : items;
    }

    /** 儿童英语图卡：分类标签 + 翻页。 */
    public Map<String, Object> englishKidsPage(String query, String tag, int page, int size) throws IOException {
        List<Map<String, Object>> all = loadKidsCards();
        return pageItems(filterTagged(all, query, tag), tagCounts(all), page, size);
    }

    /** 常用英语词汇（约 5000）：多标签 + 翻页。 */
    public Map<String, Object> englishVocabPage(String query, String tag, int page, int size) throws IOException {
        List<Map<String, Object>> all = loadVocab();
        return pageItems(filterTagged(all, query, tag), tagCounts(all), page, size);
    }

    /** 教材目录树：按路径前缀浏览；有 query 时走搜索。 */
    public Map<String, Object> textbooksTree(String prefix, String query) throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        String q = clean(query);
        if (!q.isEmpty()) {
            result.put("mode", "search");
            result.put("items", textbooks(q));
            return result;
        }
        Path cache = root.resolve("textbooks.json");
        if (!Files.isRegularFile(cache)) refreshTextbooks(cache);
        String base = normalizePrefix(prefix);
        Set<String> folders = new TreeSet<>();
        List<JsonNode> books = new ArrayList<>();
        for (JsonNode item : mapper.readTree(cache.toFile())) {
            String path = item.path("path").asText();
            if (!base.isEmpty() && !path.startsWith(base)) continue;
            String rest = base.isEmpty() ? path : path.substring(base.length());
            int slash = rest.indexOf('/');
            if (slash >= 0) folders.add(rest.substring(0, slash));
            else if (!rest.isEmpty()) {
                books.add(item);
                if (books.size() >= LIMIT) break;
            }
        }
        result.put("mode", "browse");
        result.put("prefix", base.isEmpty() ? "" : base.substring(0, base.length() - 1));
        result.put("folders", new ArrayList<>(folders));
        result.put("books", books);
        return result;
    }

    /** 按单字读取汉字笔顺详情（含笔画轨迹）。 */
    public JsonNode character(String value) throws IOException {
        if (value == null || value.codePointCount(0, value.length()) != 1) {
            throw new IllegalArgumentException("请输入一个汉字");
        }
        Path file = root.resolve("characters").resolve(Integer.toHexString(value.codePointAt(0)) + ".json");
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("字库中没有这个汉字");
        return mapper.readTree(file.toFile());
    }

    /**
     * 汉字浏览/搜索翻页（统一 query + tag）。
     * tag=common 为常用字；空 tag 为全部字库。列表只给字头。
     */
    public Map<String, Object> characterPage(String query, String tag, int page, int size) throws IOException {
        String key = clean(query);
        String tagKey = clean(tag);
        List<Map<String, Object>> tags = characterTags();
        List<Map<String, Object>> items = new ArrayList<>();
        if (!key.isEmpty()) {
            if (key.codePointCount(0, key.length()) != 1) {
                throw new IllegalArgumentException("请输入一个汉字");
            }
            try {
                JsonNode node = character(key);
                Map<String, Object> row = lightCharacter(node);
                if (tagKey.isEmpty() || characterMatchesTag(String.valueOf(row.get("character")), tagKey)) {
                    items.add(row);
                }
            } catch (IllegalArgumentException ignored) {
                // 字库没有则空列表
            }
            return pageNodes(items, tags, page, size);
        }
        if ("common".equals(tagKey)) {
            Set<String> all = new HashSet<>(loadCharacters());
            for (String ch : commonCharacters()) {
                if (!all.contains(ch)) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("character", ch);
                items.add(row);
            }
        } else {
            for (String ch : loadCharacters()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("character", ch);
                items.add(row);
            }
        }
        return pageNodes(items, tags, page, size);
    }

    /**
     * 英汉词典翻页（统一 query + tag）。
     * tag 为空=跨字母浏览（有上限）；tag=a..z 按字母；有 query 时搜分片。
     */
    public Map<String, Object> dictionaryPage(String query, String tag, int page, int size) throws IOException {
        String word = clean(query).toLowerCase(Locale.ROOT);
        String letter = clean(tag).toLowerCase(Locale.ROOT);
        List<Map<String, Object>> tags = dictionaryLetterTags();
        if (!word.isEmpty()) {
            String shard = word.substring(0, Math.min(2, word.length())).replaceAll("[^a-z]", "_");
            List<JsonNode> hits = searchJsonl(root.resolve("dictionary").resolve(shard + ".jsonl"), word, "word");
            // 搜索结果再按字母标签收窄
            if (!letter.isEmpty()) {
                char expect = letter.charAt(0);
                hits = hits.stream()
                        .filter(n -> {
                            String w = n.path("word").asText("");
                            return !w.isEmpty() && Character.toLowerCase(w.charAt(0)) == expect;
                        })
                        .collect(java.util.stream.Collectors.toList());
            }
            return pageNodes(toDictRows(hits), tags, page, size);
        }
        List<JsonNode> rows;
        if (letter.isEmpty()) {
            rows = readDictionaryBrowseAll();
        } else {
            if (letter.length() > 1) letter = letter.substring(0, 1);
            rows = readDictionaryLetter(letter);
        }
        return pageNodes(toDictRows(rows), tags, page, size);
    }

    /** 兼容旧调用：按词查询词典。 */
    public List<JsonNode> dictionary(String query) throws IOException {
        Map<String, Object> page = dictionaryPage(query, "", 1, LIMIT);
        return castItems(page);
    }

    /**
     * 古诗词翻页（统一 query + tag）。
     * 空 tag=精选；tag=作者；query 与 tag 可叠加。
     */
    public Map<String, Object> poetryPage(String query, String tag, int page, int size) throws IOException {
        String key = clean(query);
        String author = clean(tag);
        List<JsonNode> featured = loadFeaturedPoetry();
        List<JsonNode> source = key.isEmpty() ? featured : poetry(key);
        if (!author.isEmpty()) {
            List<JsonNode> filtered = filterPoetryByAuthor(source, author);
            // 精选里没有该作者时，再按作者名查全库索引
            if (filtered.isEmpty() && key.isEmpty()) filtered = poetry(author);
            source = filtered;
        }
        List<Map<String, Object>> tags = poetryAuthorTags(featured);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode node : source) rows.add(lightPoetry(node));
        return pageNodes(rows, tags, page, size);
    }

    /** 兼容旧调用：按关键词查诗词。 */
    public List<JsonNode> poetry(String query) throws IOException {
        String key = clean(query);
        if (key.isEmpty()) return Collections.emptyList();
        Path data = root.resolve("poetry.jsonl");
        Path indexDir = root.resolve("poetry-idx");
        if (Files.isDirectory(indexDir) && Files.isRegularFile(data)) {
            return searchPoetryShards(indexDir, data, key);
        }
        Path legacy = root.resolve("poetry.idx");
        if (Files.isRegularFile(legacy) && Files.isRegularFile(data)) {
            return readPoetryHits(legacy, data, key);
        }
        return searchJsonl(data, key, "title");
    }

    /** 教材目录：本地 textbooks.json，只含路径和链接。 */
    public List<JsonNode> textbooks(String query) throws IOException {
        Path cache = root.resolve("textbooks.json");
        if (!Files.isRegularFile(cache)) refreshTextbooks(cache);
        String key = clean(query).toLowerCase(Locale.ROOT);
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode item : mapper.readTree(cache.toFile())) {
            if (key.isEmpty() || item.path("path").asText().toLowerCase(Locale.ROOT).contains(key)) {
                result.add(item);
                if (result.size() >= LIMIT) break;
            }
        }
        return result;
    }

    /**
     * 通用 JSONL 检索：精确字段优先，其次字段包含，再次全文包含。
     */
    private List<JsonNode> searchJsonl(Path file, String query, String exactField) throws IOException {
        if (!Files.isRegularFile(file)) return Collections.emptyList();
        List<JsonNode> exact = new ArrayList<>(), partial = new ArrayList<>(), fuzzy = new ArrayList<>();
        String key = query.toLowerCase(Locale.ROOT);
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            Iterator<String> iterator = lines.iterator();
            while (iterator.hasNext()) {
                JsonNode item;
                try { item = mapper.readTree(iterator.next()); } catch (Exception ignored) { continue; }
                String text = item.toString().toLowerCase(Locale.ROOT);
                if (!text.contains(key)) continue;
                String field = exactField == null ? "" : item.path(exactField).asText();
                if (exactField != null && field.equalsIgnoreCase(query)) {
                    if (exact.size() < LIMIT) exact.add(item);
                } else if (exactField != null && field.toLowerCase(Locale.ROOT).contains(key)) {
                    if (partial.size() < LIMIT) partial.add(item);
                } else if (fuzzy.size() < LIMIT) {
                    fuzzy.add(item);
                }
                if (exact.size() >= LIMIT) break;
            }
        }
        return merge(exact, partial, fuzzy);
    }

    /** 只扫查询首字对应的标题分片与作者分片，避免读整库索引。 */
    private List<JsonNode> searchPoetryShards(Path indexDir, Path data, String query) throws IOException {
        String shard = Integer.toHexString(query.codePointAt(0));
        List<long[]> exact = new ArrayList<>(), partial = new ArrayList<>(), fuzzy = new ArrayList<>();
        collectPoetryHits(indexDir.resolve("t").resolve(shard + ".tsv"), query, exact, partial, fuzzy);
        collectPoetryHits(indexDir.resolve("a").resolve(shard + ".tsv"), query, exact, partial, fuzzy);
        return loadPoetry(data, merge(exact, partial, fuzzy));
    }

    /** 兼容旧版单文件 poetry.idx。 */
    private List<JsonNode> readPoetryHits(Path index, Path data, String query) throws IOException {
        List<long[]> exact = new ArrayList<>(), partial = new ArrayList<>(), fuzzy = new ArrayList<>();
        collectPoetryHits(index, query, exact, partial, fuzzy);
        return loadPoetry(data, merge(exact, partial, fuzzy));
    }

    /**
     * 索引行：offset\\tlength\\ttitle\\tauthor\\tsnippet
     * 按标题精确 / 标题作者包含 / 摘要包含 三档收集偏移。
     */
    private void collectPoetryHits(Path index, String query,
                                   List<long[]> exact, List<long[]> partial, List<long[]> fuzzy) throws IOException {
        if (!Files.isRegularFile(index)) return;
        String key = query.toLowerCase(Locale.ROOT);
        try (BufferedReader reader = Files.newBufferedReader(index, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\t", 5);
                if (parts.length < 4) continue;
                String title = parts[2];
                String author = parts[3];
                String snippet = parts.length > 4 ? parts[4] : "";
                String hay = (title + " " + author + " " + snippet).toLowerCase(Locale.ROOT);
                if (!hay.contains(key)) continue;
                long[] hit = new long[]{Long.parseLong(parts[0]), Integer.parseInt(parts[1])};
                if (title.equalsIgnoreCase(query)) {
                    if (exact.size() < LIMIT) exact.add(hit);
                } else if (title.toLowerCase(Locale.ROOT).contains(key) || author.toLowerCase(Locale.ROOT).contains(key)) {
                    if (partial.size() < LIMIT) partial.add(hit);
                } else if (fuzzy.size() < LIMIT) {
                    fuzzy.add(hit);
                }
                if (exact.size() >= LIMIT) return;
            }
        }
    }

    /** 按偏移从 poetry.jsonl 读取完整诗句。 */
    private List<JsonNode> loadPoetry(Path data, List<long[]> hits) throws IOException {
        List<JsonNode> result = new ArrayList<>(hits.size());
        try (RandomAccessFile raf = new RandomAccessFile(data.toFile(), "r")) {
            for (long[] hit : hits) {
                byte[] bytes = new byte[(int) hit[1]];
                raf.seek(hit[0]);
                raf.readFully(bytes);
                result.add(mapper.readTree(new String(bytes, StandardCharsets.UTF_8)));
            }
        }
        return result;
    }

    /** 按优先级合并三组结果，总数不超过 LIMIT。 */
    private <T> List<T> merge(List<T> exact, List<T> partial, List<T> fuzzy) {
        List<T> result = new ArrayList<>(exact);
        for (T item : partial) if (result.size() < LIMIT) result.add(item);
        for (T item : fuzzy) if (result.size() < LIMIT) result.add(item);
        return result;
    }

    /** 无本地教材缓存时，从公开仓库树拉取 PDF 路径（仅地址）。 */
    private void refreshTextbooks(Path cache) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(TREE).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "family-learning");
        if (connection.getResponseCode() != 200) throw new IOException("教材目录暂时无法连接");
        ArrayNode output = mapper.createArrayNode();
        try (InputStream input = connection.getInputStream()) {
            for (JsonNode item : mapper.readTree(input).path("tree")) {
                String path = item.path("path").asText();
                if (!"blob".equals(item.path("type").asText()) || !path.matches("(?i).*\\.pdf(?:\\.\\d+)?$")) continue;
                ObjectNode row = output.addObject();
                row.put("path", path);
                row.put("size", item.path("size").asLong());
                row.put("url", "https://github.com/TapXWorld/ChinaTextbook/blob/master/" + encodePath(path));
            }
        } finally {
            connection.disconnect();
        }
        Path temp = cache.resolveSibling(cache.getFileName() + ".tmp");
        mapper.writeValue(temp.toFile(), output);
        Files.move(temp, cache, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** URL 编码教材路径中的中文段落。 */
    private String encodePath(String path) throws UnsupportedEncodingException {
        String[] parts = path.split("/", -1);
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (result.length() > 0) result.append('/');
            result.append(URLEncoder.encode(part, "UTF-8").replace("+", "%20"));
        }
        return result.toString();
    }

    /** 去掉首尾空白并限制长度，防止超长查询。 */
    private String clean(String value) {
        if (value == null) return "";
        String text = value.trim();
        return text.substring(0, Math.min(text.length(), 80));
    }

    private Path englishKidsRoot() {
        Path nested = resourceRoot.resolve("english").resolve("kids");
        if (Files.isDirectory(nested)) return nested;
        Path legacy = resourceRoot.resolve("english").resolve("english-kids");
        if (Files.isDirectory(legacy)) return legacy;
        return nested;
    }

    private List<Map<String, Object>> loadKidsCards() throws IOException {
        Path kids = englishKidsRoot();
        Path images = kids.resolve("img");
        Path audios = kids.resolve("audio");
        Map<String, List<String>> tagMap = new HashMap<>();
        Map<String, String> stemMap = new HashMap<>();
        Path cardsFile = kids.resolve("cards.json");
        if (Files.isRegularFile(cardsFile)) {
            JsonNode rootNode = mapper.readTree(cardsFile.toFile());
            for (JsonNode node : rootNode.path("cards")) {
                String word = node.path("word").asText("");
                if (word.isEmpty()) continue;
                String stem = node.path("stem").asText(word);
                stemMap.put(word.toLowerCase(Locale.ROOT), stem);
                List<String> tags = new ArrayList<>();
                for (JsonNode t : node.path("tags")) tags.add(t.asText());
                tagMap.put(word.toLowerCase(Locale.ROOT), tags);
            }
        }
        Map<String, Map<String, Object>> cards = new TreeMap<>();
        if (Files.isDirectory(images)) try (Stream<Path> paths = Files.list(images)) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                String fileStem = stem(path.getFileName().toString());
                if (fileStem.isEmpty() || isUiAsset(fileStem)) return;
                String word = "fish1".equalsIgnoreCase(fileStem) ? "fish" : fileStem;
                Map<String, Object> card = cards.computeIfAbsent(word.toLowerCase(Locale.ROOT), key -> emptyCard(word));
                card.put("imagePath", relativizeResource(path));
            });
        }
        if (Files.isDirectory(audios)) try (Stream<Path> paths = Files.list(audios)) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                String fileStem = stem(path.getFileName().toString());
                if (fileStem.isEmpty() || isUiAsset(fileStem)) return;
                String word = "fish1".equalsIgnoreCase(fileStem) ? "fish" : fileStem;
                Map<String, Object> card = cards.computeIfAbsent(word.toLowerCase(Locale.ROOT), key -> emptyCard(word));
                card.put("audioPath", relativizeResource(path));
            });
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> card : cards.values()) {
            if (card.get("imagePath") == null && card.get("audioPath") == null) continue;
            String word = String.valueOf(card.get("word"));
            List<String> tags = tagMap.getOrDefault(word.toLowerCase(Locale.ROOT), Collections.singletonList("其他"));
            card.put("tags", tags);
            String preferred = stemMap.get(word.toLowerCase(Locale.ROOT));
            if (preferred != null && Files.isRegularFile(images.resolve(preferred + ".jpg"))) {
                card.put("imagePath", relativizeResource(images.resolve(preferred + ".jpg")));
            }
            result.add(card);
        }
        return result;
    }

    private List<Map<String, Object>> loadVocab() throws IOException {
        List<Map<String, Object>> cached = vocabCache;
        if (cached != null) return cached;
        synchronized (this) {
            if (vocabCache != null) return vocabCache;
            Path file = root.resolve("english-vocab").resolve("words.jsonl");
            if (!Files.isRegularFile(file)) {
                vocabCache = Collections.emptyList();
                return vocabCache;
            }
            List<Map<String, Object>> list = new ArrayList<>();
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    JsonNode node = mapper.readTree(line);
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("word", node.path("word").asText());
                    item.put("phonetic", node.path("phonetic").asText(""));
                    item.put("translation", node.path("translation").asText(""));
                    List<String> tags = new ArrayList<>();
                    for (JsonNode t : node.path("tags")) tags.add(t.asText());
                    item.put("tags", tags);
                    String audio = node.path("audioPath").asText("");
                    item.put("audioPath", audio.isEmpty() ? null : audio);
                    list.add(item);
                }
            }
            vocabCache = list;
            return vocabCache;
        }
    }

    private List<Map<String, Object>> filterTagged(List<Map<String, Object>> source, String query, String tag) {
        String key = clean(query).toLowerCase(Locale.ROOT);
        String tagKey = clean(tag);
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> item : source) {
            String word = String.valueOf(item.getOrDefault("word", ""));
            if (!tagKey.isEmpty()) {
                @SuppressWarnings("unchecked")
                List<String> tags = (List<String>) item.getOrDefault("tags", Collections.emptyList());
                if (tags.stream().noneMatch(t -> tagKey.equals(t))) continue;
            }
            if (!key.isEmpty()) {
                String hay = word.toLowerCase(Locale.ROOT);
                String translation = String.valueOf(item.getOrDefault("translation", "")).toLowerCase(Locale.ROOT);
                if (!hay.contains(key) && !translation.contains(key)) continue;
            }
            filtered.add(item);
        }
        return filtered;
    }

    private List<Map<String, Object>> tagCounts(List<Map<String, Object>> source) {
        Map<String, Integer> counts = new TreeMap<>();
        for (Map<String, Object> item : source) {
            @SuppressWarnings("unchecked")
            List<String> tags = (List<String>) item.getOrDefault("tags", Collections.emptyList());
            for (String tag : tags) {
                if (tag.startsWith("字母")) continue; // 字母标签太多，前端用搜索即可
                counts.merge(tag, 1, Integer::sum);
            }
        }
        List<Map<String, Object>> tags = new ArrayList<>();
        counts.entrySet().stream()
                .sorted((a, b) -> {
                    int byCount = Integer.compare(b.getValue(), a.getValue());
                    return byCount != 0 ? byCount : a.getKey().compareTo(b.getKey());
                })
                .forEach(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", e.getKey());
                    row.put("name", e.getKey());
                    row.put("count", e.getValue());
                    tags.add(row);
                });
        return tags;
    }

    private Map<String, Object> pageItems(List<Map<String, Object>> filtered, List<Map<String, Object>> tags, int page, int size) {
        return pageNodes(filtered, tags, page, size);
    }

    /** 统一翻页响应：items/total/page/size/pageCount/tags。 */
    private Map<String, Object> pageNodes(List<Map<String, Object>> filtered, List<Map<String, Object>> tags, int page, int size) {
        int pageSize = Math.max(1, Math.min(size <= 0 ? 24 : size, 60));
        int total = filtered.size();
        int pageCount = Math.max(1, (int) Math.ceil(total / (double) pageSize));
        int pageNo = Math.max(1, Math.min(page <= 0 ? 1 : page, pageCount));
        int from = (pageNo - 1) * pageSize;
        int to = Math.min(total, from + pageSize);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", from >= total ? Collections.emptyList() : filtered.subList(from, to));
        result.put("total", total);
        result.put("page", pageNo);
        result.put("size", pageSize);
        result.put("pageCount", pageCount);
        result.put("tags", tags == null ? Collections.emptyList() : tags);
        return result;
    }

    /** 缓存汉字字头列表（由文件名码点还原，不读 JSON）。 */
    private List<String> loadCharacters() throws IOException {
        List<String> cached = characterCache;
        if (cached != null) return cached;
        synchronized (this) {
            if (characterCache != null) return characterCache;
            Path dir = root.resolve("characters");
            if (!Files.isDirectory(dir)) {
                characterCache = Collections.emptyList();
                return characterCache;
            }
            List<String> list = new ArrayList<>();
            try (Stream<Path> paths = Files.list(dir)) {
                paths.filter(Files::isRegularFile)
                        .map(path -> path.getFileName().toString())
                        .filter(name -> name.endsWith(".json"))
                        .sorted()
                        .forEach(name -> {
                            try {
                                int cp = Integer.parseInt(name.substring(0, name.length() - 5), 16);
                                list.add(new String(Character.toChars(cp)));
                            } catch (Exception ignored) { /* 跳过坏文件名 */ }
                        });
            }
            characterCache = list;
            return characterCache;
        }
    }

    private Map<String, Object> lightCharacter(JsonNode node) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("character", node.path("character").asText());
        if (node.path("pinyin").isArray()) {
            List<String> py = new ArrayList<>();
            node.path("pinyin").forEach(p -> py.add(p.asText()));
            row.put("pinyin", py);
        } else if (node.has("pinyin")) {
            row.put("pinyin", node.path("pinyin").asText());
        }
        return row;
    }

    private List<Map<String, Object>> toDictRows(List<JsonNode> nodes) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode node : nodes) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("word", node.path("word").asText());
            row.put("phonetic", node.path("phonetic").asText(""));
            row.put("translation", node.path("translation").asText(node.path("definition").asText("")));
            rows.add(row);
        }
        return rows;
    }

    /** 词典字母标签：有数据的 a-z。 */
    private List<Map<String, Object>> dictionaryLetterTags() throws IOException {
        Path dir = root.resolve("dictionary");
        Set<String> letters = new TreeSet<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> paths = Files.list(dir)) {
                paths.filter(Files::isRegularFile)
                        .map(path -> path.getFileName().toString())
                        .filter(name -> name.endsWith(".jsonl") && !name.isEmpty())
                        .forEach(name -> {
                            char c = Character.toLowerCase(name.charAt(0));
                            if (c >= 'a' && c <= 'z') letters.add(String.valueOf(c));
                        });
            }
        }
        List<Map<String, Object>> tags = new ArrayList<>();
        for (String letter : letters) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", letter);
            row.put("name", letter.toUpperCase(Locale.ROOT));
            tags.add(row);
        }
        return tags;
    }

    /** 读取某一字母开头的词典分片（最多合并 240 条供翻页）。 */
    private List<JsonNode> readDictionaryLetter(String letter) throws IOException {
        return readDictionaryShards(letter, 240);
    }

    /** 「全部」：跨字母取样浏览，避免一次装入整部词典。 */
    private List<JsonNode> readDictionaryBrowseAll() throws IOException {
        return readDictionaryShards("", 360);
    }

    /** letter 为空读全部片；否则只读该字母开头的片。 */
    private List<JsonNode> readDictionaryShards(String letter, int cap) throws IOException {
        Path dir = root.resolve("dictionary");
        if (!Files.isDirectory(dir)) return Collections.emptyList();
        List<Path> shards = new ArrayList<>();
        try (Stream<Path> paths = Files.list(dir)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        if (!name.endsWith(".jsonl") || name.isEmpty()) return false;
                        return letter.isEmpty() || name.startsWith(letter);
                    })
                    .sorted()
                    .forEach(shards::add);
        }
        List<JsonNode> rows = new ArrayList<>();
        for (Path shard : shards) {
            try (BufferedReader reader = Files.newBufferedReader(shard, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    try { rows.add(mapper.readTree(line)); } catch (Exception ignored) { continue; }
                    if (rows.size() >= cap) return rows;
                }
            }
        }
        return rows;
    }

    /** 汉字标签：常用。 */
    private List<Map<String, Object>> characterTags() {
        List<Map<String, Object>> tags = new ArrayList<>();
        Map<String, Object> common = new LinkedHashMap<>();
        common.put("id", "common");
        common.put("name", "常用");
        common.put("count", commonCharacters().size());
        tags.add(common);
        return tags;
    }

    private boolean characterMatchesTag(String ch, String tag) {
        return !"common".equals(tag) || commonCharacters().contains(ch);
    }

    /** 小学常见字（标签「常用」）；不在字库中的会自动跳过。 */
    private List<String> commonCharacters() {
        String text = "一二三四五六七八九十百千万天地人你我他上下左右大小多少男女老少父母子女"
                + "同学老师学校学习读写听说看想走来去出入开关早晚春秋夏冬风雨花草树木山水"
                + "日月星云金木水火土手足口耳目心力气声音语言文字诗歌故事图画"
                + "红黄蓝绿黑白长短高低快慢好坏新旧对错真假"
                + "吃喝玩乐坐立睡醒笑哭买卖远近前后东西南北中";
        List<String> list = new ArrayList<>();
        text.codePoints().forEach(cp -> list.add(new String(Character.toChars(cp))));
        return list;
    }

    private List<JsonNode> loadFeaturedPoetry() throws IOException {
        List<JsonNode> cached = featuredPoetryCache;
        if (cached != null) return cached;
        synchronized (this) {
            if (featuredPoetryCache != null) return featuredPoetryCache;
            List<JsonNode> list = new ArrayList<>();
            Path local = root.resolve("poetry-featured.jsonl");
            if (Files.isRegularFile(local)) {
                try (BufferedReader reader = Files.newBufferedReader(local, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty()) continue;
                        try { list.add(mapper.readTree(line)); } catch (Exception ignored) { /* skip */ }
                    }
                }
            }
            if (list.isEmpty()) {
                try (InputStream in = LibraryService.class.getResourceAsStream("/library/poetry-featured.jsonl")) {
                    if (in != null) {
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                line = line.trim();
                                if (line.isEmpty()) continue;
                                try { list.add(mapper.readTree(line)); } catch (Exception ignored) { /* skip */ }
                            }
                        }
                    }
                }
            }
            featuredPoetryCache = list;
            return featuredPoetryCache;
        }
    }

    private List<JsonNode> filterPoetryByAuthor(List<JsonNode> source, String author) {
        String key = author.toLowerCase(Locale.ROOT);
        List<JsonNode> rows = new ArrayList<>();
        for (JsonNode node : source) {
            if (node.path("author").asText("").toLowerCase(Locale.ROOT).contains(key)) rows.add(node);
        }
        return rows;
    }

    private List<Map<String, Object>> poetryAuthorTags(List<JsonNode> source) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (JsonNode node : source) {
            String author = node.path("author").asText("").trim();
            if (author.isEmpty()) continue;
            counts.merge(author, 1, Integer::sum);
        }
        List<Map<String, Object>> tags = new ArrayList<>();
        counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(12)
                .forEach(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", e.getKey());
                    row.put("name", e.getKey());
                    row.put("count", e.getValue());
                    tags.add(row);
                });
        return tags;
    }

    private Map<String, Object> lightPoetry(JsonNode node) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("title", node.path("title").asText());
        row.put("author", node.path("author").asText(""));
        List<String> paragraphs = new ArrayList<>();
        if (node.path("paragraphs").isArray()) {
            node.path("paragraphs").forEach(p -> paragraphs.add(p.asText()));
        }
        row.put("paragraphs", paragraphs);
        return row;
    }

    @SuppressWarnings("unchecked")
    private List<JsonNode> castItems(Map<String, Object> page) {
        Object items = page.get("items");
        if (!(items instanceof List)) return Collections.emptyList();
        List<Map<String, Object>> rows = (List<Map<String, Object>>) items;
        List<JsonNode> nodes = new ArrayList<>();
        for (Map<String, Object> row : rows) nodes.add(mapper.valueToTree(row));
        return nodes;
    }

    private Map<String, Object> emptyCard(String word) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("word", word);
        card.put("imagePath", null);
        card.put("audioPath", null);
        card.put("tags", Collections.emptyList());
        return card;
    }

    private String relativizeResource(Path path) {
        return resourceRoot.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private String stem(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    private boolean isUiAsset(String stem) {
        String value = stem.toLowerCase(Locale.ROOT);
        return "star".equals(value) || "repeat".equals(value) || "success".equals(value)
                || "error".equals(value) || "screenshot".equals(value) || "educational".equals(value);
    }

    private String normalizePrefix(String prefix) {
        String value = prefix == null ? "" : prefix.trim().replace('\\', '/');
        while (value.startsWith("/")) value = value.substring(1);
        if (value.isEmpty()) return "";
        return value.endsWith("/") ? value : value + "/";
    }

    private long countFiles(Path dir, String suffix) throws IOException {
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> paths = Files.list(dir)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(suffix))
                    .count();
        }
    }
}
