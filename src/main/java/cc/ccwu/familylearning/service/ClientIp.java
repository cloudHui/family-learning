package cc.ccwu.familylearning.service;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;

/** 从当前请求读取客户端 IP（兼容 Nginx X-Forwarded-For / X-Real-IP）。 */
public final class ClientIp {
    private ClientIp() {}

    public static String current() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return "-";
            return from(attrs.getRequest());
        } catch (Exception ignored) {
            return "-";
        }
    }

    public static String from(HttpServletRequest request) {
        if (request == null) return "-";
        String forwarded = header(request, "X-Forwarded-For");
        if (forwarded != null) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty()) return first;
        }
        String realIp = header(request, "X-Real-IP");
        if (realIp != null && !realIp.isEmpty()) return realIp;
        String remote = request.getRemoteAddr();
        return remote == null || remote.isEmpty() ? "-" : remote;
    }

    private static String header(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return value == null ? null : value.trim();
    }
}
