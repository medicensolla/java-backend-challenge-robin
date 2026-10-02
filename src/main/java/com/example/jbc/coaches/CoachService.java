package com.example.jbc.coaches;

import com.example.jbc.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CoachService {

    private final CoachRepository repository;

    @Transactional
    public CoachResponse create(CreateCoachRequest request) {
        if (repository.existsByNormalizedNameAndEmail(request.name(), request.email())) {
            throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_COACH",
                    "Coach with this name and email already exists.");
        }
        var coach = repository.save(new Coach(request.name(), request.email()));
        return new CoachResponse(coach.getId(), coach.getName(), coach.getEmail());
    }
}
