package com.example.jbc.participants;

import com.example.jbc.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ParticipantService {

    private final ParticipantRepository repository;

    @Transactional
    public ParticipantResponse create(CreateParticipantRequest request) {
        if (repository.existsByNormalizedNameAndEmail(request.name(), request.email())) {
            throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_PARTICIPANT",
                    "Participant with this name and email already exists.");
        }
        var participant = repository.save(new Participant(request.name(), request.email()));
        return new ParticipantResponse(participant.getId(), participant.getName(), participant.getEmail());
    }
}
