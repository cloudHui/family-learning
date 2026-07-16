package cc.ccwu.familylearning.model;

import java.time.LocalDateTime;
import java.util.Map;

public class LearningRecord {
    public String id;
    public String studentId;
    public String subject;
    public String module;
    public String stage;
    public int total;
    public int correct;
    public long durationSeconds;
    public Map<String, Object> details;
    public LocalDateTime createdAt;
}
