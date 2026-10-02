package com.example.jbc.coaches;

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
class CoachServiceTest {

    @Mock
    private CoachRepository repository;
    @InjectMocks
    private CoachService service;

    @Test
    void rejectsAnExistingCombinationWithoutSaving() {
        var request = new CreateCoachRequest("  ALEX   Rivera ", "Alex@example.com");
        when(repository.existsByNormalizedNameAndEmail(request.name(), request.email())).thenReturn(true);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getCode()).isEqualTo("DUPLICATE_COACH");
                    assertThat(exception.getMessage()).isEqualTo("Coach with this name and email already exists.");
                });
        verify(repository, never()).save(any());
    }

    @Test
    void savesANewCombinationAndReturnsItsIdAndPublicName() {
        var request = new CreateCoachRequest("  Alex   Rivera ", "Alex@example.com");
        var saved = mock(Coach.class);
        var id = UUID.randomUUID();
        when(repository.save(any(Coach.class))).thenReturn(saved);
        when(saved.getId()).thenReturn(id);
        when(saved.getName()).thenReturn("Alex Rivera");
        when(saved.getEmail()).thenReturn(request.email());

        var response = service.create(request);

        var order = inOrder(repository);
        order.verify(repository).existsByNormalizedNameAndEmail(request.name(), request.email());
        var inserted = ArgumentCaptor.forClass(Coach.class);
        order.verify(repository).save(inserted.capture());
        assertThat(inserted.getValue().getName()).isEqualTo("Alex Rivera");
        assertThat(inserted.getValue().getEmail()).isEqualTo(request.email());
        assertThat(response).isEqualTo(new CoachResponse(id, "Alex Rivera", request.email()));
    }
}
