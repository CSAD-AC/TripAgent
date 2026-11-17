package uno.zhuchen.agent.domain.vo;

import uno.zhuchen.agent.security.UserPrincipal;

public record AuthUserVO(String id, String username, String displayName) {

    public static AuthUserVO from(UserPrincipal principal) {
        return new AuthUserVO(principal.userId(), principal.getUsername(), principal.displayName());
    }
}
