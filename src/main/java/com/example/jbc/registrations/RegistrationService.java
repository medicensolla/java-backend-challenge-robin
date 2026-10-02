package com.example.jbc.registrations;

import java.util.UUID;

import com.example.jbc.common.ApiException;
import com.example.jbc.participants.ParticipantRepository;
import com.example.jbc.sessions.SessionRepository;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RegistrationService {

    private static final String UNIQUE_REGISTRATION_CONSTRAINT = "uq_registrations_session_participant";

    private final SessionRepository sessions;
    private final ParticipantRepository participants;
    private final RegistrationRepository registrations;

    @Transactional
    public RegistrationResponse create(UUID sessionId, CreateRegistrationRequest request) {
        var session = sessions.findById(sessionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "Session was not found."));
        var participant = participants.findById(request.participantId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PARTICIPANT_NOT_FOUND", "Participant was not found."));
        if (registrations.existsBySessionIdAndParticipantId(sessionId, request.participantId())) {
            throw duplicateRegistration();
        }
        if (registrations.countBySessionId(sessionId) >= session.getCapacity()) {
            throw new ApiException(HttpStatus.CONFLICT, "SESSION_FULL", "Session has reached its capacity.");
        }

        Registration registration;
        try {
            registration = registrations.saveAndFlush(new Registration(session, participant));
        } catch (DataIntegrityViolationException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation
                        && UNIQUE_REGISTRATION_CONSTRAINT.equals(violation.getConstraintName())) {
                    throw duplicateRegistration();
                }
            }
            throw exception;
        }
        return new RegistrationResponse(registration.getId(), sessionId, participant.getId());
    }

    private ApiException duplicateRegistration() {
        return new ApiException(HttpStatus.CONFLICT, "DUPLICATE_REGISTRATION",
                "Participant is already registered for this session.");
    }
}
