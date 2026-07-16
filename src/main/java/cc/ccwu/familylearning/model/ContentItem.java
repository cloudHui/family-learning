package cc.ccwu.familylearning.model;

import java.time.LocalDateTime;

public class ContentItem {
    public String id;
    public String subject;
    public String stage;
    public String type;
    public String title;
    public String body;
    public String answer;
    public boolean enabled = true;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
