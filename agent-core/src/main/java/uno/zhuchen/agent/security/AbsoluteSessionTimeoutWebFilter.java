package uno.zhuchen.agent.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class AbsoluteSessionTimeoutWebFilter implements WebFilter {

    public static final String AUTHENTICATED_AT = "authenticatedAt";

    private final long absoluteTimeoutSeconds;

    public AbsoluteSessionTimeoutWebFilter(
            @Value("${app.auth.session-absolute-timeout-seconds:43200}") long absoluteTimeoutSeconds) {
        this.absoluteTimeoutSeconds = absoluteTimeoutSeconds;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return exchange.getSession().flatMap(session -> {
            Object value = session.getAttribute(AUTHENTICATED_AT);
            if (value == null) {
                return chain.filter(exchange);
            }
            Instant authenticatedAt = value instanceof Number number
                    ? Instant.ofEpochSecond(number.longValue())
                    : Instant.now();
            if (Duration.between(authenticatedAt, Instant.now()).getSeconds()
                    < absoluteTimeoutSeconds) {
                return chain.filter(exchange);
            }
            return session.invalidate().then(writeUnauthorized(exchange));
        });
    }

    private Mono<Void> writeUnauthorized(ServerWebExchange exchange) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = "{\"code\":401,\"message\":\"登录已超过最长有效期，请重新登录\",\"data\":null}"
                .getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }
}
