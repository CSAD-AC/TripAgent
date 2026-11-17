package uno.zhuchen.agent.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UserDetailsRepositoryReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import uno.zhuchen.agent.common.Result;
import uno.zhuchen.agent.domain.dto.LoginRequest;
import uno.zhuchen.agent.domain.dto.RegisterRequest;
import uno.zhuchen.agent.domain.vo.AuthUserVO;
import uno.zhuchen.agent.security.AbsoluteSessionTimeoutWebFilter;
import uno.zhuchen.agent.security.UserAccountService;
import uno.zhuchen.agent.security.UserPrincipal;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserAccountService userAccountService;
    private final UserDetailsRepositoryReactiveAuthenticationManager authenticationManager;
    private final WebSessionServerSecurityContextRepository securityContextRepository;

    public AuthController(UserAccountService userAccountService,
                          UserDetailsRepositoryReactiveAuthenticationManager authenticationManager,
                          WebSessionServerSecurityContextRepository securityContextRepository) {
        this.userAccountService = userAccountService;
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
    }

    @GetMapping("/csrf")
    public Mono<Result<Map<String, String>>> csrf(ServerWebExchange exchange) {
        return exchange.<Mono<CsrfToken>>getAttribute(CsrfToken.class.getName())
                .map(CsrfToken::getToken)
                .map(token -> Result.success(Map.of("token", token)));
    }

    @PostMapping("/register")
    public Mono<ResponseEntity<Result<AuthUserVO>>> register(
            @Valid @RequestBody RegisterRequest request,
            ServerWebExchange exchange) {
        return userAccountService.register(request)
                .flatMap(principal -> {
                    Authentication authentication =
                            new UsernamePasswordAuthenticationToken(
                                    principal, principal.getPassword(), principal.getAuthorities());
                    return userAccountService.recordSuccessfulLogin(principal.userId())
                            .then(saveAuthentication(exchange, authentication))
                            .thenReturn(principal);
                })
                .map(principal -> Result.success(AuthUserVO.from(principal)))
                .map(body -> ResponseEntity.status(HttpStatus.CREATED).body(body));
    }

    @PostMapping("/login")
    public Mono<ResponseEntity<Result<AuthUserVO>>> login(
            @Valid @RequestBody LoginRequest request,
            ServerWebExchange exchange) {
        return authenticate(request.username(), request.password(), exchange)
                .map(principal -> Result.success(AuthUserVO.from(principal)))
                .map(ResponseEntity::ok)
                .onErrorResume(BadCredentialsException.class, error ->
                        userAccountService.recordFailedLogin(request.username())
                                .thenReturn(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                        .body(Result.error(401, "用户名或密码错误"))))
                .onErrorResume(LockedException.class, error ->
                        Mono.just(ResponseEntity.status(HttpStatus.LOCKED)
                                .body(Result.error(423, "账号已临时锁定，请稍后重试"))))
                .onErrorResume(DisabledException.class, error ->
                        Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN)
                                .body(Result.error(403, "账号不可用"))))
                .onErrorResume(AuthenticationException.class, error ->
                        Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                .body(Result.error(401, "用户名或密码错误"))));
    }

    @PostMapping("/logout")
    public Mono<Result<Void>> logout(ServerWebExchange exchange) {
        return securityContextRepository.save(exchange, new SecurityContextImpl())
                .then(exchange.getSession())
                .flatMap(session -> session.invalidate())
                .thenReturn(Result.success(null));
    }

    @GetMapping("/me")
    public Mono<Result<AuthUserVO>> me(Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        return Mono.just(Result.success(AuthUserVO.from(principal)));
    }

    private Mono<UserPrincipal> authenticate(String username, String password,
                                             ServerWebExchange exchange) {
        Authentication request =
                new UsernamePasswordAuthenticationToken(username.trim(), password);
        return authenticationManager.authenticate(request)
                .flatMap(authentication -> {
                    UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
                    return userAccountService.recordSuccessfulLogin(principal.userId())
                            .then(saveAuthentication(exchange, authentication))
                            .thenReturn(principal);
                });
    }

    private Mono<Void> saveAuthentication(ServerWebExchange exchange,
                                          Authentication authentication) {
        return exchange.getSession()
                .doOnNext(session -> {
                    session.changeSessionId();
                    session.getAttributes().put(
                            AbsoluteSessionTimeoutWebFilter.AUTHENTICATED_AT,
                            Instant.now().getEpochSecond());
                })
                .then(securityContextRepository.save(
                        exchange, new SecurityContextImpl(authentication)));
    }
}
