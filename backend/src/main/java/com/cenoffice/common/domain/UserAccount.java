package com.cenoffice.common.domain;

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
    /** 알림 메일 수신 주소. 비어 있으면 화면 알림만 간다. */
    @Column(length = 254)
    private String email;
    /** 메일 알림 수신 여부. null 이면 수신(기존 계정 호환). */
    private Boolean emailNotify;
    /** 전체(공통) 일정을 등록·수정할 수 있는 일정 관리자(팀장 등). null 이면 아님(기존 계정 호환). 관리자는 이 값과 무관하게 가능. */
    private Boolean calendarManager;

    protected UserAccount() {}

    public UserAccount(String username, String passwordHash, String displayName, Enums.Role role) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.role = role;
    }

    public String getTeam() { return team; }
    public void setTeam(String team) { this.team = team; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public boolean isEmailNotify() { return emailNotify == null || emailNotify; }
    public void setEmailNotify(boolean emailNotify) { this.emailNotify = emailNotify; }

    public boolean isCalendarManager() { return calendarManager != null && calendarManager; }
    public void setCalendarManager(boolean calendarManager) { this.calendarManager = calendarManager; }

    public void changePasswordHash(String hash) { this.passwordHash = hash; }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public String getDisplayName() { return displayName; }
    public Enums.Role getRole() { return role; }
}
