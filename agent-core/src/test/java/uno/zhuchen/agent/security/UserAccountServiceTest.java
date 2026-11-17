package uno.zhuchen.agent.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import uno.zhuchen.agent.common.exception.ConflictException;
import uno.zhuchen.agent.domain.dto.RegisterRequest;
import uno.zhuchen.agent.domain.entity.UserAccountEntity;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserAccountServiceTest {

    @Mock
    private UserAccountRepository repository;

    private UserAccountService service;
    private BCryptPasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder(4);
        service = new UserAccountService(repository, passwordEncoder);
    }

    @Test
    void register_normalizesUsernameAndHashesPassword() {
        when(repository.findByUsername("alice")).thenReturn(Optional.empty());

        UserPrincipal principal = service.register(
                new RegisterRequest(" Alice ", "password123", " Alice "))
                .block();

        ArgumentCaptor<UserAccountEntity> captor = ArgumentCaptor.forClass(UserAccountEntity.class);
        verify(repository).insert(captor.capture());
        UserAccountEntity stored = captor.getValue();
        assertEquals("alice", stored.getUsername());
        assertEquals("Alice", stored.getDisplayName());
        assertNotEquals("password123", stored.getPasswordHash());
        assertTrue(passwordEncoder.matches("password123", stored.getPasswordHash()));
        assertEquals("alice", principal.getUsername());
    }

    @Test
    void register_rejectsDuplicateUsername() {
        when(repository.findByUsername("alice")).thenReturn(Optional.of(new UserAccountEntity()));

        assertThrows(ConflictException.class, () -> service.register(
                new RegisterRequest("alice", "password123", "Alice")).block());
    }

    @Test
    void register_translatesConcurrentDuplicateToConflict() {
        when(repository.findByUsername("alice")).thenReturn(Optional.empty());
        doThrow(new DuplicateKeyException("duplicate"))
                .when(repository).insert(any(UserAccountEntity.class));

        assertThrows(ConflictException.class, () -> service.register(
                new RegisterRequest("alice", "password123", "Alice")).block());
    }

    @Test
    void failedLogin_usesConfiguredLockThreshold() {
        service.recordFailedLogin("Alice").block();

        verify(repository).recordFailedLogin("alice", 5, 15);
    }

    @Test
    void principal_rejectsLockedOrDisabledAccounts() {
        UserAccountEntity locked = new UserAccountEntity();
        locked.setId("u1");
        locked.setUsername("alice");
        locked.setDisplayName("Alice");
        locked.setPasswordHash("hash");
        locked.setStatus("active");
        locked.setLockedUntil(java.time.LocalDateTime.now().plusMinutes(5));

        UserPrincipal lockedPrincipal = UserAccountDetailsService.toPrincipal(locked);

        assertFalse(lockedPrincipal.isAccountNonLocked());
        assertTrue(lockedPrincipal.isEnabled());

        locked.setStatus("disabled");
        UserPrincipal disabledPrincipal = UserAccountDetailsService.toPrincipal(locked);
        assertFalse(disabledPrincipal.isEnabled());
    }
}
