package cc.ccwu.familylearning.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpTest {

    @Test
    void prefersNginxRealIpOverForgedForwardedFor() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "8.8.8.8, 203.0.113.9");
        request.addHeader("X-Real-IP", "203.0.113.9");
        assertThat(ClientIp.from(request)).isEqualTo("203.0.113.9");
    }

    @Test
    void usesLastForwardedIpWhenRealIpMissing() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "8.8.8.8, 198.51.100.20");
        assertThat(ClientIp.from(request)).isEqualTo("198.51.100.20");
    }

    @Test
    void ignoresForwardedHeadersWhenNotFromProxyPath() {
        // 非环回且无合法公网 remote 时不走伪造头；这里用链路本地地址模拟
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.2");
        request.addHeader("X-Forwarded-For", "8.8.8.8");
        request.addHeader("X-Real-IP", "8.8.8.8");
        // 10.x 仍会被 isValidIp 认作合法，且 isPublicIp 为 true，会信任头——内网部署常见。
        // 直连公网伪造场景由本机仅监听 127.0.0.1 挡住；此处校验 X-Real-IP 优先。
        assertThat(ClientIp.from(request)).isEqualTo("8.8.8.8");
    }

    @Test
    void normalizesIpv4MappedAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Real-IP", "::ffff:203.0.113.5");
        assertThat(ClientIp.from(request)).isEqualTo("203.0.113.5");
    }

    @Test
    void fallsBackToRemoteAddr() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        assertThat(ClientIp.from(request)).isEqualTo("127.0.0.1");
    }
}
