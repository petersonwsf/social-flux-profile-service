package com.socialflux.profile.support;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.socialflux.events.OutboxRelay;
import com.socialflux.media.storage.MediaStorage;
import com.socialflux.profile.events.AccountEventHandler;
import com.sun.net.httpserver.HttpServer;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Base dos testes de integração: sobe o serviço completo em porta aleatória contra um PostgreSQL REAL embutido (sem Docker)
 * e um servidor JWKS local com uma chave RSA de teste — o serviço valida os tokens exatamente como em produção
 * (assinatura RS256 via JWKS, issuer, audiência, expiração).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "socialflux.events.enabled=false",
                "socialflux.media.storage=memory",
                "profile.security.issuer=http://auth.test",
                "spring.flyway.enabled=true"
        })
@Import(TestSupportConfig.class)
public abstract class AbstractProfileTest {

    protected static final String ISSUER = "http://auth.test";
    protected static final String USER_AUDIENCE = "socialflux-api";
    protected static final String SERVICE_AUDIENCE = "profile-service";

    protected static final EmbeddedPostgres POSTGRES;
    protected static final RSAKey KEY;
    private static final HttpServer JWKS;

    static {
        try {
            POSTGRES = EmbeddedPostgres.builder().start();
            KEY = new RSAKeyGenerator(2048).keyID("test-key").generate();
            JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            JWKS.createContext("/jwks.json", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            JWKS.start();
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao preparar a infraestrutura de teste", e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("profile.security.jwks-uri", () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks.json");
    }

    @Value("${local.server.port}")
    protected int port;

    @Autowired
    protected JdbcClient jdbc;

    /** Armazenamento de mídia em memória ({@code socialflux.media.storage=memory}): inspecionável nos testes. */
    @Autowired
    protected MediaStorage mediaStorage;

    @Autowired
    protected AccountEventHandler accountEvents;

    @Autowired
    protected OutboxRelay relay;

    @Autowired
    protected TestSupportConfig.RecordingPublisher publisher;

    private Http http;

    protected Http http() {
        if (http == null) {
            http = new Http(port);
        }
        return http;
    }

    // ------------------------------------------------------------------ tokens

    protected static String userToken(UUID accountId) {
        return sign(new JWTClaimsSet.Builder()
                .issuer(ISSUER).audience(USER_AUDIENCE).subject(accountId.toString())
                .issueTime(new Date()).expirationTime(new Date(System.currentTimeMillis() + 3_600_000))
                .claim("roles", List.of("USER")).claim("ver", 1).build());
    }

    protected static String serviceToken(String scope) {
        return sign(new JWTClaimsSet.Builder()
                .issuer(ISSUER).audience(SERVICE_AUDIENCE).subject("svc:authentication-service")
                .issueTime(new Date()).expirationTime(new Date(System.currentTimeMillis() + 300_000))
                .claim("scope", scope).build());
    }

    protected static String sign(JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(KEY));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ cenários

    /** Uma conta de teste: perfil criado pelo "auth" (PUT interno) e, se pedido, confirmado pelo evento account.registered. */
    protected record Account(UUID id, String username, String token) {
    }

    protected static String uniqueUsername() {
        return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    protected static String createBody(String username) {
        return """
                {"username":"%s","nome":"Pessoa Teste","telefone":"11 99999-8888","sexo":"F","data_nascimento":"1990-05-20"}
                """.formatted(username);
    }

    protected Account createPending(String username) {
        UUID id = UUID.randomUUID();
        Http.Response response = http().send("PUT", "/internal/profiles/" + id, createBody(username),
                serviceToken("profiles:write profiles:read"));
        if (response.status() != 201) {
            throw new IllegalStateException("Criação falhou: " + response.status() + " " + response.body());
        }
        return new Account(id, username, userToken(id));
    }

    protected Account createActive(String username) {
        Account account = createPending(username);
        publishAccountEvent("account.registered.v1", account.id());
        return account;
    }

    protected void publishAccountEvent(String type, UUID accountId) {
        accountEvents.handle("""
                {"id":"%s","type":"%s","occurredAt":"2026-01-01T00:00:00Z","aggregateId":"%s","aggregateVersion":1,"data":{}}
                """.formatted(UUID.randomUUID(), type, accountId));
    }

    protected List<String> outboxTypes(UUID aggregateId) {
        return jdbc.sql("select type from outbox_event where aggregate_id = :id order by seq")
                .param("id", aggregateId).query(String.class).list();
    }

    /** Publica TODOS os eventos pendentes (o relay envia em lotes). */
    protected void drainOutbox() {
        for (int i = 0; i < 100 && relay.publishPending() > 0; i++) {
            // continua até esvaziar
        }
    }

    protected static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unused")
    private static void ignore() throws IOException {
        // mantém o import de IOException estável para subclasses que lançam
    }
}
