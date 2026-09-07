package com.tameem.pricewatch.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

@Component
public class JwtService {

    @Value("${pricewatch.jwt.secret}")
    private String secret;

    @Value("${pricewatch.jwt.expiration}")
    private long expirationMs;

//    Converts the plain text into secret key
    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes());
    }
    public String generateToken(Long userId, String email) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(String.valueOf(userId)) // sets the "who is this token for?"
                .claim("email", email) // puts the email in the payload (for convenience, no separate call for email)
                .issuedAt(now) // when it was made
                .expiration(expiry) // when it will expire
                .signWith(getSigningKey()) // basically this does the encryption
                .compact(); // serializes the whole thing into a format to be stored
    }
    public Long extractUserId(String token) {
        Claims claims = parseClaims(token);
        return Long.valueOf(claims.getSubject());
    }
    public boolean isTokenValid(String token) {
        try {
            parseClaims(token); // if parse wasn't successful then fake signature
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    private Claims parseClaims(String token) {
        return Jwts.parser() // creates a parser
                .verifyWith(getSigningKey()) // this tell it which key to check the signature against
                .build() //
                .parseSignedClaims(token) // decode the token, verify the signature matches, check it hasn't expired and throws if error
                .getPayload(); // pulls out just the claims data
    }

}