package cc.ccwu.familylearning.service;

import cc.ccwu.familylearning.model.Invite;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class InviteService {
    private final JsonFileStore store;

    public InviteService(JsonFileStore store) {
        this.store = store;
    }

    public synchronized Invite create(String createdBy, String note, int maxUses, int validDays) throws Exception {
        Invite invite = new Invite();
        invite.id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        invite.token = UUID.randomUUID().toString().replace("-", "");
        invite.note = note == null ? "" : note.trim();
        invite.createdBy = createdBy;
        invite.createdAt = LocalDateTime.now();
        invite.expiresAt = LocalDateTime.now().plusDays(Math.max(1, Math.min(validDays, 365)));
        invite.maxUses = Math.max(1, Math.min(maxUses, 100));
        invite.usedCount = 0;
        invite.enabled = true;
        store.write(store.path("invites", invite.id), invite);
        return invite;
    }

    public synchronized List<Invite> list() throws Exception {
        List<Invite> result = new ArrayList<>(store.readFolder("invites", Invite.class));
        result.sort(Comparator.comparing((Invite item) -> item.createdAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return result;
    }

    public synchronized void revoke(String id) throws Exception {
        Invite invite = store.read(store.path("invites", id), Invite.class);
        if (invite == null) throw new IllegalArgumentException("找不到邀请");
        invite.enabled = false;
        store.write(store.path("invites", id), invite);
    }

    public synchronized Invite consume(String token) throws Exception {
        Invite invite = findValid(token);
        invite.usedCount++;
        if (invite.usedCount >= invite.maxUses) invite.enabled = false;
        store.write(store.path("invites", invite.id), invite);
        return invite;
    }

    public synchronized Invite peekValid(String token) throws Exception {
        return findValid(token);
    }

    public synchronized List<Invite> active() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        return list().stream().filter(item -> usable(item, now)).collect(Collectors.toList());
    }

    private Invite findValid(String token) throws Exception {
        if (token == null || token.trim().isEmpty()) throw new IllegalArgumentException("请提供邀请码");
        String normalized = token.trim();
        LocalDateTime now = LocalDateTime.now();
        for (Invite invite : list()) {
            if (normalized.equals(invite.token) && usable(invite, now)) return invite;
        }
        throw new IllegalArgumentException("邀请码无效或已过期");
    }

    private boolean usable(Invite invite, LocalDateTime now) {
        return invite != null && invite.enabled && invite.token != null
                && invite.usedCount < invite.maxUses
                && invite.expiresAt != null && invite.expiresAt.isAfter(now);
    }
}
