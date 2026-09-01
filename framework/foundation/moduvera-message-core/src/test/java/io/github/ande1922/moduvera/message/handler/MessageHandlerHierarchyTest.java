package io.github.ande1922.moduvera.message.handler;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ande1922.moduvera.message.SerializedMessage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessageHandlerHierarchyTest {

    @Test
    void commandAndEventHandlersAreInboundFunctionalHandlers() {
        List<SerializedMessage> handled = new ArrayList<>();
        CommandMessageHandler command = handled::add;
        EventMessageHandler event = handled::add;

        command.handle(null);
        event.handle(null);

        assertThat(command).isInstanceOf(InboundMessageHandler.class);
        assertThat(event).isInstanceOf(InboundMessageHandler.class);
        assertThat(handled).containsExactly(null, null);
        assertThat(InboundMessageHandler.class).hasAnnotation(FunctionalInterface.class);
        assertThat(CommandMessageHandler.class).hasAnnotation(FunctionalInterface.class);
        assertThat(EventMessageHandler.class).hasAnnotation(FunctionalInterface.class);
    }
}
