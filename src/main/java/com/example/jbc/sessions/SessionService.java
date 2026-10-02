package com.example.jbc.sessions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.example.jbc.coaches.CoachRepository;
import com.example.jbc.common.ApiException;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SessionService {

    private final CoachRepository coaches;
    private final SessionRepository sessions;

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
    public List<SessionResponse> list(UUID coachId, Instant from, Instant to) {
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
        }, Sort.by("startTime", "id"));
        return matches.stream().map(this::toResponse).toList();
    }

    private SessionResponse toResponse(TrainingSession session) {
        return new SessionResponse(session.getId(), session.getCoach().getId(), session.getStartTime(), session.getEndTime(),
                session.getCapacity(), session.getLocation());
    }
}
