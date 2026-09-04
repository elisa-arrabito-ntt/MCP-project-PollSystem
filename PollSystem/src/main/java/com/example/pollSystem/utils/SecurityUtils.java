package com.example.pollSystem.utils;

import com.example.pollSystem.entity.Poll;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public class SecurityUtils {

    /**
     * Restituisce l'Authentication dell'utente corrente.
     */
    private Authentication currentAuthentication() {
        Authentication auth =
                SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !auth.isAuthenticated()) {
            throw new IllegalStateException("No authenticated user");
        }

        return auth;
    }

    /**
     * Recupera l'id dell'utente autenticato dal claim custom "user_id" del JWT.
     */
    public Long currentUserId() {
        Authentication auth = currentAuthentication();

        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            Number userId = jwtAuth.getToken().getClaim("user_id");

            if (userId != null) {
                return userId.longValue();
            }
        }

        throw new IllegalStateException(
                "Claim 'user_id' not present in authenticated JWT"
        );
    }

    /**
     * Username dell'utente autenticato.
     */
    public String currentUsername() {
        return currentAuthentication().getName();
    }

    /**
     * Verifica se l'utente autenticato ha il ruolo ADMIN.
     */
    public boolean isAdmin() {
        return currentAuthentication()
                .getAuthorities()
                .stream()
                .anyMatch(authority ->
                        authority.getAuthority().equals("ROLE_ADMIN"));
    }

    /**
     * Verifica se l'utente autenticato è ADMIN oppure
     * proprietario del poll.
     */
    public boolean isAdminOrOwner(Poll poll) {
        if (poll == null || poll.getOwner() == null) {
            return false;
        }

        return isAdmin()
                || poll.getOwner().getId().equals(currentUserId());
    }
}