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
 * 开放学习库：读取本地 datasets，提供教材/汉字/词典/诗词查询。
 * 诗词优先走轻量索引，避免每次扫整库。
 */
@Service
public class LibraryService {
    private static final int LIMIT = 50;
    private static final String TREE = "https://api.github.com/repos/TapXWorld/ChinaTextbook/git/trees/master?recursive=1";

    private final Path root;
    private final Path resourceRoot;
    private final ObjectMapper mapper;

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
        result.put("englishCards", englishKids("").size());
        return result;
    }

    /** 儿童英语图卡：匹配 img/ 与 audio/ 同名词条。 */
    public List<Map<String, Object>> englishKids(String query) throws IOException {
        Path kids = englishKidsRoot();
        Path images = kids.resolve("img");
        Path audios = kids.resolve("audio");
        if (!Files.isDirectory(images) && !Files.isDirectory(audios)) return Collections.emptyList();
        String key = clean(query).toLowerCase(Locale.ROOT);
        Map<String, Map<String, Object>> cards = new TreeMap<>();
        if (Files.isDirectory(images)) try (Stream<Path> paths = Files.list(images)) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                String stem = stem(path.getFileName().toString());
                if (stem.isEmpty() || isUiAsset(stem)) return;
                if (!key.isEmpty() && !stem.toLowerCase(Locale.ROOT).contains(key)) return;
                Map<String, Object> card = cards.computeIfAbsent(stem, this::emptyCard);
                card.put("imagePath", relativizeResource(path));
            });
        }
        if (Files.isDirectory(audios)) try (Stream<Path> paths = Files.list(audios)) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                String stem = stem(path.getFileName().toString());
                if (stem.isEmpty() || isUiAsset(stem)) return;
                if (!key.isEmpty() && !stem.toLowerCase(Locale.ROOT).contains(key)) return;
                Map<String, Object> card = cards.computeIfAbsent(stem, this::emptyCard);
                card.put("audioPath", relativizeResource(path));
            });
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> card : cards.values()) {
            if (card.get("imagePath") == null && card.get("audioPath") == null) continue;
            result.add(card);
            if (result.size() >= LIMIT) break;
        }
        return result;
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

    /** 按单字读取汉字笔顺 JSON。 */
    public JsonNode character(String value) throws IOException {
        if (value == null || value.codePointCount(0, value.length()) != 1) {
            throw new IllegalArgumentException("请输入一个汉字");
        }
        Path file = root.resolve("characters").resolve(Integer.toHexString(value.codePointAt(0)) + ".json");
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("字库中没有这个汉字");
        return mapper.readTree(file.toFile());
    }

    /** 按词头分片查找英汉词典。 */
    public List<JsonNode> dictionary(String query) throws IOException {
        String word = clean(query).toLowerCase(Locale.ROOT);
        if (word.isEmpty()) return Collections.emptyList();
        String shard = word.substring(0, Math.min(2, word.length())).replaceAll("[^a-z]", "_");
        return searchJsonl(root.resolve("dictionary").resolve(shard + ".jsonl"), word, "word");
    }

    /**
     * 查古诗词。
     * 优先读 poetry-idx 分片（按标题/作者首字），再按偏移取全文；否则回退扫 poetry.jsonl。
     */
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

    private Map<String, Object> emptyCard(String word) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("word", word);
        card.put("imagePath", null);
        card.put("audioPath", null);
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
