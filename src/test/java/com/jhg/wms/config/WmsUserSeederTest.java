package com.jhg.wms.config;

import com.jhg.wms.domain.WmsRole;
import com.jhg.wms.domain.WmsUser;
import com.jhg.wms.repository.WmsUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WmsUserSeederTest {

    private final WmsUserRepository repo = mock(WmsUserRepository.class);
    private final PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    private WmsUserSeeder seeder(String opPw, String mgrPw) {
        return new WmsUserSeeder(repo, encoder, "operator", opPw, "manager", mgrPw);
    }

    @Test
    void seed_없는_계정을_bcrypt로_저장한다() {
        when(repo.findByUsername(any())).thenReturn(Optional.empty());

        seeder("operator", "manager").seed();

        var saved = org.mockito.ArgumentCaptor.forClass(WmsUser.class);
        verify(repo, times(2)).save(saved.capture());
        WmsUser op = saved.getAllValues().get(0);
        assertThat(op.getUsername()).isEqualTo("operator");
        assertThat(op.getRole()).isEqualTo(WmsRole.OPERATOR);
        assertThat(encoder.matches("operator", op.getPassword())).isTrue();   // 평문 아님
    }

    @Test
    void seed_이미_있으면_저장하지_않는다() {
        when(repo.findByUsername("operator"))
                .thenReturn(Optional.of(WmsUser.create("operator", "{noop}x", WmsRole.OPERATOR)));
        when(repo.findByUsername("manager")).thenReturn(Optional.empty());

        seeder("operator", "manager").seed();

        verify(repo, times(1)).save(any());   // manager만
    }

    @Test
    void seed_자격증명_공백이면_기동_실패() {
        assertThatThrownBy(() -> seeder("", "manager").seed())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> seeder("operator", "  ").seed())
                .isInstanceOf(IllegalStateException.class);
    }
}
