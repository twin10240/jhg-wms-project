package com.jhg.wms.config;

import com.jhg.wms.domain.WmsRole;
import com.jhg.wms.domain.WmsUser;
import com.jhg.wms.repository.WmsUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DbUserDetailsServiceTest {

    private final WmsUserRepository repo = mock(WmsUserRepository.class);
    private final DbUserDetailsService service = new DbUserDetailsService(repo);

    @Test
    void loadUserByUsername_롤을_ROLE_접두어_권한으로_노출한다() {
        when(repo.findByUsername("manager"))
                .thenReturn(Optional.of(WmsUser.create("manager", "{noop}pw", WmsRole.MANAGER)));

        UserDetails details = service.loadUserByUsername("manager");

        assertThat(details.getUsername()).isEqualTo("manager");
        assertThat(details.getPassword()).isEqualTo("{noop}pw");
        assertThat(details.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_MANAGER");
    }

    @Test
    void loadUserByUsername_없으면_예외() {
        when(repo.findByUsername("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.loadUserByUsername("ghost"))
                .isInstanceOf(UsernameNotFoundException.class);
    }
}
