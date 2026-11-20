package uno.zhuchen.agent.security;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import uno.zhuchen.agent.domain.entity.UserAccountEntity;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
@Profile("dev || test")
public class InMemoryUserAccountRepository implements UserAccountStore {

    private final ConcurrentMap<String, UserAccountEntity> accounts = new ConcurrentHashMap<>();

    @Override
    public Optional<UserAccountEntity> findByUsername(String username) {
        return Optional.ofNullable(accounts.get(username)).map(InMemoryUserAccountRepository::copy);
    }

    @Override
    public void insert(UserAccountEntity account) {
        if (accounts.putIfAbsent(account.getUsername(), copy(account)) != null) {
            throw new org.springframework.dao.DuplicateKeyException("duplicate username");
        }
        UserAccountEntity stored = accounts.get(account.getUsername());
        if (account.getId() == null || account.getId().isBlank()) {
            account.setId(stored.getId());
        }
    }

    @Override
    public synchronized void recordSuccessfulLogin(String userId) {
        accounts.values().stream()
                .filter(account -> userId.equals(account.getId()))
                .findFirst()
                .ifPresent(account -> {
                    account.setFailedLoginCount(0);
                    account.setLockedUntil(null);
                    account.setLastLoginAt(LocalDateTime.now());
                    account.setUpdatedAt(LocalDateTime.now());
                });
    }

    @Override
    public synchronized int recordFailedLogin(String username, int maxFailedLogins, int lockMinutes) {
        UserAccountEntity account = accounts.get(username);
        if (account == null) {
            return 0;
        }
        int failures = account.getFailedLoginCount() == null ? 0 : account.getFailedLoginCount();
        int nextFailures = failures + 1;
        account.setFailedLoginCount(nextFailures);
        account.setLockedUntil(nextFailures >= maxFailedLogins
                ? LocalDateTime.now().plusMinutes(lockMinutes)
                : null);
        account.setUpdatedAt(LocalDateTime.now());
        return nextFailures;
    }

    private static UserAccountEntity copy(UserAccountEntity source) {
        UserAccountEntity target = new UserAccountEntity();
        target.setId(source.getId() != null ? source.getId() : UUID.randomUUID().toString());
        target.setUsername(source.getUsername());
        target.setPasswordHash(source.getPasswordHash());
        target.setDisplayName(source.getDisplayName());
        target.setStatus(source.getStatus());
        target.setFailedLoginCount(source.getFailedLoginCount());
        target.setLockedUntil(source.getLockedUntil());
        target.setLastLoginAt(source.getLastLoginAt());
        target.setCreatedAt(source.getCreatedAt() != null ? source.getCreatedAt() : LocalDateTime.now());
        target.setUpdatedAt(source.getUpdatedAt() != null ? source.getUpdatedAt() : LocalDateTime.now());
        return target;
    }
}
