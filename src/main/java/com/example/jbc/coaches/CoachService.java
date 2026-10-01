package com.example.jbc.coaches;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CoachService {

    private final CoachRepository repository;

    @Transactional
    public CoachResponse create(CreateCoachRequest request) {
        var coach = repository.save(new Coach(request.name(), request.email()));
        return new CoachResponse(coach.getId(), coach.getName(), coach.getEmail());
    }
}
