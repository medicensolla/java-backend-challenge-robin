package com.example.jbc.sessions;

import java.sql.SQLException;
import java.util.UUID;

import com.example.jbc.coaches.CoachRepository;
import com.example.jbc.common.ApiException;
import com.example.jbc.registrations.RegistrationRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionDeletionServiceTest {

    private static final UUID SESSION_ID = UUID.randomUUID();

    @Mock
    private CoachRepository coaches;
    @Mock
    private SessionRepository sessions;
    @Mock
    private RegistrationRepository registrations;
    @InjectMocks
    private SessionService service;

    @Test
    void missingSessionTakesPrecedenceOverRegistrationLookup() {
        assertThatThrownBy(() -> service.delete(SESSION_ID))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.getCode()).isEqualTo("SESSION_NOT_FOUND");
                });
        verifyNoInteractions(registrations, coaches);
        verify(sessions, never()).deleteSessionById(any());
    }

    @Test
    void blocksDeletionWhenRegistrationsExist() {
        when(sessions.existsById(SESSION_ID)).thenReturn(true);
        when(registrations.existsBySessionId(SESSION_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.delete(SESSION_ID))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getCode()).isEqualTo("SESSION_HAS_REGISTRATIONS");
                    assertThat(exception.getMessage()).isEqualTo("Cancel all registrations before deleting this session.");
                });
        verify(sessions, never()).deleteSessionById(any());
    }

    @Test
    void deletesOnlyAfterCheckingExistenceAndRegistrations() {
        emptySession();
        when(sessions.deleteSessionById(SESSION_ID)).thenReturn(1);

        service.delete(SESSION_ID);

        var order = inOrder(sessions, registrations);
        order.verify(sessions).existsById(SESSION_ID);
        order.verify(registrations).existsBySessionId(SESSION_ID);
        order.verify(sessions).deleteSessionById(SESSION_ID);
        verifyNoInteractions(coaches);
    }

    @Test
    void returnsNotFoundIfAnotherRequestAlreadyDeletedTheSession() {
        emptySession();
        when(sessions.deleteSessionById(SESSION_ID)).thenReturn(0);

        assertThatThrownBy(() -> service.delete(SESSION_ID))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.getCode()).isEqualTo("SESSION_NOT_FOUND");
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void translatesOnlyTheRegistrationSessionForeignKeyEvenWhenWrapped(boolean wrapped) {
        emptySession();
        var violation = new ConstraintViolationException("referenced session", new SQLException("FK", "23503"),
                "fk_registrations_session");
        when(sessions.deleteSessionById(SESSION_ID)).thenThrow(new DataIntegrityViolationException("delete failed",
                wrapped ? new IllegalStateException("wrapped", violation) : violation));

        assertThatThrownBy(() -> service.delete(SESSION_ID))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getCode()).isEqualTo("SESSION_HAS_REGISTRATIONS");
                });
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"fk_sessions_coach", "another_constraint"})
    void preservesOtherOrUnnamedConstraintFailures(String constraint) {
        emptySession();
        var failure = new DataIntegrityViolationException("delete failed",
                new ConstraintViolationException("other", new SQLException("other", "23503"), constraint));
        when(sessions.deleteSessionById(SESSION_ID)).thenThrow(failure);

        assertThatThrownBy(() -> service.delete(SESSION_ID)).isSameAs(failure);
    }

    @Test
    void preservesIntegrityFailuresWithoutConstraintMetadata() {
        emptySession();
        var failure = new DataIntegrityViolationException("internal details");
        when(sessions.deleteSessionById(SESSION_ID)).thenThrow(failure);

        assertThatThrownBy(() -> service.delete(SESSION_ID)).isSameAs(failure);
    }

    private void emptySession() {
        when(sessions.existsById(SESSION_ID)).thenReturn(true);
        when(registrations.existsBySessionId(SESSION_ID)).thenReturn(false);
    }
}
