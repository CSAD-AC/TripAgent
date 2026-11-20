package uno.zhuchen.agent.security;

import uno.zhuchen.agent.domain.entity.UserAccountEntity;

import java.util.Optional;

public interface UserAccountStore {

    Optional<UserAccountEntity> findByUsername(String username);

    void insert(UserAccountEntity account);

    void recordSuccessfulLogin(String userId);

    int recordFailedLogin(String username, int maxFailedLogins, int lockMinutes);
}
