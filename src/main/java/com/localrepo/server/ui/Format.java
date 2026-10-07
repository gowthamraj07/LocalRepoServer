package com.localrepo.server.ui;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/** Formatting helpers for the templates, used as {@code ${@fmt.bytes(x)}}. */
@Component("fmt")
public class Format {

    private final Clock clock;

    public Format(Clock clock) {
        this.clock = clock;
    }

    public String bytes(long bytes) {
        if (bytes < 0) {
            return "?";
        }
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return unit == 0 ? bytes + " B" : String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    public String percent(double fraction) {
        return String.format(Locale.ROOT, "%.0f%%", fraction * 100);
    }

    public int percentOf(long part, long whole) {
        return whole <= 0 ? 0 : (int) Math.min(100, part * 100 / whole);
    }

    /** Lets a dotted Maven group wrap after its dots instead of mid-word. */
    public String breakable(String group) {
        return group == null ? null : group.replace(".", ".\u200B");
    }

    public String ago(Instant instant) {
        if (instant == null) {
            return "never";
        }
        Duration age = Duration.between(instant, clock.instant());
        if (age.toSeconds() < 60) {
            return "just now";
        }
        if (age.toMinutes() < 60) {
            return age.toMinutes() + " min ago";
        }
        if (age.toHours() < 48) {
            return age.toHours() + " h ago";
        }
        return age.toDays() + " days ago";
    }
}
