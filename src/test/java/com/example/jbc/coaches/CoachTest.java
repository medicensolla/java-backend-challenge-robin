package com.example.jbc.coaches;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class CoachTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', emptyValue = "", textBlock = """
            Alex Rivera | Alex | Rivera | Alex Rivera
            Prince | Prince | '' | Prince
            Alex Rivera Stone | Alex | Rivera Stone | Alex Rivera Stone
            '  Alex   Rivera  ' | Alex | Rivera | Alex Rivera
            '  Prince  ' | Prince | '' | Prince
            María del Río | María | del Río | María del Río
            李 小龍 | 李 | 小龍 | 李 小龍
            """)
    void splitsNamesAndRecomposesThePublicName(String input, String first, String last, String publicName) {
        var coach = new Coach(input, "alex@example.com");

        assertThat(coach.getFirstName()).isEqualTo(first);
        assertThat(coach.getLastName()).isEqualTo(last);
        assertThat(coach.getName()).isEqualTo(publicName);
        assertThat(coach.getEmail()).isEqualTo("alex@example.com");
    }

    @ParameterizedTest
    @CsvSource({"9", "10", "11", "12", "13"})
    void normalizesTabsAndLineWhitespaceConsistently(int character) {
        var whitespace = String.valueOf((char) character);
        var coach = new Coach(whitespace + "Alex" + whitespace + " Rivera " + whitespace, "alex@example.com");

        assertThat(coach.getFirstName()).isEqualTo("Alex");
        assertThat(coach.getLastName()).isEqualTo("Rivera");
        assertThat(coach.getName()).isEqualTo("Alex Rivera");
    }
}
