package com.tindapp.util;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public final class DateTimeUtils {
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private DateTimeUtils() {
    }

    public static String formatToIso(final LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.atOffset(ZoneOffset.UTC).format(ISO_FORMATTER);
    }

    public static LocalDateTime parseFromIso(final String isoString) {
        if (isoString == null || isoString.isBlank()) {
            return null;
        }
        return OffsetDateTime.parse(isoString, ISO_FORMATTER).toLocalDateTime();
    }

    public static String nowAsIso() {
        return OffsetDateTime.now(ZoneOffset.UTC).format(ISO_FORMATTER);
    }
}
