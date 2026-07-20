package cc.ccwu.familylearning.model;

import java.time.LocalDateTime;

/** 注册邀请码：分享链接注册时使用。 */
public class Invite {
    public String id;
    public String token;
    public String note;
    public String createdBy;
    public LocalDateTime createdAt;
    public LocalDateTime expiresAt;
    public int maxUses = 1;
    public int usedCount;
    public boolean enabled = true;
}
