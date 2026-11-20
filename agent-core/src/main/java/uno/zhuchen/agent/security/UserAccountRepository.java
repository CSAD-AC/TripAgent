package uno.zhuchen.agent.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.Profile;
import uno.zhuchen.agent.domain.entity.UserAccountEntity;
import uno.zhuchen.agent.persistence.mapper.UserAccountMapper;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
@Profile("!dev && !test")
public class UserAccountRepository implements UserAccountStore {

    private final UserAccountMapper mapper;

    public UserAccountRepository(UserAccountMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<UserAccountEntity> findByUsername(String username) {
        return Optional.ofNullable(mapper.selectOne(
                new LambdaQueryWrapper<UserAccountEntity>()
                        .eq(UserAccountEntity::getUsername, username)
                        .last("LIMIT 1")));
    }

    @Override
    public void insert(UserAccountEntity account) {
        mapper.insert(account);
    }

    @Override
    public void recordSuccessfulLogin(String userId) {
        mapper.update(null, new LambdaUpdateWrapper<UserAccountEntity>()
                .eq(UserAccountEntity::getId, userId)
                .set(UserAccountEntity::getFailedLoginCount, 0)
                .set(UserAccountEntity::getLockedUntil, null)
                .set(UserAccountEntity::getLastLoginAt, LocalDateTime.now())
                .set(UserAccountEntity::getUpdatedAt, LocalDateTime.now()));
    }

    @Override
    public int recordFailedLogin(String username, int maxFailedLogins, int lockMinutes) {
        UserAccountEntity account = findByUsername(username).orElse(null);
        if (account == null) {
            return 0;
        }
        int failures = account.getFailedLoginCount() == null ? 0 : account.getFailedLoginCount();
        int nextFailures = failures + 1;
        LocalDateTime lockedUntil = nextFailures >= maxFailedLogins
                ? LocalDateTime.now().plusMinutes(lockMinutes)
                : null;
        mapper.update(null, new LambdaUpdateWrapper<UserAccountEntity>()
                .eq(UserAccountEntity::getId, account.getId())
                .set(UserAccountEntity::getFailedLoginCount, nextFailures)
                .set(UserAccountEntity::getLockedUntil, lockedUntil)
                .set(UserAccountEntity::getUpdatedAt, LocalDateTime.now()));
        return nextFailures;
    }
}
