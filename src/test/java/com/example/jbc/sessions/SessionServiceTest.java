package com.example.jbc.sessions;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.example.jbc.coaches.Coach;
import com.example.jbc.coaches.CoachRepository;
import com.example.jbc.common.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
class SessionServiceTest {

    private static final UUID COACH_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final Instant START = Instant.parse("2026-10-05T14:00:00Z");
    private static final Instant END = START.plusSeconds(3600);

    @Mock
    private CoachRepository coaches;

    @Mock
    private SessionRepository sessions;

    @Mock
    private Coach coach;

    @InjectMocks
    private SessionService service;

    @Test
    void locksCoachBeforeCheckingOverlapAndPersistsTheCheckedMicrosecondWindow() {
        var start = Instant.parse("2026-10-05T14:00:00.123456789Z");
        var end = Instant.parse("2026-10-05T15:00:00.987654321Z");
        var storedStart = Instant.parse("2026-10-05T14:00:00.123456Z");
        var storedEnd = Instant.parse("2026-10-05T15:00:00.987654Z");
        when(coaches.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));
        when(coach.getId()).thenReturn(COACH_ID);
        when(sessions.save(any(TrainingSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.create(request(start, end));

        var order = inOrder(coaches, sessions);
        order.verify(coaches).findByIdForUpdate(COACH_ID);
        order.verify(sessions).existsByCoachIdAndStartTimeLessThanAndEndTimeGreaterThan(COACH_ID, storedEnd, storedStart);
        var inserted = ArgumentCaptor.forClass(TrainingSession.class);
        order.verify(sessions).save(inserted.capture());
        assertThat(inserted.getValue().getCoach()).isSameAs(coach);
        assertThat(inserted.getValue().getStartTime()).isEqualTo(storedStart);
        assertThat(inserted.getValue().getEndTime()).isEqualTo(storedEnd);
        assertThat(response.coachId()).isEqualTo(COACH_ID);
        assertThat(response.startTime()).isEqualTo(storedStart);
        assertThat(response.endTime()).isEqualTo(storedEnd);
        assertThat(response.capacity()).isEqualTo(10);
        assertThat(response.location()).isEqualTo("Court A");
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-05T13:00:00Z", "2026-10-05T14:00:00Z", "2026-10-05T14:00:00.000000999Z"})
    void rejectsInvalidOrSubMicrosecondWindowsBeforeCheckingCoach(String end) {
        assertThatThrownBy(() -> service.create(request(START, Instant.parse(end))))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getCode()).isEqualTo("INVALID_REQUEST");
                });
        verifyNoInteractions(coaches, sessions);
    }

    @Test
    void missingCoachTakesPrecedenceOverOverlap() {
        when(coaches.findByIdForUpdate(COACH_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(request(START, END)))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.getCode()).isEqualTo("COACH_NOT_FOUND");
                });
        verifyNoInteractions(sessions);
    }

    @Test
    void rejectsAnOverlapWithoutInsertingASession() {
        when(coaches.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(coach));
        when(sessions.existsByCoachIdAndStartTimeLessThanAndEndTimeGreaterThan(COACH_ID, END, START)).thenReturn(true);

        assertThatThrownBy(() -> service.create(request(START, END)))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getCode()).isEqualTo("COACH_OVERLAP");
                    assertThat(exception.getMessage()).isEqualTo("Coach already has a session overlapping this interval.");
                });
        verify(sessions, never()).save(any());
    }

    private static CreateSessionRequest request(Instant start, Instant end) {
        return new CreateSessionRequest(COACH_ID, start, end, 10, "Court A");
    }
}
