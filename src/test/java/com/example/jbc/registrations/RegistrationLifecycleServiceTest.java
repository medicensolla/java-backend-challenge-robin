package com.example.jbc.registrations;

import java.util.List;
import java.util.UUID;

import com.example.jbc.common.ApiException;
import com.example.jbc.participants.Participant;
import com.example.jbc.participants.ParticipantRepository;
import com.example.jbc.sessions.SessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegistrationLifecycleServiceTest {

    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final UUID REGISTRATION_ID = UUID.randomUUID();
    private static final UUID PARTICIPANT_ID = UUID.randomUUID();

    @Mock
    private SessionRepository sessions;
    @Mock
    private ParticipantRepository participants;
    @Mock
    private RegistrationRepository registrations;
    @Mock
    private Registration registration;
    @Mock
    private Participant participant;
    @InjectMocks
    private RegistrationService service;

    @Test
    void missingSessionPreventsListingRegistrations() {
        assertThatThrownBy(() -> service.listParticipants(SESSION_ID))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.getCode()).isEqualTo("SESSION_NOT_FOUND");
                });
        verifyNoInteractions(registrations, participants);
    }

    @Test
    void existingEmptySessionReturnsAnEmptyList() {
        when(sessions.existsById(SESSION_ID)).thenReturn(true);
        when(registrations.findAllBySessionIdOrderByParticipantId(SESSION_ID)).thenReturn(List.of());

        assertThat(service.listParticipants(SESSION_ID)).isEmpty();
    }

    @Test
    void mapsParticipantDetailsAndTheRegistrationIdAfterCheckingTheSession() {
        when(sessions.existsById(SESSION_ID)).thenReturn(true);
        when(registrations.findAllBySessionIdOrderByParticipantId(SESSION_ID)).thenReturn(List.of(registration));
        when(registration.getParticipant()).thenReturn(participant);
        when(registration.getId()).thenReturn(REGISTRATION_ID);
        when(participant.getId()).thenReturn(PARTICIPANT_ID);
        when(participant.getName()).thenReturn("Sam");
        when(participant.getEmail()).thenReturn("sam@example.com");

        assertThat(service.listParticipants(SESSION_ID))
                .containsExactly(new RegisteredParticipantResponse(PARTICIPANT_ID, "Sam", "sam@example.com", REGISTRATION_ID));
        var order = inOrder(sessions, registrations);
        order.verify(sessions).existsById(SESSION_ID);
        order.verify(registrations).findAllBySessionIdOrderByParticipantId(SESSION_ID);
        verifyNoInteractions(participants);
    }

    @Test
    void missingSessionTakesPrecedenceOverCancellation() {
        assertThatThrownBy(() -> service.cancel(SESSION_ID, REGISTRATION_ID))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.getCode()).isEqualTo("SESSION_NOT_FOUND");
                });
        verifyNoInteractions(registrations, participants);
    }

    @Test
    void cancellationUsesBothIdsAndDoesNotDeleteTheParticipant() {
        when(sessions.existsById(SESSION_ID)).thenReturn(true);
        when(registrations.deleteBySessionAndRegistrationId(SESSION_ID, REGISTRATION_ID)).thenReturn(1);

        service.cancel(SESSION_ID, REGISTRATION_ID);

        var order = inOrder(sessions, registrations);
        order.verify(sessions).existsById(SESSION_ID);
        order.verify(registrations).deleteBySessionAndRegistrationId(SESSION_ID, REGISTRATION_ID);
        verifyNoInteractions(participants);
    }

    @Test
    void noDeletedRowMeansTheRegistrationWasNotFoundInThisSession() {
        when(sessions.existsById(SESSION_ID)).thenReturn(true);
        when(registrations.deleteBySessionAndRegistrationId(SESSION_ID, REGISTRATION_ID)).thenReturn(0);

        assertThatThrownBy(() -> service.cancel(SESSION_ID, REGISTRATION_ID))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.getCode()).isEqualTo("REGISTRATION_NOT_FOUND");
                    assertThat(exception.getMessage()).isEqualTo("Registration was not found under this session.");
                });
    }
}
