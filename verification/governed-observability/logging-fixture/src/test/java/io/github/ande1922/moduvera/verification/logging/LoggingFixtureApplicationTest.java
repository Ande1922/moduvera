package io.github.ande1922.moduvera.verification.logging;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;

class LoggingFixtureApplicationTest {

    @Test
    void closesOwnedContextAndServerWhenLifetimeEnds(@TempDir Path temporaryDirectory) throws Exception {
        AtomicBoolean contextClosed = new AtomicBoolean();
        SpringApplication application = applicationObservingClosure(contextClosed);
        Path portFile = temporaryDirectory.resolve("logging-fixture.port");

        LoggingFixtureApplication.runUntilReleased(
                application,
                new String[] {"--spring.main.banner-mode=off"},
                portFile,
                new CountDownLatch(0));

        assertTrue(contextClosed.get());
        int port = Integer.parseInt(Files.readString(portFile).trim());
        assertThrows(IOException.class, () -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
            }
        });
    }

    @Test
    void closesOwnedContextWhenPortPublicationFails(@TempDir Path temporaryDirectory) {
        AtomicBoolean contextClosed = new AtomicBoolean();
        SpringApplication application = applicationObservingClosure(contextClosed);
        Path missingParentPortFile = temporaryDirectory.resolve("missing").resolve("logging-fixture.port");

        assertThrows(IOException.class, () -> LoggingFixtureApplication.runUntilReleased(
                application,
                new String[] {"--spring.main.banner-mode=off"},
                missingParentPortFile,
                new CountDownLatch(1)));

        assertTrue(contextClosed.get());
    }

    private static SpringApplication applicationObservingClosure(AtomicBoolean contextClosed) {
        SpringApplication application = new SpringApplication(LoggingFixtureApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.addListeners(
                (ApplicationListener<ContextClosedEvent>) event -> contextClosed.set(true));
        return application;
    }
}
