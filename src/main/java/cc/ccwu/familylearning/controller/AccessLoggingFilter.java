package cc.ccwu.familylearning.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component
public class AccessLoggingFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(AccessLoggingFilter.class);
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.currentTimeMillis();
        try { chain.doFilter(request, response); }
        finally {
            log.info("访问 method={}, path={}, status={}, durationMs={}, ip={}",
                    request.getMethod(), request.getRequestURI(), response.getStatus(),
                    System.currentTimeMillis() - started, cc.ccwu.familylearning.service.ClientIp.from(request));
        }
    }
}
