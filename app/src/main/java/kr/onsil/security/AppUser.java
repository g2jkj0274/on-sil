package kr.onsil.security;

import kr.onsil.domain.AccountType;
import kr.onsil.domain.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/** 로그인한 사용자 정보 (세션에 저장됨). */
public class AppUser implements UserDetails {
    private final Long id;
    private final String username;
    private final String passwordHash;
    private final String displayName;
    private final AccountType accountType;

    public AppUser(User u) {
        this.id = u.getId();
        this.username = u.getUsername();
        this.passwordHash = u.getPasswordHash();
        this.displayName = u.getDisplayName();
        this.accountType = u.getAccountType();
    }

    public Long getId() { return id; }
    public String getDisplayName() { return displayName; }
    public AccountType getAccountType() { return accountType; }

    @Override public String getUsername() { return username; }
    @Override public String getPassword() { return passwordHash; }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + accountType.name()));
    }
}
