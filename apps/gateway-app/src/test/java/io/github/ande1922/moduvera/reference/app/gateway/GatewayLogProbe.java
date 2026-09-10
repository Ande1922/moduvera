package io.github.ande1922.moduvera.reference.app.gateway;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import io.github.ande1922.moduvera.logging.ModuveraEcsStructuredLogFormatter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Formats inside the emitting callback so tests observe the real diagnostic projection. */
final class GatewayLogProbe extends AppenderBase<ILoggingEvent> implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final List<JsonNode> events = new CopyOnWriteArrayList<>();
    private final ModuveraEcsStructuredLogFormatter formatter =
            new ModuveraEcsStructuredLogFormatter(new MockEnvironment());
    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);

    GatewayLogProbe() {
        setContext(root.getLoggerContext());
        start();
        root.addAppender(this);
    }

    @Override
    protected void append(ILoggingEvent event) {
        events.add(JSON.readTree(formatter.format(event)));
    }

    List<JsonNode> canonical() {
        return events.stream()
                .filter(event -> event.path("log").path("logger").asString().equals("http.request"))
                .toList();
    }

    List<JsonNode> clients() {
        return events.stream().filter(event -> event.path("log").path("logger").asString().equals("http.client")).toList();
    }

    List<JsonNode> errors() {
        return events.stream()
                .filter(event -> event.path("log").path("level").asString().equals("ERROR"))
                .toList();
    }

    @Override
    public void close() {
        root.detachAppender(this);
        stop();
    }
}
