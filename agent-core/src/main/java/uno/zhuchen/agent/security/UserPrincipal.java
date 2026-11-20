package uno.zhuchen.agent.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serial;
import java.util.Collection;
import java.util.List;

public final class UserPrincipal implements UserDetails {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String userId;
    private final String username;
    private final String displayName;
    private final String passwordHash;
    private final boolean enabled;
    private final boolean accountNonLocked;

    public UserPrincipal(String userId, String username, String displayName,
                         String passwordHash, boolean enabled, boolean accountNonLocked) {
        this.userId = userId;
        this.username = username;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.enabled = enabled;
        this.accountNonLocked = accountNonLocked;
    }

    public String userId() {
        return userId;
    }

    public String displayName() {
        return displayName;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return accountNonLocked;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
