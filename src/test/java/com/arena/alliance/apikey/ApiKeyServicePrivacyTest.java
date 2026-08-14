package com.arena.alliance.apikey;

import com.arena.alliance.common.AllianceException;
import com.arena.alliance.common.CryptoService;
import com.arena.alliance.engine.EngineEvents;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiKeyServicePrivacyTest {

    @Test
    void updatesOwnedKeyPrivacyAndPublishesRefreshEvent() {
        ApiKeyRepository repository = mock(ApiKeyRepository.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        ApiKeyService service = new ApiKeyService(repository, mock(CryptoService.class), publisher);
        ApiKey key = mock(ApiKey.class);
        when(key.getId()).thenReturn(11L);
        when(key.getUserId()).thenReturn(7L);
        when(repository.findById(11L)).thenReturn(Optional.of(key));

        service.setPrivacy(7L, 11L, false, true);

        verify(key).setShowCoreOnMap(false);
        verify(key).setRosterIdsOnly(true);
        verify(repository).save(key);
        verify(publisher).publishEvent(new EngineEvents.KeyPrivacyChanged(11L));
    }

    @Test
    void readsPrivacyForSeveralKeysInOneBatch() {
        ApiKeyRepository repository = mock(ApiKeyRepository.class);
        ApiKeyService service = new ApiKeyService(repository, mock(CryptoService.class),
                mock(ApplicationEventPublisher.class));
        ApiKey key = mock(ApiKey.class);
        when(key.getId()).thenReturn(11L);
        when(key.isShowCoreOnMap()).thenReturn(false);
        when(key.isRosterIdsOnly()).thenReturn(true);
        when(repository.findAllById(java.util.Set.of(11L, 12L))).thenReturn(java.util.List.of(key));

        Map<Long, ApiKeyService.PrivacySettings> settings =
                service.privacySettings(java.util.Set.of(11L, 12L));

        assertFalse(settings.get(11L).showCoreOnMap());
        assertTrue(settings.get(11L).rosterIdsOnly());
        assertEquals(ApiKeyService.PrivacySettings.DEFAULT,
                settings.getOrDefault(12L, ApiKeyService.PrivacySettings.DEFAULT));
    }

    @Test
    void cannotChangeAnotherUsersPrivacy() {
        ApiKeyRepository repository = mock(ApiKeyRepository.class);
        ApiKeyService service = new ApiKeyService(repository, mock(CryptoService.class),
                mock(ApplicationEventPublisher.class));
        ApiKey key = mock(ApiKey.class);
        when(key.getUserId()).thenReturn(7L);
        when(repository.findById(11L)).thenReturn(Optional.of(key));

        AllianceException denied = assertThrows(AllianceException.class,
                () -> service.setPrivacy(8L, 11L, false, true));

        assertEquals(403, denied.getStatus());
    }
}
