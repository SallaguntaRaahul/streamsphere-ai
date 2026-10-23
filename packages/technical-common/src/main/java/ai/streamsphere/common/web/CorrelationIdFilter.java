package ai.streamsphere.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/** Accepts a safe inbound X-Correlation-Id (or creates one), exposes it in MDC and echoes it back. */
public class CorrelationIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9-]{8,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String cid = req.getHeader(HEADER);
        if (cid == null || !SAFE.matcher(cid).matches()) cid = UUID.randomUUID().toString();
        MDC.put(MDC_KEY, cid);
        res.setHeader(HEADER, cid);
        final String id = cid;
        // expose the (possibly generated) id as a request header so proxies (gateway) forward the same id
        HttpServletRequest wrapped = new jakarta.servlet.http.HttpServletRequestWrapper(req) {
            @Override public String getHeader(String name) { return HEADER.equalsIgnoreCase(name) ? id : super.getHeader(name); }
            @Override public java.util.Enumeration<String> getHeaders(String name) {
                return HEADER.equalsIgnoreCase(name) ? java.util.Collections.enumeration(java.util.List.of(id)) : super.getHeaders(name);
            }
            @Override public java.util.Enumeration<String> getHeaderNames() {
                var names = new java.util.LinkedHashSet<>(java.util.Collections.list(super.getHeaderNames()));
                names.add(HEADER);
                return java.util.Collections.enumeration(names);
            }
        };
        try {
            chain.doFilter(wrapped, res);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    public static String current() {
        String cid = MDC.get(MDC_KEY);
        return cid != null ? cid : UUID.randomUUID().toString();
    }
}
