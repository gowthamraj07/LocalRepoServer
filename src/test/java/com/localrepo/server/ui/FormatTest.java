package com.localrepo.server.ui;

import com.localrepo.server.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FormatTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-07T10:00:00Z"));
    private final Format format = new Format(clock);

    @Test
    void formatsSizesTheSameWhateverTheSystemLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("nl-BE"));
            assertEquals("2.2 KB", format.bytes(2250));
            assertEquals("512 B", format.bytes(512));
            assertEquals("1.5 GB", format.bytes(1610612736L));
            assertEquals("75%", format.percent(0.75));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void describesHowLongAgo() {
        Instant then = clock.instant();
        clock.advance(Duration.ofMinutes(5));

        assertEquals("5 min ago", format.ago(then));
        assertEquals("never", format.ago(null));
    }
}
