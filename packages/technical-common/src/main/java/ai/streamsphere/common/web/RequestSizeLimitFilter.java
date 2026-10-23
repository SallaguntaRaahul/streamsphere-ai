package ai.streamsphere.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Rejects bodies above a bound (APIs carry JSON only — media never flows through services). */
public class RequestSizeLimitFilter extends OncePerRequestFilter {
    private final long maxBytes;

    public RequestSizeLimitFilter(long maxBytes) { this.maxBytes = maxBytes; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        long len = req.getContentLengthLong();
        boolean chunked = "chunked".equalsIgnoreCase(req.getHeader("Transfer-Encoding"));
        if (len > maxBytes || (chunked && len < 0 && hasBody(req))) {
            res.setStatus(413);
            res.setContentType("application/problem+json");
            res.getWriter().write("{\"type\":\"https://streamsphere.dev/problems/payload-too-large\",\"title\":\"Payload Too Large\",\"status\":413,\"detail\":\"Request body exceeds " + maxBytes + " bytes\"}");
            return;
        }
        chain.doFilter(req, res);
    }

    private static boolean hasBody(HttpServletRequest req) {
        String m = req.getMethod();
        return "POST".equals(m) || "PUT".equals(m) || "PATCH".equals(m);
    }
}
