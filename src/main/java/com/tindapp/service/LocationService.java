package com.tindapp.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class LocationService {

    private static final Logger logger = LoggerFactory.getLogger(LocationService.class);

    private static final String COUNTRIES_RESOURCE = "countries.csv";
    private static final Locale SEARCH_LOCALE = new Locale("ru");
    private static final int DEFAULT_CITY_SEARCH_LIMIT = 100;

    private static volatile LocationService instance;

    private final List<Country> countries;
    private final Map<String, List<City>> citiesByCountry;

    private LocationService() {
        final long totalStart = System.currentTimeMillis();
        logger.info("Starting LocationService initialization...");

        countries = loadCountries();
        logger.info("Countries loaded: {} items", countries.size());

        citiesByCountry = Collections.emptyMap();
        logger.info("City CSV loading disabled: city search is handled by VK API on the frontend");

        logger.info("LocationService initialized in {}ms", System.currentTimeMillis() - totalStart);
    }

    public static LocationService getInstance() {
        if (instance == null) {
            synchronized (LocationService.class) {
                if (instance == null) {
                    instance = new LocationService();
                }
            }
        }
        return instance;
    }

    public List<Country> getCountries() {
        return countries;
    }

    public List<City> getCitiesByCountry(final String countryId) {
        if (countryId == null) return Collections.emptyList();
        final List<City> cities = citiesByCountry.get(countryId);
        return cities != null ? cities : Collections.emptyList();
    }

    public List<City> searchCitiesByCountry(final String countryId, final String query) {
        return searchCitiesByCountry(countryId, query, DEFAULT_CITY_SEARCH_LIMIT);
    }

    public List<City> searchCitiesByCountry(final String countryId, final String query, final int limit) {
        if (countryId == null || query == null || query.length() < 3) {
            return Collections.emptyList();
        }

        final List<City> cities = citiesByCountry.get(countryId);
        if (cities == null || cities.isEmpty()) {
            return Collections.emptyList();
        }

        final String normalizedQuery = normalize(query);
        if (normalizedQuery.length() < 3) {
            return Collections.emptyList();
        }

        final List<City> matches = new ArrayList<>();
        final Set<String> seen = new HashSet<>();

        for (final City city : cities) {
            if (city.matchesQuery(normalizedQuery) && seen.add(city.normalizedName)) {
                matches.add(city);
                if (limit > 0 && matches.size() >= limit) {
                    break;
                }
            }
        }

        return matches;
    }

    private List<Country> loadCountries() {
        final long start = System.currentTimeMillis();
        final List<Country> loaded = new ArrayList<>();
        final Collator collator = Collator.getInstance(SEARCH_LOCALE);

        try (final InputStream is = getResourceAsStream(COUNTRIES_RESOURCE);
             final BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8), 8192)) {

            reader.readLine();            String line;

            while ((line = reader.readLine()) != null) {
                final int idx = line.indexOf(';');
                if (idx == -1) continue;

                final String id = unquote(line, 0, idx);
                final String name = unquote(line, idx + 1, line.length());

                if (!id.isEmpty() && !name.isEmpty()) {
                    loaded.add(new Country(id, name));
                }
            }
        } catch (final IOException e) {
            logger.error("Failed to load countries", e);
        }

        loaded.sort(Comparator.comparing(Country::name, collator));

        return Collections.unmodifiableList(loaded);
    }

    private InputStream getResourceAsStream(final String resourceName) throws IOException {
        final InputStream is = getClass().getClassLoader().getResourceAsStream(resourceName);
        if (is == null) throw new IOException("Resource not found: " + resourceName);
        return is;
    }

    private String unquote(final String line, int start, int end) {
        while (start < end && line.charAt(start) <= ' ') start++;
        while (end > start && line.charAt(end - 1) <= ' ') end--;

        if (end > start && line.charAt(start) == '"' && line.charAt(end - 1) == '"') {
            start++;
            end--;
        }

        return start >= end ? "" : line.substring(start, end);
    }

    private String normalize(final String value) {
        return value == null ? "" : value.trim().toLowerCase(SEARCH_LOCALE);
    }

    public record Country(String id, String name) {
    }

    public static class City {
        private final String id;
        private final String countryId;
        private final String name;

        final String normalizedName;
        private final String[] normalizedSegments;

        public City(final String id, final String countryId, final String name) {
            this.id = id;
            this.countryId = countryId;
            this.name = name;

            normalizedName = name.trim().toLowerCase(SEARCH_LOCALE);

            if (name.contains("-") || name.contains("—") || name.contains("–")) {
                final String[] parts = name.split("\\s*[-—–]\\s*");
                if (parts.length > 1) {
                    final List<String> segments = new ArrayList<>();
                    for (final String part : parts) {
                        final String norm = part.trim().toLowerCase(SEARCH_LOCALE);
                        if (!norm.isEmpty() && !norm.equals(normalizedName)) {
                            segments.add(norm);
                        }
                    }
                    normalizedSegments = segments.isEmpty() ? null : segments.toArray(new String[0]);
                } else {
                    normalizedSegments = null;
                }
            } else {
                normalizedSegments = null;
            }
        }

        boolean matchesQuery(final String normalizedQuery) {
            if (normalizedName.startsWith(normalizedQuery)) {
                return true;
            }

            if (normalizedSegments != null) {
                for (final String segment : normalizedSegments) {
                    if (segment.startsWith(normalizedQuery)) {
                        return true;
                    }
                }
            }

            return false;
        }

        public String getId() {
            return id;
        }

        public String getCountryId() {
            return countryId;
        }

        public String getName() {
            return name;
        }
    }
}