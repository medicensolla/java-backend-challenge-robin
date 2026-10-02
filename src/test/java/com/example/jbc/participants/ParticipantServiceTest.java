package com.example.jbc.participants;

import java.util.UUID;

import com.example.jbc.common.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ParticipantServiceTest {

    @Mock
    private ParticipantRepository repository;
    @InjectMocks
    private ParticipantService service;

    @Test
    void rejectsAnExistingCombinationWithoutSaving() {
        var request = new CreateParticipantRequest("  SAM   Lee ", "Sam@example.com");
        when(repository.existsByNormalizedNameAndEmail(request.name(), request.email())).thenReturn(true);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getCode()).isEqualTo("DUPLICATE_PARTICIPANT");
                    assertThat(exception.getMessage()).isEqualTo("Participant with this name and email already exists.");
                });
        verify(repository, never()).save(any());
    }

    @Test
    void savesANewCombinationAndPreservesTheSuppliedNameAndEmail() {
        var request = new CreateParticipantRequest("  Sam   Lee ", "Sam@example.com");
        var saved = mock(Participant.class);
        var id = UUID.randomUUID();
        when(repository.save(any(Participant.class))).thenReturn(saved);
        when(saved.getId()).thenReturn(id);
        when(saved.getName()).thenReturn(request.name());
        when(saved.getEmail()).thenReturn(request.email());

        var response = service.create(request);

        var order = inOrder(repository);
        order.verify(repository).existsByNormalizedNameAndEmail(request.name(), request.email());
        var inserted = ArgumentCaptor.forClass(Participant.class);
        order.verify(repository).save(inserted.capture());
        assertThat(inserted.getValue().getName()).isEqualTo(request.name());
        assertThat(inserted.getValue().getEmail()).isEqualTo(request.email());
        assertThat(response).isEqualTo(new ParticipantResponse(id, request.name(), request.email()));
    }
}
