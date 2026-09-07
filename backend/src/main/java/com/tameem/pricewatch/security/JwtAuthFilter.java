package com.tameem.pricewatch.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }
    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7); // removes the Bearer part
            if (jwtService.isTokenValid(token)) {
                Long userId = jwtService.extractUserId(token);
//           UsernamePasswordAuthenticationToken is basically "authenticated identity"
//                parameters are id, credentials <- not needed since JWT proves identity in this case, and a list of roles
                UsernamePasswordAuthenticationToken authToken =
                        new UsernamePasswordAuthenticationToken(userId, null, java.util.List.of());
//                the context holder basically has localstorage for who is the currently authenticated user for this specific request's thread
                SecurityContextHolder.getContext().setAuthentication(authToken);
            }
        }
//        hands control to the next thing in the chain could be another filter or controller
        filterChain.doFilter(request, response);
    }
}