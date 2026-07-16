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
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class JsonFileStore {
    private final ObjectMapper mapper;
    private final Path root;

    public JsonFileStore(ObjectMapper mapper, @Value("${family-learning.data-dir}") String dataDir) {
        this.mapper = mapper;
        this.root = Paths.get(dataDir).toAbsolutePath().normalize();
    }

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(root.resolve("students"));
        Files.createDirectories(root.resolve("records"));
        Files.createDirectories(root.resolve("mistakes"));
        Files.createDirectories(root.resolve("usage"));
        Files.createDirectories(root.resolve("content"));
        Files.createDirectories(root.resolve("reports"));
    }

    public Path path(String folder, String safeName) {
        if (!safeName.matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException("非法文件标识");
        return root.resolve(folder).resolve(safeName + ".json");
    }

    public synchronized <T> T read(Path path, Class<T> type) throws IOException {
        if (!Files.exists(path)) return null;
        return mapper.readValue(path.toFile(), type);
    }

    public synchronized <T> List<T> readList(Path path, TypeReference<List<T>> type) throws IOException {
        if (!Files.exists(path)) return new ArrayList<>();
        return mapper.readValue(path.toFile(), type);
    }

    public synchronized void write(Path path, Object value) throws IOException {
        Files.createDirectories(path.getParent());
        Path temp = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".tmp");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), value);
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public synchronized <T> List<T> readFolder(String folder, Class<T> type) throws IOException {
        Path dir = root.resolve(folder);
        if (!Files.isDirectory(dir)) return new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> paths = files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted().collect(Collectors.toList());
            List<T> result = new ArrayList<>();
            for (Path path : paths) result.add(mapper.readValue(path.toFile(), type));
            return result;
        }
    }

    public synchronized void delete(Path path) throws IOException {
        Files.deleteIfExists(path);
    }

    public Path root() { return root; }
}
