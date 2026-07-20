package cc.ccwu.familylearning.service;

import cc.ccwu.familylearning.model.Mistake;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class MistakeService {
    private final JsonFileStore store;
    public MistakeService(JsonFileStore store) { this.store = store; }

    public synchronized Mistake add(Mistake incoming) throws Exception {
        validateId(incoming.studentId);
        validate(incoming);
        List<Mistake> all = all(incoming.studentId);
        Mistake existing = all.stream().filter(item -> sameQuestion(item, incoming)).findFirst().orElse(null);
        if (existing != null) {
            existing.errorCount++;
            existing.userAnswer = incoming.userAnswer;
            existing.lastWrongAt = LocalDateTime.now();
            existing.consecutiveCorrect = 0;
            existing.status = "待复习";
            store.write(store.path("mistakes", incoming.studentId), all);
            return existing;
        }
        incoming.id = UUID.randomUUID().toString();
        incoming.errorCount = 1;
        incoming.reviewCount = 0;
        incoming.consecutiveCorrect = 0;
        incoming.status = "待复习";
        incoming.firstWrongAt = LocalDateTime.now();
        incoming.lastWrongAt = incoming.firstWrongAt;
        all.add(0, incoming);
        store.write(store.path("mistakes", incoming.studentId), all);
        return incoming;
    }

    public List<Mistake> list(String studentId, String subject, String status) throws Exception {
        return all(studentId).stream()
                .filter(item -> subject == null || subject.isEmpty() || subject.equals(item.subject))
                .filter(item -> status == null || status.isEmpty() || status.equals(item.status))
                .collect(Collectors.toList());
    }

    public synchronized Mistake review(String studentId, String mistakeId, boolean correct) throws Exception {
        List<Mistake> all = all(studentId);
        Mistake target = all.stream().filter(item -> mistakeId.equals(item.id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("找不到这条错题"));
        target.reviewCount++;
        target.lastReviewedAt = LocalDateTime.now();
        if (correct) {
            target.consecutiveCorrect++;
            target.status = target.consecutiveCorrect >= 3 ? "已掌握" : target.consecutiveCorrect >= 2 ? "基本掌握" : "复习中";
        } else {
            target.errorCount++;
            target.consecutiveCorrect = 0;
            target.status = "待复习";
            target.lastWrongAt = LocalDateTime.now();
        }
        store.write(store.path("mistakes", studentId), all);
        return target;
    }

    public int pendingCount(String studentId) throws Exception {
        return (int) all(studentId).stream().filter(item -> !"已掌握".equals(item.status)).count();
    }

    public synchronized Mistake update(String studentId, String mistakeId, Mistake changes) throws Exception {
        validate(changes);
        List<Mistake> all = all(studentId);
        for (int i = 0; i < all.size(); i++) if (mistakeId.equals(all.get(i).id)) {
            Mistake current = all.get(i); changes.id = current.id; changes.studentId = studentId;
            if (changes.firstWrongAt == null) changes.firstWrongAt = current.firstWrongAt;
            if (changes.lastWrongAt == null) changes.lastWrongAt = current.lastWrongAt;
            if (changes.lastReviewedAt == null) changes.lastReviewedAt = current.lastReviewedAt;
            if (changes.errorCount <= 0) changes.errorCount = current.errorCount;
            if (changes.reviewCount <= 0) changes.reviewCount = current.reviewCount;
            if (changes.consecutiveCorrect <= 0) changes.consecutiveCorrect = current.consecutiveCorrect;
            if (changes.status == null || changes.status.trim().isEmpty()) changes.status = current.status;
            all.set(i, changes); store.write(store.path("mistakes", studentId), all); return changes;
        }
        throw new IllegalArgumentException("找不到错题");
    }

    public synchronized void delete(String studentId, String mistakeId) throws Exception {
        List<Mistake> all = all(studentId);
        if (!all.removeIf(item -> mistakeId.equals(item.id))) throw new IllegalArgumentException("找不到错题");
        store.write(store.path("mistakes", studentId), all);
    }

    private List<Mistake> all(String studentId) throws Exception {
        if (!StudentService.isValidArchiveId(studentId)) return new ArrayList<>();
        return store.readList(store.path("mistakes", studentId), new TypeReference<List<Mistake>>() {});
    }

    private boolean sameQuestion(Mistake a, Mistake b) {
        return safe(a.subject).equals(safe(b.subject)) && safe(a.question).equals(safe(b.question));
    }
    private String safe(String value) { return value == null ? "" : value; }
    private void validateId(String id) {
        if (!StudentService.isValidArchiveId(id)) throw new IllegalArgumentException("无效的学习档案");
    }
    private void validate(Mistake mistake) {
        if (mistake.subject == null || mistake.subject.trim().isEmpty()) throw new IllegalArgumentException("错题科目不能为空");
        if (mistake.question == null || mistake.question.trim().isEmpty()) throw new IllegalArgumentException("错题内容不能为空");
        if (mistake.correctAnswer == null) mistake.correctAnswer = "";
    }
}
