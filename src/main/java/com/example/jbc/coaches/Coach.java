package com.example.jbc.coaches;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "coaches")
public class Coach {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @Column(nullable = false)
    private String email;

    public Coach(String name, String email) {
        var normalized = name.replaceAll("\\s+", " ").replaceAll("^ | $", "");
        var separator = normalized.indexOf(' ');
        this.firstName = separator < 0 ? normalized : normalized.substring(0, separator);
        this.lastName = separator < 0 ? "" : normalized.substring(separator + 1);
        this.email = email;
    }

    public String getName() {
        return lastName.isEmpty() ? firstName : firstName + " " + lastName;
    }
}
