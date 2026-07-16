package cc.ccwu.familylearning.service;

import cc.ccwu.familylearning.model.LearningRecord;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class RecordService {
    private final JsonFileStore store;
    public RecordService(JsonFileStore store) { this.store = store; }

    public synchronized LearningRecord add(LearningRecord record) throws Exception {
        validateId(record.studentId);
        validate(record);
        List<LearningRecord> records = list(record.studentId);
        record.id = UUID.randomUUID().toString();
        record.createdAt = LocalDateTime.now();
        if (record.stage == null) record.stage = "幼小衔接";
        records.add(0, record);
        store.write(store.path("records", record.studentId), records);
        return record;
    }

    public List<LearningRecord> list(String studentId) throws Exception {
        validateId(studentId);
        return store.readList(store.path("records", studentId), new TypeReference<List<LearningRecord>>() {});
    }

    public synchronized LearningRecord update(String studentId, String recordId, LearningRecord changes) throws Exception {
        validate(changes);
        List<LearningRecord> records = list(studentId);
        for (int i = 0; i < records.size(); i++) {
            LearningRecord current = records.get(i);
            if (recordId.equals(current.id)) {
                changes.id = current.id; changes.studentId = studentId;
                if (changes.createdAt == null) changes.createdAt = current.createdAt;
                records.set(i, changes); store.write(store.path("records", studentId), records); return changes;
            }
        }
        throw new IllegalArgumentException("找不到学习记录");
    }

    public synchronized void delete(String studentId, String recordId) throws Exception {
        List<LearningRecord> records = list(studentId);
        if (!records.removeIf(item -> recordId.equals(item.id))) throw new IllegalArgumentException("找不到学习记录");
        store.write(store.path("records", studentId), records);
    }

    public Dashboard dashboard(String studentId) throws Exception {
        List<LearningRecord> records = list(studentId);
        LocalDate today = LocalDate.now();
        Dashboard dashboard = new Dashboard();
        dashboard.totalSessions = records.size();
        for (LearningRecord record : records) {
            dashboard.totalQuestions += record.total;
            dashboard.totalCorrect += record.correct;
            if (record.createdAt != null && record.createdAt.toLocalDate().equals(today)) {
                dashboard.todaySessions++;
                dashboard.todayQuestions += record.total;
                dashboard.todaySeconds += record.durationSeconds;
            }
        }
        dashboard.accuracy = dashboard.totalQuestions == 0 ? 0 : Math.round(dashboard.totalCorrect * 1000.0 / dashboard.totalQuestions) / 10.0;
        return dashboard;
    }

    private void validateId(String id) {
        if (id == null || !id.matches("[a-f0-9]{20}")) throw new IllegalArgumentException("无效的学习档案");
    }

    private void validate(LearningRecord record) {
        if (record.subject == null || record.subject.trim().isEmpty()) throw new IllegalArgumentException("学习科目不能为空");
        if (record.module == null || record.module.trim().isEmpty()) throw new IllegalArgumentException("学习模块不能为空");
        if (record.total < 0 || record.correct < 0 || record.correct > record.total) throw new IllegalArgumentException("完成数和正确数不正确");
        if (record.durationSeconds < 0 || record.durationSeconds > 86400) throw new IllegalArgumentException("学习时长不正确");
    }

    public static class Dashboard {
        public int totalSessions;
        public int todaySessions;
        public int totalQuestions;
        public int totalCorrect;
        public int todayQuestions;
        public long todaySeconds;
        public double accuracy;
        public int pendingMistakes;
    }
}
