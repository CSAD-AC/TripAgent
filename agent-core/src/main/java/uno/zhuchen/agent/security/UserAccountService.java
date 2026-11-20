package uno.zhuchen.agent.security;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import uno.zhuchen.agent.common.exception.ConflictException;
import uno.zhuchen.agent.domain.dto.RegisterRequest;
import uno.zhuchen.agent.domain.entity.UserAccountEntity;

import java.time.LocalDateTime;

@Service
public class UserAccountService {

    private static final int MAX_FAILED_LOGINS = 5;
    private static final int LOCK_MINUTES = 15;

    private final UserAccountStore repository;
    private final PasswordEncoder passwordEncoder;

    public UserAccountService(UserAccountStore repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    public Mono<UserPrincipal> register(RegisterRequest request) {
        return Mono.fromCallable(() -> doRegister(request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    protected UserPrincipal doRegister(RegisterRequest request) {
        String username = UserAccountDetailsService.normalizeUsername(request.username());
        if (repository.findByUsername(username).isPresent()) {
            throw new ConflictException("用户名已被占用");
        }

        UserAccountEntity account = new UserAccountEntity();
        account.setUsername(username);
        account.setPasswordHash(passwordEncoder.encode(request.password()));
        account.setDisplayName(request.displayName().trim());
        account.setStatus("active");
        account.setFailedLoginCount(0);
        try {
            repository.insert(account);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("用户名已被占用");
        }
        return UserAccountDetailsService.toPrincipal(account);
    }

    public Mono<Void> recordSuccessfulLogin(String userId) {
        return Mono.fromRunnable(() -> repository.recordSuccessfulLogin(userId))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    public Mono<Void> recordFailedLogin(String username) {
        return Mono.fromRunnable(() -> repository.recordFailedLogin(
                        UserAccountDetailsService.normalizeUsername(username),
                        MAX_FAILED_LOGINS,
                        LOCK_MINUTES))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    public int maxFailedLogins() {
        return MAX_FAILED_LOGINS;
    }
}
