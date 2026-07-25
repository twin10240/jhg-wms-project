package com.jhg.wms.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class WmsUserTest {

    @Test
    void create_필수값을_담고_생성한다() {
        WmsUser user = WmsUser.create("operator", "{bcrypt}$2a$hash", WmsRole.OPERATOR);
        assertThat(user.getUsername()).isEqualTo("operator");
        assertThat(user.getPassword()).isEqualTo("{bcrypt}$2a$hash");
        assertThat(user.getRole()).isEqualTo(WmsRole.OPERATOR);
    }

    @Test
    void create_username이_공백이면_예외() {
        assertThatThrownBy(() -> WmsUser.create(" ", "hash", WmsRole.MANAGER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_password가_공백이면_예외() {
        assertThatThrownBy(() -> WmsUser.create("manager", "", WmsRole.MANAGER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_role이_null이면_예외() {
        assertThatThrownBy(() -> WmsUser.create("manager", "hash", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
