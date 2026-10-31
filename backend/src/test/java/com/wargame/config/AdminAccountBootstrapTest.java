package com.wargame.config;

import com.wargame.model.entity.AdminUser;
import com.wargame.repository.AdminUserRepository;
import com.wargame.security.AdminCredentials;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminAccountBootstrapTest {
    private final AdminUserRepository admins = mock(AdminUserRepository.class);
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
    private static final String PASSWORD = "isolated-bootstrap-password";

    @Test void emptyConfigurationCannotCreateADefaultPassword() {
        new AdminAccountBootstrap(admins, passwords, "admin", "").run(null);
        verifyNoInteractions(admins);
    }

    @Test void newAdministratorIsCreatedOnlyWithExplicitCredentials() {
        when(admins.findByUsername("operator")).thenReturn(Optional.empty());
        new AdminAccountBootstrap(admins, passwords, "operator", PASSWORD).run(null);
        var saved = ArgumentCaptor.forClass(AdminUser.class);
        verify(admins).saveAndFlush(saved.capture());
        assertEquals("operator", saved.getValue().getUsername());
        assertEquals("SUPER_ADMIN", saved.getValue().getRole());
        assertTrue(saved.getValue().isActive());
        assertNotEquals(PASSWORD, saved.getValue().getPasswordHash());
        assertTrue(passwords.matches(PASSWORD, saved.getValue().getPasswordHash()));
    }

    @Test void onlyTheMigratedStockCredentialCanBeReplaced() {
        var admin = new AdminUser();
        admin.setPasswordHash(AdminCredentials.LEGACY_DEFAULT_HASH);
        admin.setDisabled(1);
        when(admins.findByUsername("admin")).thenReturn(Optional.of(admin));
        new AdminAccountBootstrap(admins, passwords, "admin", PASSWORD).run(null);
        verify(admins).saveAndFlush(admin);
        assertTrue(admin.isActive());
        assertTrue(passwords.matches(PASSWORD, admin.getPasswordHash()));
    }

    @Test void restartCannotResetAnExistingPasswordOrReenableACustomDisabledAccount() {
        var admin = new AdminUser();
        String existing = passwords.encode("existing-custom-password");
        admin.setPasswordHash(existing);
        admin.setDisabled(1);
        when(admins.findByUsername("admin")).thenReturn(Optional.of(admin));
        new AdminAccountBootstrap(admins, passwords, "admin", PASSWORD).run(null);
        verify(admins, never()).saveAndFlush(any());
        assertEquals(existing, admin.getPasswordHash());
        assertFalse(admin.isActive());
    }

    @Test void invalidInitializationFailsBeforeAnyDatabaseMutation() {
        assertThrows(IllegalArgumentException.class, () -> new AdminAccountBootstrap(admins, passwords, "admin", "admin123456").run(null));
        assertThrows(IllegalArgumentException.class, () -> new AdminAccountBootstrap(admins, passwords, "admin", "中".repeat(25)).run(null));
        assertThrows(IllegalArgumentException.class, () -> new AdminAccountBootstrap(admins, passwords, "admin user", PASSWORD).run(null));
        verifyNoInteractions(admins);
    }
}
