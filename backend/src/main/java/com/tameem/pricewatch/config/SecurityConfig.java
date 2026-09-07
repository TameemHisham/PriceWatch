package com.tameem.pricewatch.config;


import com.tameem.pricewatch.security.JwtAuthFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
@Configuration
public class SecurityConfig {
    private final JwtAuthFilter jwtAuthFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
    @Bean // prevents default behaviour basically overwrites it
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable()) // stops a security layer that prevents CSRF since i not using session cookie anyways
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
    // this basically says  don't create a session, don't expect one, JWT is stateless
                .authorizeHttpRequests(auth -> auth
                        // The container forwards to /error as a separate ERROR dispatch, and
                        // OncePerRequestFilter skips that dispatch by default — so jwtAuthFilter
                        // never runs on it and, being stateless, there is no SecurityContext left.
                        // Without this the chain denied the forward and every failure came back as
                        // a bare 403, discarding the real status and body. Internal forward only:
                        // it exposes no endpoint.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/api/auth/**").permitAll() // anything under /api/auth/ is permitted to all , no token required
                        .anyRequest().authenticated()) // everything else requires authentication or basically a token
                        .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class); // Spring Security runs a whole chain of filters for every request, this line inserts the custom jwtAuthFilter into the chain
        return http.build(); //finalizes all the configuration into the actual SecurityFilterChain object
    }
}