package com.example.jbc.registrations;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import com.example.jbc.common.ApiException;
import com.example.jbc.participants.Participant;
import com.example.jbc.participants.ParticipantRepository;
import com.example.jbc.sessions.SessionRepository;
import com.example.jbc.sessions.TrainingSession;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
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
class RegistrationServiceTest {

    private static final UUID SESSION_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PARTICIPANT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID REGISTRATION_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final CreateRegistrationRequest REQUEST = new CreateRegistrationRequest(PARTICIPANT_ID);

    @Mock
    private SessionRepository sessions;
    @Mock
    private ParticipantRepository participants;
    @Mock
    private RegistrationRepository registrations;
    @Mock
    private TrainingSession session;
    @Mock
    private Participant participant;
    @Mock
    private Registration saved;
    @InjectMocks
    private RegistrationService service;

    @Test
    void missingSessionTakesPrecedenceOverParticipantLookup() {
        when(sessions.findByIdForUpdate(SESSION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(SESSION_ID, REQUEST))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.getCode()).isEqualTo("SESSION_NOT_FOUND");
                });
        verifyNoInteractions(participants, registrations);
    }

    @Test
    void missingParticipantTakesPrecedenceOverDuplicateAndCapacityChecks() {
        when(sessions.findByIdForUpdate(SESSION_ID)).thenReturn(Optional.of(session));
        when(participants.findById(PARTICIPANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(SESSION_ID, REQUEST))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.getCode()).isEqualTo("PARTICIPANT_NOT_FOUND");
                });
        verifyNoInteractions(registrations);
    }

    @Test
    void rejectsDuplicatesBeforeCountingCapacityOrInserting() {
        existingReferences();
        when(registrations.existsBySessionIdAndParticipantId(SESSION_ID, PARTICIPANT_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.create(SESSION_ID, REQUEST))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getCode()).isEqualTo("DUPLICATE_REGISTRATION");
                });
        verify(registrations, never()).countBySessionId(any());
        verify(registrations, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @ValueSource(longs = {2, 3})
    void rejectsFullOrOverfullSessionsWithoutInserting(long count) {
        availableReferences(count);

        assertThatThrownBy(() -> service.create(SESSION_ID, REQUEST))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getCode()).isEqualTo("SESSION_FULL");
                });
        verify(registrations, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1})
    void permitsTheFirstAndLastAvailableSlotsAndReturnsTheRegistrationId(long count) {
        availableReferences(count);
        when(participant.getId()).thenReturn(PARTICIPANT_ID);
        when(registrations.saveAndFlush(any())).thenReturn(saved);
        when(saved.getId()).thenReturn(REGISTRATION_ID);

        var response = service.create(SESSION_ID, REQUEST);

        var order = inOrder(sessions, participants, registrations);
        order.verify(sessions).findByIdForUpdate(SESSION_ID);
        order.verify(participants).findById(PARTICIPANT_ID);
        order.verify(registrations).existsBySessionIdAndParticipantId(SESSION_ID, PARTICIPANT_ID);
        order.verify(registrations).countBySessionId(SESSION_ID);
        var inserted = ArgumentCaptor.forClass(Registration.class);
        order.verify(registrations).saveAndFlush(inserted.capture());
        assertThat(inserted.getValue().getSession()).isSameAs(session);
        assertThat(inserted.getValue().getParticipant()).isSameAs(participant);
        assertThat(response).isEqualTo(new RegistrationResponse(REGISTRATION_ID, SESSION_ID, PARTICIPANT_ID));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void translatesTheNamedUniqueConstraintEvenThroughAWrappedCause(boolean wrapped) {
        availableReferences(0);
        var violation = new ConstraintViolationException("duplicate", new SQLException("duplicate", "23505"),
                "uq_registrations_session_participant");
        var failure = new DataIntegrityViolationException("failed insert",
                wrapped ? new IllegalStateException("wrapped", violation) : violation);
        when(registrations.saveAndFlush(any())).thenThrow(failure);

        assertThatThrownBy(() -> service.create(SESSION_ID, REQUEST))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getCode()).isEqualTo("DUPLICATE_REGISTRATION");
                    assertThat(exception.getMessage()).isEqualTo("Participant is already registered for this session.");
                });
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"fk_registrations_session", "another_unique_constraint"})
    void doesNotMisclassifyOtherOrUnnamedConstraintsAsDuplicates(String constraint) {
        availableReferences(0);
        var failure = new DataIntegrityViolationException("failed insert",
                new ConstraintViolationException("other failure", new SQLException("other", "23503"), constraint));
        when(registrations.saveAndFlush(any())).thenThrow(failure);

        assertThatThrownBy(() -> service.create(SESSION_ID, REQUEST)).isSameAs(failure);
    }

    @Test
    void preservesAnIntegrityFailureWithoutConstraintMetadata() {
        availableReferences(0);
        var failure = new DataIntegrityViolationException("unexpected failure");
        when(registrations.saveAndFlush(any())).thenThrow(failure);

        assertThatThrownBy(() -> service.create(SESSION_ID, REQUEST)).isSameAs(failure);
    }

    private void existingReferences() {
        when(sessions.findByIdForUpdate(SESSION_ID)).thenReturn(Optional.of(session));
        when(participants.findById(PARTICIPANT_ID)).thenReturn(Optional.of(participant));
    }

    private void availableReferences(long count) {
        existingReferences();
        when(registrations.existsBySessionIdAndParticipantId(SESSION_ID, PARTICIPANT_ID)).thenReturn(false);
        when(registrations.countBySessionId(SESSION_ID)).thenReturn(count);
        when(session.getCapacity()).thenReturn(2);
    }
}
