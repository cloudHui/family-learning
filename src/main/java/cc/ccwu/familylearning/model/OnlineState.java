package cc.ccwu.familylearning.model;

import java.time.LocalDateTime;

public class OnlineState {
    public String userId;
    public String username;
    public String name;
    public String page;
    public String feature;
    public String device;
    public LocalDateTime loginAt;
    public LocalDateTime lastSeenAt;

    public OnlineState() {}
}
