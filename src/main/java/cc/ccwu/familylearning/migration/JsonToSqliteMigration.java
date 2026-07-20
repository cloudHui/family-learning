package cc.ccwu.familylearning.migration;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Standalone, non-Spring migration command for the legacy business JSON. */
public final class JsonToSqliteMigration {
    private JsonToSqliteMigration() {}

    public static void main(String[] args) throws Exception {
        Path dataDir = argument(args, "data-dir", Paths.get("./data"));
        Path database = argument(args, "database", dataDir.resolve("family-learning.sqlite"));
        List<Document> documents = collect(dataDir);
        if (documents.isEmpty()) {
            System.out.println("没有发现需要迁移的 JSON 数据: " + dataDir.toAbsolutePath());
            return;
        }
        migrate(database, documents);
        System.out.println("迁移完成: " + documents.size() + " 个 JSON 文档 -> " + database.toAbsolutePath());
    }

    private static List<Document> collect(Path dataDir) throws IOException {
        if (!Files.isDirectory(dataDir)) return new ArrayList<>();
        List<String> folders = Arrays.asList("students", "records", "mistakes", "usage", "content", "reports");
        List<Document> result = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();
        for (String folder : folders) {
            Path directory = dataDir.resolve(folder);
            if (!Files.isDirectory(directory)) continue;
            try (Stream<Path> files = Files.list(directory)) {
                for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json")).sorted().collect(Collectors.toList())) {
                    String name = file.getFileName().toString();
                    String key = name.substring(0, name.length() - 5);
                    String payload = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                    mapper.readTree(payload); // validate before opening the destination transaction
                    result.add(new Document(folder, key, payload));
                }
            }
        }
        return result;
    }

    private static void migrate(Path database, List<Document> documents) throws Exception {
        Path parent = database.toAbsolutePath().normalize().getParent();
        if (parent != null) Files.createDirectories(parent);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            connection.setAutoCommit(false);
            try (Statement schema = connection.createStatement()) {
                schema.execute("CREATE TABLE IF NOT EXISTS documents (folder TEXT NOT NULL, item_key TEXT NOT NULL, payload TEXT NOT NULL, updated_at TEXT NOT NULL, PRIMARY KEY(folder, item_key))");
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO documents(folder,item_key,payload,updated_at) VALUES(?,?,?,?) " +
                            "ON CONFLICT(folder,item_key) DO UPDATE SET payload=excluded.payload,updated_at=excluded.updated_at")) {
                for (Document document : documents) {
                    insert.setString(1, document.folder); insert.setString(2, document.key);
                    insert.setString(3, document.payload); insert.setString(4, Instant.now().toString()); insert.addBatch();
                }
                insert.executeBatch();
            }
            connection.commit();
        } catch (Exception exception) {
            throw new IllegalStateException("迁移失败，原 JSON 未被修改: " + exception.getMessage(), exception);
        }
    }

    private static Path argument(String[] args, String name, Path fallback) {
        String prefix = "--" + name + "=";
        for (String arg : args) if (arg.startsWith(prefix)) return Paths.get(arg.substring(prefix.length()));
        return fallback;
    }

    private static final class Document {
        final String folder; final String key; final String payload;
        Document(String folder, String key, String payload) { this.folder = folder; this.key = key; this.payload = payload; }
    }
}
