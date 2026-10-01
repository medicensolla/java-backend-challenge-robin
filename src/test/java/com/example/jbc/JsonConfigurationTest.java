package com.example.jbc;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.boot.test.json.JacksonTester;

import static org.assertj.core.api.Assertions.assertThat;

@JsonTest
class JsonConfigurationTest {

    @Autowired
    private JacksonTester<Instant> json;

    @Test
    void serializesInstantsAsIso8601InUtc() throws Exception {
        var instant = Instant.parse("2026-10-05T14:00:00Z");

        assertThat(json.write(instant).getJson()).isEqualTo("\"2026-10-05T14:00:00Z\"");
    }

    @Test
    void deserializesEquivalentOffsetsToTheSameInstant() throws Exception {
        var utc = json.parseObject("\"2026-10-05T14:00:00Z\"");
        var offset = json.parseObject("\"2026-10-05T10:00:00-04:00\"");

        assertThat(offset).isEqualTo(utc);
    }
}
