package cc.ccwu.familylearning.service;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;

/**
 * 读取客户端 IP。
 * 本机 Nginx 会覆盖写入 X-Real-IP=$remote_addr，优先用它，避免客户端伪造的 X-Forwarded-For 抢先。
 */
public final class ClientIp {
    private static final Pattern IPV4 = Pattern.compile(
            "^(?:(?:25[0-5]|2[0-4]\\d|[01]?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|[01]?\\d?\\d)$");

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
        String remote = normalize(request.getRemoteAddr());

        // 仅当直连来自本机反代，或 Spring 已把 remoteAddr 解析成公网 IP 时，才采信转发头
        boolean trustForward = isLoopback(remote) || isPublicIp(remote);
        if (trustForward) {
            String realIp = normalize(header(request, "X-Real-IP"));
            if (isValidIp(realIp)) return realIp;

            String forwarded = header(request, "X-Forwarded-For");
            if (forwarded != null) {
                // proxy_add_x_forwarded_for 把真实对端追加在末尾；取最后一个合法 IP
                String[] parts = forwarded.split(",");
                for (int i = parts.length - 1; i >= 0; i--) {
                    String ip = normalize(parts[i]);
                    if (isValidIp(ip)) return ip;
                }
            }
        }
        return remote == null || remote.isEmpty() ? "-" : remote;
    }

    static String normalize(String value) {
        if (value == null) return null;
        String ip = value.trim();
        if (ip.isEmpty()) return null;
        if (ip.regionMatches(true, 0, "::ffff:", 0, 7)) ip = ip.substring(7);
        return ip;
    }

    static boolean isLoopback(String ip) {
        if (ip == null || ip.isEmpty()) return false;
        return "127.0.0.1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip) || "localhost".equalsIgnoreCase(ip);
    }

    static boolean isPublicIp(String ip) {
        return isValidIp(ip) && !isLoopback(ip);
    }

    static boolean isValidIp(String ip) {
        if (ip == null || ip.isEmpty() || "-".equals(ip)) return false;
        if (IPV4.matcher(ip).matches()) return true;
        // 宽松接受常见 IPv6（含压缩形式）
        return ip.indexOf(':') >= 0 && ip.length() <= 45 && ip.chars().allMatch(c ->
                (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') || c == ':' || c == '.');
    }

    private static String header(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return value == null ? null : value.trim();
    }
}
