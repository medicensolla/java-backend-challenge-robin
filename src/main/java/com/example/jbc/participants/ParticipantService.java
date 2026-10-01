package com.example.jbc.participants;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ParticipantService {

    private final ParticipantRepository repository;

    @Transactional
    public ParticipantResponse create(CreateParticipantRequest request) {
        var participant = repository.save(new Participant(request.name(), request.email()));
        return new ParticipantResponse(participant.getId(), participant.getName(), participant.getEmail());
    }
}
