package com.example.pollSystem.service;

import com.example.pollSystem.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.function.Function;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    @Value("${security.jwt.secret}")
    private String jwtSecret;

    @Value("${security.jwt.expiration-minutes}")
    private long jwtExpirationMinutes;

    // per login response
    public String generateToken(User user) {
        Date issuedAt = new Date();
        Date expiration = new Date(issuedAt.getTime() + jwtExpirationMinutes * 60 * 1000);

        return Jwts.builder()
                .subject(user.getUsername())
                .issuedAt(issuedAt)
                .expiration(expiration)
                .signWith(getSigningKey(), Jwts.SIG.HS256) // firma con la chiave segreta
                .compact();
    }

    public LocalDateTime getExpirationDateTime() {
        return LocalDateTime.now().plusMinutes(jwtExpirationMinutes);
    }

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    // Estrae lo username (subject) dal token
    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public boolean isTokenValid(String token) {
        try {
            extractAllClaims(token); // Controlla firma e scadenza
            return true;
        } catch (JwtException e) {
            // Il token è manomesso o scaduto
            return false;
        }
    }

    // Controlla se il token è scaduto
    private boolean isTokenExpired(String token) {
        Date expiration = extractClaim(token, Claims::getExpiration);
        return expiration.before(new Date());
    }

    // Metodo generico per estrarre un claim qualunque
    private <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    // Legge tutti i claims dal token usando la stessa chiave di firma
    // i claims contengono subject, issuedAt, expiration, ecc.
    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey()) // verifico la firma con la chiave segreta
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    // Per avere anche la data di scadenza dal token stesso
    public LocalDateTime extractExpirationDateTime(String token) {
        Date expirationDate = extractClaim(token, Claims::getExpiration);
        return LocalDateTime.ofInstant(expirationDate.toInstant(), ZoneId.systemDefault());
    }
}