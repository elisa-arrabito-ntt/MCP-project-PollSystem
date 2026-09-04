package com.example.pollSystem.exception.config;

import org.springaicommunity.mcp.security.server.config.McpServerOAuth2Configurer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity // Questa annotazione è fondamentale: sposta il controllo sui metodi con @PreAuthorize
public class SecurityConfig {

    @Value("${authorization.server.url}")
    private String authorizationServerUrl;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/rest/api/v0/registration", "/rest/api/v0/login").permitAll()
                        // Pagina statica di conferma dell'URL-mode elicitation: deve essere raggiungibile
                        // senza autenticazione (il browser vi naviga direttamente); è la pagina stessa che,
                        // via JS, ottiene un token con scope "rest" per chiamare l'endpoint REST di conferma.
                        .requestMatchers("/confirm-delete.html").permitAll()
                        // NOTA: l'enforcement esplicito dello scope "mcp" su /mcp/** e' stato
                        // temporaneamente rimosso perche' il client MCP (es. VS Code/GitHub Copilot)
                        // non e' controllabile per garantire che richieda esplicitamente questo scope
                        // nell'authorize request; basta l'autenticazione JWT generica. Resta invece
                        // l'enforcement sull'endpoint REST di conferma, che richiede lo scope "rest"
                        // dedicato, cosi' un token MCP non puo' essere riutilizzato per confermare
                        // l'eliminazione.
                        .requestMatchers("/mcp/**").authenticated()
                        .requestMatchers("/rest/api/v0/delete-poll-requests/**").hasAuthority("SCOPE_rest")
                        .anyRequest().authenticated()
                )

                // Il resource server MCP configura automaticamente gli endpoint e mappa la sicurezza
                .with(McpServerOAuth2Configurer.mcpServerOAuth2(), mcpAuthorization -> {
                    mcpAuthorization.authorizationServer(authorizationServerUrl);
                    mcpAuthorization.validateAudienceClaim(false);
                    // Dichiara esplicitamente lo scope richiesto per il canale MCP nel Protected
                    // Resource Metadata (/.well-known/oauth-protected-resource/mcp), replicando anche
                    // "authorizationServer" e "resourceName" normalmente impostati dal customizer di
                    // default: impostare un customizer qui lo SOSTITUISCE del tutto, quindi vanno
                    // riportati esplicitamente per non perdere il claim "authorization_servers".
                    mcpAuthorization.protectedResourceMetadataCustomizer(builder -> builder
                            .authorizationServer(authorizationServerUrl)
                            .resourceName("Spring MCP Resource Server")
                            .scope("mcp"));
                })

                // Il convertitore estrae i ruoli (es. ROLE_ADMIN) dal JWT
                .oauth2ResourceServer(resourceServer ->
                        resourceServer.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));

        return http.build();
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        // Estrae i ruoli (es. ROLE_ADMIN) dal claim custom "roles" emesso dall'Auth Server.
        JwtGrantedAuthoritiesConverter rolesConverter = new JwtGrantedAuthoritiesConverter();
        rolesConverter.setAuthorityPrefix(""); // Lascia vuoto perché l'Auth Server mette già "ROLE_"
        rolesConverter.setAuthoritiesClaimName("roles");

        // Estrae anche gli scope OAuth2 standard (claim "scope") come authority "SCOPE_xxx",
        // necessari per distinguere il canale MCP (scope "mcp"/poll.*) dall'endpoint REST di
        // conferma della URL-mode elicitation di delete_poll (scope "rest").
        JwtGrantedAuthoritiesConverter scopeConverter = new JwtGrantedAuthoritiesConverter();
        scopeConverter.setAuthorityPrefix("SCOPE_");
        scopeConverter.setAuthoritiesClaimName("scope");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> authorities = new ArrayList<>(rolesConverter.convert(jwt));
            authorities.addAll(scopeConverter.convert(jwt));
            return authorities;
        });
        return converter;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("Content-Type", "Cache-Control", "Connection"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
