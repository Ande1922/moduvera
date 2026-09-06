package io.github.ande1922.moduvera.verification.logging;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.Initiator;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.logging.LoggingContextSnapshot;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

/** Real runtime fixture for the governed logging starter and external Agent. */
@SpringBootConfiguration
public class LoggingFixtureApplication {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoggingFixtureApplication.class);

    public static void main(String[] arguments) throws Exception {
        SpringApplication application = new SpringApplication(LoggingFixtureApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        ConfigurableApplicationContext applicationContext = application.run(arguments);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/info", exchange -> handle(exchange, false));
        server.createContext("/error", exchange -> handle(exchange, true));
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(0);
            applicationContext.close();
        }));
        Files.writeString(
                requiredPath("FIXTURE_PORT_FILE"),
                server.getAddress().getPort() + System.lineSeparator(),
                StandardCharsets.UTF_8);
        LOGGER.info("governed logging fixture ready");
        new CountDownLatch(1).await();
    }

    private static void handle(HttpExchange exchange, boolean fail) throws IOException {
        String secret = requiredEnvironment("FIXTURE_SECRET");
        String query = requiredEnvironment("FIXTURE_QUERY_SENTINEL");
        String sql = requiredEnvironment("FIXTURE_SQL_SENTINEL");
        String apiKey = requiredEnvironment("FIXTURE_API_KEY");
        String xApiKey = requiredEnvironment("FIXTURE_X_API_KEY");
        String dottedApiKey = requiredEnvironment("FIXTURE_DOTTED_API_KEY");
        String camelAccessToken = requiredEnvironment("FIXTURE_CAMEL_ACCESS_TOKEN");
        String camelClientSecret = requiredEnvironment("FIXTURE_CAMEL_CLIENT_SECRET");
        String basicCredential = requiredEnvironment("FIXTURE_BASIC_CREDENTIAL");
        Actor actor = new Actor(ActorType.USER, "fixture-user", Set.of("fixture:read"));
        ExecutionContext executionContext = new ExecutionContext(
                new TenantId("fixture-tenant"),
                actor,
                Initiator.from(actor),
                "fixture-correlation");

        try (var ignoredExecution = ExecutionContextHolder.open(executionContext);
                var ignoredSnapshot = LoggingContextSnapshot.capture().openScope();
                var ignoredTenant = MDC.putCloseable("tenant_id", "forged-tenant");
                var ignoredAuthorization = MDC.putCloseable("authorization", secret)) {
            if (fail) {
                HttpTimeoutException transport =
                        new HttpTimeoutException("https://example.test/orders?secret=" + query);
                SQLException failure = new SQLException(
                        "SELECT secret FROM credentials WHERE value='" + sql + "'", transport);
                LOGGER.atError()
                        .addKeyValue("error.code", "DEP_DATABASE_UNAVAILABLE")
                        .addKeyValue("db.query.text", sql)
                        .addKeyValue("http.request.body.content", secret)
                        .addKeyValue("url.query", query)
                        .addKeyValue("url.full", "https://example.test/orders?arbitrary=" + query)
                        .addKeyValue("order_id", "fixture-order-error")
                        .addKeyValue("trace_id", "forged-trace")
                        .setCause(failure)
                        .log(
                                "fixture final failure, credential={}, Authorization: Basic {}",
                                secret,
                                basicCredential);
            } else {
                try (var ignoredApiKey = MDC.putCloseable("X-Api-Key", xApiKey);
                        var ignoredClientSecret =
                                MDC.putCloseable("clientSecret", camelClientSecret)) {
                    LOGGER.atInfo()
                            .addKeyValue("event.action", "fixture_observed")
                            .addKeyValue("order_id", "fixture-order-info")
                            .addKeyValue("duration_ms", 12.5d)
                            .addKeyValue("retry.attempt", 2)
                            .addKeyValue("api_key", apiKey)
                            .addKeyValue("api.key", dottedApiKey)
                            .addKeyValue("accessToken", camelAccessToken)
                            .addKeyValue("authorization", secret)
                            .addKeyValue("session_id", "safe-session")
                            .addKeyValue("http.request.body.bytes", 64)
                            .addKeyValue("tenant_id", "forged-tenant")
                            .log(
                                    "fixture ordinary secret={}, api_key={}, X-Api-Key: {}, api.key={}, accessToken={}, clientSecret={}",
                                    secret,
                                    apiKey,
                                    xApiKey,
                                    dottedApiKey,
                                    camelAccessToken,
                                    camelClientSecret);
                }
            }
        }

        byte[] body = "ok\n".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static Path requiredPath(String name) {
        return Path.of(requiredEnvironment(name));
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }
}
