package com.cenoffice.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "users")
public class UserAccount {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 64)
    private String username;
    @Column(nullable = false)
    private String passwordHash;
    @Column(nullable = false, length = 100)
    private String displayName;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private Enums.Role role;
    @Column(length = 64)
    private String team;

    protected UserAccount() {}

    public UserAccount(String username, String passwordHash, String displayName, Enums.Role role) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.role = role;
    }

    public String getTeam() { return team; }
    public void setTeam(String team) { this.team = team; }

    public void changePasswordHash(String hash) { this.passwordHash = hash; }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public String getDisplayName() { return displayName; }
    public Enums.Role getRole() { return role; }
}
