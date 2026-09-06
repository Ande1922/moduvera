package io.github.ande1922.moduvera.verification.otel;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;

/** Real HTTP server fixture that makes the Agent create an exception event. */
public final class ExceptionServerFixture {

    private ExceptionServerFixture() {}

    public static void main(String[] ignoredArguments) throws Exception {
        String sqlSentinel = System.getenv("FIXTURE_SQL_SENTINEL");
        if (sqlSentinel == null || sqlSentinel.isBlank()) {
            throw new IllegalArgumentException("FIXTURE_SQL_SENTINEL is required");
        }
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/boom", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            throw new IllegalStateException("failure query=" + query + " sql=" + sqlSentinel);
        });
        server.start();
        System.out.println(server.getAddress().getPort());
        System.out.flush();
        Thread.sleep(15_000);
        server.stop(0);
    }
}
