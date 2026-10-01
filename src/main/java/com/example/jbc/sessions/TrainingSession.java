package com.example.jbc.sessions;

import java.time.Instant;
import java.util.UUID;

import com.example.jbc.coaches.Coach;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "sessions")
public class TrainingSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "coach_id", nullable = false)
    private Coach coach;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time", nullable = false)
    private Instant endTime;

    @Column(nullable = false)
    private int capacity;

    @Column(nullable = false)
    private String location;

    protected TrainingSession() {
    }

    public TrainingSession(Coach coach, Instant startTime, Instant endTime, int capacity, String location) {
        this.coach = coach;
        this.startTime = startTime;
        this.endTime = endTime;
        this.capacity = capacity;
        this.location = location;
    }

    public UUID getId() {
        return id;
    }

    public Coach getCoach() {
        return coach;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public int getCapacity() {
        return capacity;
    }

    public String getLocation() {
        return location;
    }
}
