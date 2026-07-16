package cc.ccwu.familylearning.service;

import cc.ccwu.familylearning.model.WordItem;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class WordService {
    private final ObjectMapper mapper;
    private final JsonFileStore store;

    public WordService(ObjectMapper mapper, JsonFileStore store) { this.mapper = mapper; this.store = store; }

    @PostConstruct
    public synchronized void init() throws Exception {
        if (!java.nio.file.Files.exists(store.path("content", "words"))) {
            try (InputStream input = getClass().getResourceAsStream("/content/words.json")) {
                if (input == null) throw new IllegalStateException("缺少字词数据");
                store.write(store.path("content", "words"), mapper.readValue(input, new TypeReference<List<WordItem>>() {}));
            }
        }
    }

    public synchronized List<WordItem> list(String stage) throws Exception {
        List<WordItem> words = all();
        if (stage == null || stage.trim().isEmpty()) return words;
        return words.stream().filter(word -> stage.equals(word.stage)).collect(Collectors.toList());
    }

    public synchronized WordItem save(WordItem item) throws Exception {
        List<WordItem> words = all();
        if (item.character == null || item.character.trim().isEmpty()) throw new IllegalArgumentException("汉字不能为空");
        if (item.id == null || item.id.trim().isEmpty()) {
            item.id = "w-" + UUID.randomUUID().toString().substring(0, 8);
            words.add(item);
        } else {
            int index = -1; for (int i = 0; i < words.size(); i++) if (item.id.equals(words.get(i).id)) index = i;
            if (index >= 0) words.set(index, item); else words.add(item);
        }
        if (item.stage == null) item.stage = "幼小衔接";
        store.write(store.path("content", "words"), words); return item;
    }

    public synchronized void delete(String id) throws Exception {
        List<WordItem> words = all();
        if (!words.removeIf(word -> id.equals(word.id))) throw new IllegalArgumentException("找不到汉字");
        store.write(store.path("content", "words"), words);
    }

    private List<WordItem> all() throws Exception {
        return store.readList(store.path("content", "words"), new TypeReference<List<WordItem>>() {});
    }
}
