package io.github.ande1922.moduvera.reference.order.adapter.outbound.http;

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

final class HttpClientLogProbe extends AppenderBase<ILoggingEvent> implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Logger logger = (Logger) LoggerFactory.getLogger("http.client");
    private final ModuveraEcsStructuredLogFormatter formatter = new ModuveraEcsStructuredLogFormatter(new MockEnvironment());
    private final List<JsonNode> events = new CopyOnWriteArrayList<>();

    HttpClientLogProbe() {
        setContext(logger.getLoggerContext());
        start();
        logger.addAppender(this);
    }

    @Override
    protected void append(ILoggingEvent event) {
        events.add(JSON.readTree(formatter.format(event)));
    }

    List<JsonNode> events() {
        return List.copyOf(events);
    }

    @Override
    public void close() {
        logger.detachAppender(this);
        stop();
    }
}
