package io.github.ande1922.moduvera.reference.app.gateway;

import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Observe cancellation before Netty can translate an incomplete write into handler completion. */
final class GatewayDiagnosticResponse extends ServerHttpResponseDecorator {

    private final GatewayRequestDiagnostics state;

    GatewayDiagnosticResponse(ServerHttpResponse response, GatewayRequestDiagnostics state) {
        super(response);
        this.state = state;
    }

    @Override
    public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
        return super.writeWith(observe(body));
    }

    @Override
    public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
        return super.writeAndFlushWith(Flux.from(body).map(this::observe)
                .doOnCancel(state::responseCancelled).doOnError(state::failed));
    }

    private Flux<? extends DataBuffer> observe(Publisher<? extends DataBuffer> body) {
        return Flux.from(body).doOnCancel(state::responseCancelled).doOnError(state::failed);
    }
}
