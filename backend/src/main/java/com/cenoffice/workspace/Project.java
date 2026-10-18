package com.cenoffice.workspace;

import jakarta.persistence.*;

@Entity
@Table(name = "projects")
public class Project extends Timestamped {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 120)
    private String name;
    @Column(length = 2000)
    private String description;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private WsEnums.ProjectStatus status = WsEnums.ProjectStatus.ACTIVE;
    @Column(nullable = false, length = 64)
    private String owner;

    protected Project() {}

    public Project(String name, String description, String owner) {
        this.name = name;
        this.description = description;
        this.owner = owner;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public WsEnums.ProjectStatus getStatus() { return status; }
    public String getOwner() { return owner; }

    public void update(String name, String description, WsEnums.ProjectStatus status) {
        this.name = name;
        this.description = description;
        if (status != null) this.status = status;
    }
}
