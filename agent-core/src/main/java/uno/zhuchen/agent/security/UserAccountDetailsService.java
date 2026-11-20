package uno.zhuchen.agent.security;

import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;

@Service
public class UserAccountDetailsService implements ReactiveUserDetailsService {

    private final UserAccountStore repository;
    private final String dummyPasswordHash;

    public UserAccountDetailsService(UserAccountStore repository,
                                     PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.dummyPasswordHash = passwordEncoder.encode("travelpal-not-a-real-password");
    }

    @Override
    public Mono<UserDetails> findByUsername(String username) {
        String normalized = normalizeUsername(username);
        return Mono.fromCallable(() -> (UserDetails) repository.findByUsername(normalized)
                        .map(UserAccountDetailsService::toPrincipal)
                        .orElseGet(() -> new UserPrincipal(
                                "missing",
                                normalized,
                                "",
                                dummyPasswordHash,
                                true,
                                true)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public static UserPrincipal toPrincipal(uno.zhuchen.agent.domain.entity.UserAccountEntity account) {
        boolean active = "active".equals(account.getStatus());
        boolean locked = account.getLockedUntil() != null
                && account.getLockedUntil().isAfter(LocalDateTime.now());
        return new UserPrincipal(
                account.getId(),
                account.getUsername(),
                account.getDisplayName(),
                account.getPasswordHash(),
                active,
                active && !locked);
    }

    public static String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase();
    }
}
