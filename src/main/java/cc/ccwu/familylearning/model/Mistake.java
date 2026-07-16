package cc.ccwu.familylearning.model;

import java.time.LocalDateTime;

public class Mistake {
    public String id;
    public String studentId;
    public String subject;
    public String module;
    public String stage;
    public String question;
    public String userAnswer;
    public String correctAnswer;
    public String errorType;
    public int errorCount;
    public int reviewCount;
    public int consecutiveCorrect;
    public String status;
    public LocalDateTime firstWrongAt;
    public LocalDateTime lastWrongAt;
    public LocalDateTime lastReviewedAt;
}
