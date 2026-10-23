package ai.streamsphere.upload;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.streamsphere.common.messaging.OutboxWriter;
import ai.streamsphere.common.storage.StorageProvider;
import ai.streamsphere.common.storage.StorageProvider.Part;
import ai.streamsphere.common.web.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Server never trusts the client's view of the upload: storage is the source of truth. */
class UploadCompletionTest {
    final StorageProvider storage = mock(StorageProvider.class);
    final JdbcClient jdbc = mock(JdbcClient.class, Answers.RETURNS_DEEP_STUBS);
    final OutboxWriter outbox = mock(OutboxWriter.class);
    UploadService service;
    UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        var props = new UploadProperties("b", 100, 8, java.time.Duration.ofMinutes(1), java.time.Duration.ofHours(1), java.time.Duration.ofDays(1),
                java.util.Set.of("video/mp4"), 1000, 0.95);
        service = new UploadService(jdbc, storage, props, mock(CostGuard.class), mock(OwnershipClient.class), outbox);
        var session = new UploadSession(id, UUID.randomUUID(), "me", "b", "k", "up", "f", "video/mp4", 16, 8, 2, "0".repeat(64),
                "UPLOADING", null, "minio", Instant.now(), null, Instant.now().plusSeconds(60), 0);
        when(jdbc.sql(anyString()).param(anyString(), any()).query(UploadSession.class).optional()).thenReturn(java.util.Optional.of(session));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                Jwt.withTokenValue("t").header("alg", "none").subject("me").build()));
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void missingPartInStorageIsRefused() {
        when(storage.listParts("b", "k", "up")).thenReturn(List.of(new Part(1, "\"e1\"", 8)));
        assertThatThrownBy(() -> service.complete(id, List.of(new Part(1, "e1", 0), new Part(2, "e2", 0))))
                .isInstanceOf(ApiException.class).hasMessageContaining("1 of 2");
        verify(storage, never()).completeMultipartUpload(any(), any(), any(), any());
        verifyNoInteractions(outbox);
    }

    @Test
    void etagMismatchIsRefused() {
        when(storage.listParts("b", "k", "up")).thenReturn(List.of(new Part(1, "\"e1\"", 8), new Part(2, "\"real\"", 8)));
        assertThatThrownBy(() -> service.complete(id, List.of(new Part(1, "e1", 0), new Part(2, "forged", 0))))
                .isInstanceOf(ApiException.class).hasMessageContaining("Part 2");
        verify(storage, never()).completeMultipartUpload(any(), any(), any(), any());
    }

    @Test
    void otherUserCannotComplete() {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                Jwt.withTokenValue("t").header("alg", "none").subject("attacker").build()));
        assertThatThrownBy(() -> service.complete(id, List.of())).isInstanceOf(ApiException.class).hasMessageContaining("not found");
        verifyNoInteractions(storage);
    }
}
