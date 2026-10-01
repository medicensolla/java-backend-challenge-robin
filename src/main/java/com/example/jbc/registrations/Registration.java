package com.example.jbc.registrations;

import java.util.UUID;

import com.example.jbc.participants.Participant;
import com.example.jbc.sessions.TrainingSession;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "registrations")
public class Registration {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private TrainingSession session;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "participant_id", nullable = false)
    private Participant participant;

    protected Registration() {
    }

    public Registration(TrainingSession session, Participant participant) {
        this.session = session;
        this.participant = participant;
    }

    public UUID getId() {
        return id;
    }

    public TrainingSession getSession() {
        return session;
    }

    public Participant getParticipant() {
        return participant;
    }
}
