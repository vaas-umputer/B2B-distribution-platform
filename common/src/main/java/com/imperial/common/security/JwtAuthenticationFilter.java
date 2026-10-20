package com.imperial.common.security;

import com.imperial.common.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Not a @Component: each service's own SecurityConfig instantiates this and
 * wires it into the Spring Security filter chain explicitly via
 * addFilterBefore(...). That's deliberate - letting Spring Boot
 * auto-register it as a generic servlet Filter (which is what happens to any
 * OncePerRequestFilter bean) would add it to the container's filter chain
 * *outside* Spring Security's own chain, at a position this class has no
 * control over relative to Security's internals.
 *
 * <p>On a valid Bearer token, populates both TenantContext (what
 * TenantRlsAspect reads to scope RLS) and Spring Security's own
 * SecurityContext (what @PreAuthorize / authorizeHttpRequests read). Missing
 * or invalid tokens are left to Spring Security's normal authorization rules
 * to reject - this filter only ever adds trust, never denies a request itself.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (header != null && header.startsWith("Bearer ")) {
            jwtService.parseToken(header.substring(7)).ifPresent(parsed -> {
                TenantContext.set(parsed.tenantId());
                var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + parsed.role()));
                var authentication = new UsernamePasswordAuthenticationToken(
                        parsed.tenantId(), null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            });
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }
}
