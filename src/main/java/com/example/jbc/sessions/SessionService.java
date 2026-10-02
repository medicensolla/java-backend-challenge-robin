package com.example.jbc.sessions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.UUID;

import com.example.jbc.coaches.CoachRepository;
import com.example.jbc.common.ApiException;
import com.example.jbc.common.PageResponse;
import com.example.jbc.registrations.RegistrationRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SessionService {

    private static final String REGISTRATION_SESSION_CONSTRAINT = "fk_registrations_session";

    private final CoachRepository coaches;
    private final SessionRepository sessions;
    private final RegistrationRepository registrations;

    @Transactional
    public SessionResponse create(CreateSessionRequest request) {
        var start = request.startTime().truncatedTo(ChronoUnit.MICROS);
        var end = request.endTime().truncatedTo(ChronoUnit.MICROS);
        if (!start.isBefore(end)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                    "startTime must be before endTime at microsecond precision.");
        }

        var coach = coaches.findByIdForUpdate(request.coachId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "COACH_NOT_FOUND", "Coach was not found."));
        if (sessions.existsByCoachIdAndStartTimeLessThanAndEndTimeGreaterThan(request.coachId(), end, start)) {
            throw new ApiException(HttpStatus.CONFLICT, "COACH_OVERLAP",
                    "Coach already has a session overlapping this interval.");
        }

        var session = sessions.save(new TrainingSession(coach, start, end, request.capacity(), request.location()));
        return toResponse(session);
    }

    @Transactional(readOnly = true)
    public PageResponse<SessionResponse> list(UUID coachId, Instant from, Instant to, int page, int size) {
        var lower = from == null ? null : from.truncatedTo(ChronoUnit.MICROS);
        var upper = to == null ? null : to.truncatedTo(ChronoUnit.MICROS);
        if (lower != null && upper != null && !lower.isBefore(upper)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                    "from must be before to at microsecond precision.");
        }

        var matches = sessions.findAll((root, query, builder) -> {
            var predicates = new ArrayList<Predicate>();
            if (coachId != null) {
                predicates.add(builder.equal(root.get("coach").get("id"), coachId));
            }
            if (lower != null) {
                predicates.add(builder.greaterThan(root.get("endTime"), lower));
            }
            if (upper != null) {
                predicates.add(builder.lessThan(root.get("startTime"), upper));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        }, PageRequest.of(page, size, Sort.by("startTime", "id")));
        return PageResponse.from(matches.map(this::toResponse));
    }

    @Transactional
    public void delete(UUID sessionId) {
        if (!sessions.existsById(sessionId)) {
            throw sessionNotFound();
        }
        if (registrations.existsBySessionId(sessionId)) {
            throw sessionHasRegistrations();
        }
        try {
            if (sessions.deleteSessionById(sessionId) == 0) {
                throw sessionNotFound();
            }
        } catch (DataIntegrityViolationException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation
                        && REGISTRATION_SESSION_CONSTRAINT.equals(violation.getConstraintName())) {
                    throw sessionHasRegistrations();
                }
            }
            throw exception;
        }
    }

    private ApiException sessionNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "Session was not found.");
    }

    private ApiException sessionHasRegistrations() {
        return new ApiException(HttpStatus.CONFLICT, "SESSION_HAS_REGISTRATIONS",
                "Cancel all registrations before deleting this session.");
    }

    private SessionResponse toResponse(TrainingSession session) {
        return new SessionResponse(session.getId(), session.getCoach().getId(), session.getStartTime(), session.getEndTime(),
                session.getCapacity(), session.getLocation());
    }
}
