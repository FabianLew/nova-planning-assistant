package pl.futureirl.nova.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects state-changing requests whose Origin header is neither this server nor an allowed origin.
 * Works alongside CSRF tokens (spec p.8: origin control on every change).
 */
public class OriginCheckFilter extends OncePerRequestFilter {
    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private final List<String> allowed;

    public OriginCheckFilter(List<String> allowed) {
        this.allowed = allowed.stream().map(OriginCheckFilter::normalize).toList();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        var origin = req.getHeader("Origin");
        if (SAFE.contains(req.getMethod()) || origin == null || isAllowed(origin, req)) {
            chain.doFilter(req, res);
            return;
        }
        res.setStatus(HttpServletResponse.SC_FORBIDDEN);
        res.setContentType("application/json");
        res.getWriter().write("{\"code\":\"bad_origin\",\"message\":\"Origin not allowed\"}");
    }

    private boolean isAllowed(String origin, HttpServletRequest req) {
        var o = normalize(origin);
        var port = req.getServerPort();
        var defaultPort = ("https".equals(req.getScheme()) && port == 443) || ("http".equals(req.getScheme()) && port == 80);
        var self = req.getScheme() + "://" + req.getServerName() + (defaultPort ? "" : ":" + port);
        return o.equals(normalize(self)) || allowed.contains(o);
    }

    private static String normalize(String origin) {
        try {
            var u = URI.create(origin.trim());
            var port = u.getPort();
            var scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
            var host = u.getHost() == null ? "" : u.getHost().toLowerCase();
            var defaultPort = port == -1 || ("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80);
            return scheme + "://" + host + (defaultPort ? "" : ":" + port);
        } catch (IllegalArgumentException e) {
            return "invalid";
        }
    }
}
