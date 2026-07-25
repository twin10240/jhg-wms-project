package com.jhg.wms.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "wms_user", uniqueConstraints =
        @UniqueConstraint(name = "uk_wms_user_username", columnNames = "username"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WmsUser {

    @Id @GeneratedValue
    @Column(name = "wms_user_id")
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    private String password;   // bcrypt 해시

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WmsRole role;

    public static WmsUser create(String username, String password, WmsRole role) {
        if (username == null || username.isBlank())
            throw new IllegalArgumentException("username is required");
        if (password == null || password.isBlank())
            throw new IllegalArgumentException("password is required");
        if (role == null)
            throw new IllegalArgumentException("role is required");
        WmsUser user = new WmsUser();
        user.username = username.trim();
        user.password = password;
        user.role = role;
        return user;
    }
}
