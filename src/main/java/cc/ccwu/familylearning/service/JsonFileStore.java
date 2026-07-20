package cc.ccwu.familylearning.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SQLite document store. The historic class name is retained so the business
 * services remain source-compatible; no business JSON is read or written.
 */
@Component
public class JsonFileStore {
    private static final Logger log = LoggerFactory.getLogger(JsonFileStore.class);
    private final ObjectMapper mapper;
    private final Path root;
    private final Path database;

    public JsonFileStore(ObjectMapper mapper, @Value("${family-learning.data-dir}") String dataDir) {
        this.mapper = mapper;
        this.root = Paths.get(dataDir).toAbsolutePath().normalize();
        this.database = root.resolve("family-learning.sqlite");
    }

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(root);
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("CREATE TABLE IF NOT EXISTS documents (folder TEXT NOT NULL, item_key TEXT NOT NULL, payload TEXT NOT NULL, updated_at TEXT NOT NULL, PRIMARY KEY(folder, item_key))");
        } catch (SQLException exception) {
            throw new IOException("无法初始化 SQLite 数据库: " + database, exception);
        }
    }

    /** Compatibility path used as a logical document identifier. */
    public Path path(String folder, String safeName) {
        if (!safeName.matches("[a-zA-Z0-9_.-]+")) throw new IllegalArgumentException("非法文件标识");
        return root.resolve(folder).resolve(safeName + ".json");
    }

    public synchronized <T> T read(Path path, Class<T> type) throws IOException {
        String payload = find(path);
        return payload == null ? null : mapper.readValue(payload, type);
    }

    public synchronized <T> List<T> readList(Path path, TypeReference<List<T>> type) throws IOException {
        String payload = find(path);
        return payload == null ? new ArrayList<>() : mapper.readValue(payload, type);
    }

    public synchronized void write(Path path, Object value) throws IOException {
        String[] key = key(path);
        String payload = mapper.writeValueAsString(value);
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO documents(folder,item_key,payload,updated_at) VALUES(?,?,?,datetime('now')) " +
                        "ON CONFLICT(folder,item_key) DO UPDATE SET payload=excluded.payload,updated_at=excluded.updated_at")) {
            statement.setString(1, key[0]); statement.setString(2, key[1]); statement.setString(3, payload); statement.executeUpdate();
        } catch (SQLException exception) { throw new IOException("SQLite 写入失败: " + path, exception); }
    }

    public synchronized <T> List<T> readFolder(String folder, Class<T> type) throws IOException {
        List<T> result = new ArrayList<>();
        for (Entry<T> entry : readFolderEntries(folder, type)) result.add(entry.value);
        return result;
    }

    public synchronized <T> List<Entry<T>> readFolderEntries(String folder, Class<T> type) throws IOException {
        List<Entry<T>> result = new ArrayList<>();
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT item_key, payload FROM documents WHERE folder=? ORDER BY item_key")) {
            statement.setString(1, folder);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    try {
                        result.add(new Entry<>(rows.getString(1), mapper.readValue(rows.getString(2), type)));
                    } catch (IOException invalidDocument) {
                        log.error("跳过损坏的 SQLite 数据文档，目录={} key={}", folder, rows.getString(1), invalidDocument);
                    }
                }
            }
            return result;
        } catch (SQLException exception) { throw new IOException("SQLite 查询失败: " + folder, exception); }
    }

    public synchronized void delete(Path path) throws IOException {
        String[] key = key(path);
        deleteDocument(key[0], key[1]);
    }

    public synchronized void deleteDocument(String folder, String itemKey) throws IOException {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM documents WHERE folder=? AND item_key=?")) {
            statement.setString(1, folder); statement.setString(2, itemKey); statement.executeUpdate();
        } catch (SQLException exception) { throw new IOException("SQLite 删除失败: " + folder + "/" + itemKey, exception); }
    }

    public synchronized void writeDocument(String folder, String itemKey, Object value) throws IOException {
        String payload = mapper.writeValueAsString(value);
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO documents(folder,item_key,payload,updated_at) VALUES(?,?,?,datetime('now')) " +
                        "ON CONFLICT(folder,item_key) DO UPDATE SET payload=excluded.payload,updated_at=excluded.updated_at")) {
            statement.setString(1, folder); statement.setString(2, itemKey); statement.setString(3, payload); statement.executeUpdate();
        } catch (SQLException exception) { throw new IOException("SQLite 写入失败: " + folder + "/" + itemKey, exception); }
    }

    public synchronized String findDocument(String folder, String itemKey) throws IOException {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT payload FROM documents WHERE folder=? AND item_key=?")) {
            statement.setString(1, folder); statement.setString(2, itemKey);
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? rows.getString(1) : null; }
        } catch (SQLException exception) { throw new IOException("SQLite 查询失败: " + folder + "/" + itemKey, exception); }
    }

    public synchronized void moveDocument(String folder, String fromKey, String toKey) throws IOException {
        if (fromKey == null || toKey == null || fromKey.equals(toKey)) return;
        String payload = findDocument(folder, fromKey);
        if (payload == null) return;
        String existing = findDocument(folder, toKey);
        if (existing == null) {
            try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                    "UPDATE documents SET item_key=? WHERE folder=? AND item_key=?")) {
                statement.setString(1, toKey); statement.setString(2, folder); statement.setString(3, fromKey); statement.executeUpdate();
            } catch (SQLException exception) { throw new IOException("SQLite 重命名失败: " + folder + "/" + fromKey, exception); }
            return;
        }
        // Target exists: keep target, drop the source key after optional merge by caller.
        deleteDocument(folder, fromKey);
    }

    public synchronized boolean exists(Path path) throws IOException { return find(path) != null; }
    public Path root() { return root; }
    public Path database() { return database; }

    public static final class Entry<T> {
        public final String key;
        public final T value;
        public Entry(String key, T value) { this.key = key; this.value = value; }
    }

    private String find(Path path) throws IOException {
        String[] key = key(path);
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT payload FROM documents WHERE folder=? AND item_key=?")) {
            statement.setString(1, key[0]); statement.setString(2, key[1]);
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? rows.getString(1) : null; }
        } catch (SQLException exception) { throw new IOException("SQLite 查询失败: " + path, exception); }
    }

    private String[] key(Path path) {
        Path relative = root.relativize(path.toAbsolutePath().normalize());
        Path file = relative.getFileName();
        if (file == null || relative.getNameCount() != 2) throw new IllegalArgumentException("非法数据路径");
        String name = file.toString();
        if (!name.endsWith(".json")) throw new IllegalArgumentException("非法数据路径");
        return new String[]{relative.getName(0).toString(), name.substring(0, name.length() - 5)};
    }

    private Connection connection() throws SQLException { return DriverManager.getConnection("jdbc:sqlite:" + database); }
}
