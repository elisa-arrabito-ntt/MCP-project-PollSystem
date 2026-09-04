package com.example.PollAuthServer.config;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.UUID;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

import com.example.PollAuthServer.security.PollSystemUserDetails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientCredentialsAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2RefreshTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.web.SecurityFilterChain;
import org.springaicommunity.mcp.security.authorizationserver.config.McpAuthorizationServerConfigurer;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import java.util.ArrayList;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${authserver.client.default-client.client-id}")
    private String clientId;

    @Value("${authserver.client.default-client.client-secret}")
    private String clientSecret;

    @Value("${authserver.client.confirm-delete-client.client-id}")
    private String confirmDeleteClientId;

    @Value("${authserver.client.confirm-delete-client.access-token-ttl-minutes:5}")
    private long confirmDeleteAccessTokenTtlMinutes;


    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // 1. Istanziamo il configuratore MCP standard di Spring AI
        McpAuthorizationServerConfigurer mcpConfigurer = McpAuthorizationServerConfigurer.mcpAuthorizationServer();

        // 2. Applichiamo il configuratore al filtro HTTP di Spring
        http.with(mcpConfigurer, Customizer.withDefaults());

        // ⚠️ CONFIGURAZIONE CORS: Permette all'interfaccia dell'Inspector di dialogare con l'Auth Server
        http.cors(cors -> cors.configurationSource(request -> {
            var config = new org.springframework.web.cors.CorsConfiguration();
            config.setAllowedOrigins(List.of(
                    "http://localhost:3000", "http://localhost:5173", "http://127.0.0.1:3000", "http://127.0.0.1:5173",
                    // Origine della pagina statica di conferma (confirm-delete.html) servita da PollSystem3,
                    // che agisce da client OAuth2 aggiuntivo per ottenere il token con scope "rest".
                    "http://localhost:8080", "http://127.0.0.1:8080"
            ));
            config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
            config.setAllowedHeaders(List.of("*"));
            return config;
        }));

        // 3. Ritorniamo la catena escludendo solo i path statici dal CSRF
        return http
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .csrf(csrf -> csrf.ignoringRequestMatchers(
                        "/oauth2/authorize",
                        "/oauth2/token",
                        "/oauth2/jwks"
                        // NOTA: Abbiamo rimosso /connect/register perché l'endpoint viene spento
                ))
                .formLogin(Customizer.withDefaults())
                .build();
    }

    @Bean
    public RegisteredClientRepository registeredClientRepository() {
        List<RegisteredClient> mutableClients = new ArrayList<>();

        RegisteredClient defaultStaticClient = RegisteredClient.withId("mcp-client")
                .clientId("mcp-client") // Deve coincidere esattamente con l'URL
                //.clientSecret(clientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE) // Obbligatorio per PKCE
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .redirectUris(uris -> {
                    // --- REDIRECT UFFICIALI PER VS CODE / GITHUB COPILOT ---
                    uris.add("http://127.0.0.1:33418/");
                    uris.add("http://localhost:33418/");
                    uris.add("http://127.0.0:0/");
                    uris.add("http://localhost:0/");

                    // --- ⚠️ AGGIUNTA CRUCIALE PER MCP INSPECTOR ---
                    // L'Inspector (CLI o web) usa tipicamente questi path standard per la callback
                    uris.add("http://localhost:6274/oauth/callback");
                    uris.add("http://127.0.0.1:6274/oauth/callback");
                    uris.add("http://localhost:3000/oauth/callback");
                    uris.add("http://127.0.0.1:3000/oauth/callback");
                    uris.add("http://localhost:5173/oauth/callback");
                    uris.add("http://127.0.0.1:5173/oauth/callback");
                })
                .scope("mcp")
                .tokenSettings(TokenSettings.builder()
                    .accessTokenTimeToLive(Duration.ofMinutes(30))
                    .refreshTokenTimeToLive(Duration.ofHours(2))
                    .reuseRefreshTokens(true)
                    .build())
                .clientSettings(ClientSettings.builder()
                        .requireAuthorizationConsent(false) // Impedisce l'errore 999 dopo il login
                        .requireProofKey(true)              // Forza PKCE (S256 nell'URL)
                        .build())
                .build();

        mutableClients.add(defaultStaticClient);

        // Client OAuth2 pubblico usato dalla pagina statica di conferma (confirm-delete.html, servita
        // da PollSystem3) per la URL-mode elicitation di delete_poll. Ha uno scope dedicato ("rest"),
        // distinto da quello del client MCP ("mcp"), e token ad vita molto breve, coerente con il TTL
        // delle richieste di eliminazione pending (property poll.delete-confirmation.ttl-minutes in
        // PollSystem3). Nessun refresh token: il flusso di conferma è singolo e non deve essere rinnovabile.
        RegisteredClient confirmDeleteWebClient = RegisteredClient.withId(confirmDeleteClientId)
                .clientId(confirmDeleteClientId)
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE) // client pubblico, obbligatorio PKCE
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUris(uris -> {
                    uris.add("http://localhost:8080/confirm-delete.html");
                    uris.add("http://127.0.0.1:8080/confirm-delete.html");
                })
                .scope("rest")
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(confirmDeleteAccessTokenTtlMinutes))
                        .build())
                .clientSettings(ClientSettings.builder()
                        .requireAuthorizationConsent(false)
                        .requireProofKey(true)
                        .build())
                .build();

        mutableClients.add(confirmDeleteWebClient);

        return new InMemoryRegisteredClientRepository(mutableClients);
    }


    @Bean
    public JWKSource<SecurityContext> jwkSource() {
        KeyPair keyPair = generateRsaKey();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
        RSAKey rsaKey = new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(UUID.randomUUID().toString())
                .build();
        JWKSet jwkSet = new JWKSet(rsaKey);
        return new ImmutableJWKSet<>(jwkSet);
    }

    private static KeyPair generateRsaKey() {
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048);
            return keyPairGenerator.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // Stesso encoder usato da PollSystem3 in fase di registrazione (PasswordConfig),
        // così le password vengono verificate correttamente qui sul PollAuthServer.
        return new BCryptPasswordEncoder();
    }

    // Personalizza i claims del JWT (access token) per includere ruoli e user_id, mantenendo
    // anche il comportamento di default (claim "aud" dal parametro "resource" della richiesta)
    private OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer() {
        return context -> {
            if (!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
                return;
            }

            // Replica il comportamento di ResourceIdentifierAudienceTokenCustomizer (di default
            // usato dal McpAuthorizationServerConfigurer), qui necessario perché sostituiamo
            // integralmente l'OAuth2TokenGenerator per poter aggiungere i nostri claim custom.
            String resource = null;
            if (AuthorizationGrantType.AUTHORIZATION_CODE.equals(context.getAuthorizationGrantType())) {
                OAuth2Authorization authorization = context.getAuthorization();
                if (authorization != null) {
                    OAuth2AuthorizationRequest authorizationRequest = authorization.getAttribute(
                            OAuth2AuthorizationRequest.class.getName());
                    if (authorizationRequest != null) {
                        Map<String, Object> additionalParameters = authorizationRequest.getAdditionalParameters();
                        resource = (String) additionalParameters.get("resource");
                    }
                }
            } else if (AuthorizationGrantType.CLIENT_CREDENTIALS.equals(context.getAuthorizationGrantType())) {
                if (context.getAuthorizationGrant() instanceof OAuth2ClientCredentialsAuthenticationToken clientCredentials) {
                    resource = (String) clientCredentials.getAdditionalParameters().get("resource");
                }
            }

            // CORREZIONE CRUCIALE QUI:
            if (resource != null) {
                // Utilizziamo .audience() passando una List. Questo imposta il claim "aud"
                // nel formato array standard, permettendo al Resource Server di validarlo.
                context.getClaims().audience(List.of(resource));
            }

            // Claim custom con ruoli e user_id, disponibili solo per login utente (PollSystemUserDetails)
            Authentication principal = context.getPrincipal();
            if (principal.getPrincipal() instanceof PollSystemUserDetails userDetails) {
                List<String> roles = userDetails.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .collect(Collectors.toList());

                context.getClaims()
                        .claim("roles", roles)
                        .claim("user_id", userDetails.getUser().getId());
            }
        };
    }

    // Il McpAuthorizationServerConfigurer cerca prima un bean OAuth2TokenGenerator<?> nel contesto
    // e, se presente, lo usa al posto della propria logica di default (che ignora qualsiasi bean
    // OAuth2TokenCustomizer<JwtEncodingContext>). Per applicare i claim custom è quindi necessario
    // sostituire l'intero generatore di token, non solo il customizer.
    @Bean
    public OAuth2TokenGenerator<?> tokenGenerator(JWKSource<SecurityContext> jwkSource) {
        JwtEncoder jwtEncoder = new NimbusJwtEncoder(jwkSource);
        JwtGenerator jwtGenerator = new JwtGenerator(jwtEncoder);
        jwtGenerator.setJwtCustomizer(tokenCustomizer());

        OAuth2RefreshTokenGenerator refreshTokenGenerator = new OAuth2RefreshTokenGenerator();

        return new DelegatingOAuth2TokenGenerator(jwtGenerator, refreshTokenGenerator);
    }
}