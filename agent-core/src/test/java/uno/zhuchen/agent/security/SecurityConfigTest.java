package uno.zhuchen.agent.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.reactive.context.GenericReactiveWebApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.config.EnableWebFlux;
import reactor.core.publisher.Mono;
import uno.zhuchen.agent.controller.AuthController;
import uno.zhuchen.agent.domain.dto.RegisterRequest;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

class SecurityConfigTest {

    private GenericReactiveWebApplicationContext context;
    private WebTestClient webTestClient;
    private UserAccountDetailsService userAccountDetailsService;

    @BeforeEach
    void setUp() {
        context = new GenericReactiveWebApplicationContext();
        AnnotatedBeanDefinitionReader reader = new AnnotatedBeanDefinitionReader(context);
        reader.register(TestWebConfig.class, SecurityConfig.class, AuthController.class);
        context.refresh();
        userAccountDetailsService = context.getBean(UserAccountDetailsService.class);
        webTestClient = WebTestClient
                .bindToApplicationContext(context)
                .apply(springSecurity())
                .configureClient()
                .responseTimeout(java.time.Duration.ofSeconds(5))
                .build();
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void protectedEndpointRejectsAnonymousRequestWithJson401() {
        webTestClient.get().uri("/api/models")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo(401);
    }

    @Test
    void csrfBootstrapReturnsTokenAndSetsCookie() {
        webTestClient.get().uri("/api/auth/csrf")
                .exchange()
                .expectStatus().isOk()
                .expectCookie().exists("XSRF-TOKEN")
                .expectBody()
                .jsonPath("$.data.token").isNotEmpty();
    }

    @Test
    void loginEstablishesServerSideSessionAndMeUsesIt() {
        String hash = new BCryptPasswordEncoder(4).encode("password123");
        UserPrincipal principal = new UserPrincipal(
                "user-a", "alice", "Alice", hash, true, true);
        when(userAccountDetailsService.findByUsername("alice"))
                .thenReturn(Mono.just(principal));

        var loginResult = webTestClient.mutateWith(csrf())
                .post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"username\":\"alice\",\"password\":\"password123\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.id").isEqualTo("user-a")
                .returnResult();

        List<String> setCookies = loginResult.getResponseHeaders().get("Set-Cookie");
        String sessionCookie = setCookies == null ? null : setCookies.stream()
                .filter(cookie -> cookie.startsWith("SESSION="))
                .findFirst()
                .orElse(null);
        org.junit.jupiter.api.Assertions.assertNotNull(sessionCookie, "登录后应创建 Session");

        webTestClient.mutate()
                .defaultCookie("SESSION", sessionCookie.split(";", 2)[0].substring("SESSION=".length()))
                .build()
                .get().uri("/api/auth/me")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.username").isEqualTo("alice");
    }

    @Configuration
    @EnableWebFlux
    @Import(SecurityConfig.class)
    static class TestWebConfig {

        @Bean
        UserAccountService userAccountService() {
            UserAccountService service = mock(UserAccountService.class);
            when(service.recordSuccessfulLogin(anyString())).thenReturn(Mono.empty());
            when(service.recordFailedLogin(anyString())).thenReturn(Mono.empty());
            when(service.register(any(RegisterRequest.class))).thenReturn(Mono.just(
                    new UserPrincipal("new-user", "new", "New", "hash", true, true)));
            return service;
        }

        @Bean
        UserAccountDetailsService userAccountDetailsService() {
            return mock(UserAccountDetailsService.class);
        }

        @Bean
        JsonAuthenticationEntryPoint jsonAuthenticationEntryPoint() {
            return new JsonAuthenticationEntryPoint();
        }

        @Bean
        JsonAccessDeniedHandler jsonAccessDeniedHandler() {
            return new JsonAccessDeniedHandler();
        }
    }
}
