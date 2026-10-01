package com.example.jbc.coaches;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CoachRepository extends JpaRepository<Coach, UUID> {
}
