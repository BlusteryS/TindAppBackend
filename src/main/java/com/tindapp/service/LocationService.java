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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class LocationService {

    private static final Logger logger = LoggerFactory.getLogger(LocationService.class);

    private static final String COUNTRIES_RESOURCE = "countries.csv";
    private static final String CITIES_RESOURCE = "cities.csv";

    private static final Locale SEARCH_LOCALE = new Locale("ru");
    private static final Pattern DASH_SPLIT_PATTERN = Pattern.compile("\\s*[-—–]\\s*");
    private static final int DEFAULT_CITY_SEARCH_LIMIT = 100;

    private final List<Country> countries;
    private final Map<String, List<City>> citiesByCountry;

    public LocationService() {
        this.countries = loadCountries();
        this.citiesByCountry = loadCities();
    }

    public List<Country> getCountries() {
        return countries;
    }

    public List<City> getCitiesByCountry(String countryId) {
        if (countryId == null) {
            return Collections.emptyList();
        }

        return citiesByCountry.getOrDefault(countryId, Collections.emptyList());
    }

    public List<City> searchCitiesByCountry(String countryId, String query) {
        return searchCitiesByCountry(countryId, query, DEFAULT_CITY_SEARCH_LIMIT);
    }

    public List<City> searchCitiesByCountry(String countryId, String query, int limit) {
        if (countryId == null || query == null) {
            return Collections.emptyList();
        }

        String normalizedQuery = normalize(query);
        if (normalizedQuery.length() < 3) {
            return Collections.emptyList();
        }

        List<City> cities = citiesByCountry.getOrDefault(countryId, Collections.emptyList());
        if (cities.isEmpty()) {
            return Collections.emptyList();
        }

        List<City> matches = new ArrayList<>();
        HashSet<String> seenNames = new HashSet<>();
        for (City city : cities) {
            String normalizedName = normalize(city.getName());
            if (cityMatchesQuery(city, normalizedQuery) && seenNames.add(normalizedName)) {
                matches.add(city);
                if (limit > 0 && matches.size() >= limit) {
                    break;
                }
            }
        }

        if (matches.isEmpty()) {
            return Collections.emptyList();
        }

        return Collections.unmodifiableList(matches);
    }

    private List<Country> loadCountries() {
        List<Country> loadedCountries = new ArrayList<>();

        try (InputStream inputStream = getResourceAsStream(COUNTRIES_RESOURCE);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

            String line = reader.readLine();

            while ((line = reader.readLine()) != null) {
                String[] parts = splitCsv(line);
                if (parts.length < 2) {
                    continue;
                }

                String id = unquote(parts[0]);
                String name = unquote(parts[1]);

                if (!id.isEmpty() && !name.isEmpty()) {
                    loadedCountries.add(new Country(id, name));
                }
            }
        } catch (IOException e) {
            logger.error("Failed to load countries from {}", COUNTRIES_RESOURCE, e);
        }

        Collator collator = Collator.getInstance(new Locale("ru"));
        loadedCountries.sort(Comparator.comparing(Country::getName, collator));

        return Collections.unmodifiableList(loadedCountries);
    }

    private Map<String, List<City>> loadCities() {
        Map<String, List<City>> loadedCities = new HashMap<>();

        try (InputStream inputStream = getResourceAsStream(CITIES_RESOURCE);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

            String line = reader.readLine();

            while ((line = reader.readLine()) != null) {
                String[] parts = splitCsv(line);
                if (parts.length < 3) {
                    continue;
                }

                String cityId = unquote(parts[0]);
                String countryId = unquote(parts[1]);
                String name = unquote(parts[2]);

                if (cityId.isEmpty() || countryId.isEmpty() || name.isEmpty()) {
                    continue;
                }

                City city = new City(cityId, countryId, name);
                loadedCities
                    .computeIfAbsent(countryId, key -> new ArrayList<>())
                    .add(city);
            }
        } catch (IOException e) {
            logger.error("Failed to load cities from {}", CITIES_RESOURCE, e);
        }

        Collator collator = Collator.getInstance(new Locale("ru"));

        loadedCities.replaceAll((key, value) ->
            value.stream()
                .sorted(Comparator.comparing(City::getName, collator))
                .collect(Collectors.collectingAndThen(Collectors.toList(), Collections::unmodifiableList))
        );

        return Collections.unmodifiableMap(loadedCities);
    }

    private InputStream getResourceAsStream(String resourceName) throws IOException {
        ClassLoader classLoader = getClass().getClassLoader();
        InputStream inputStream = classLoader.getResourceAsStream(resourceName);

        if (inputStream == null) {
            throw new IOException("Resource not found: " + resourceName);
        }

        return inputStream;
    }

    private String[] splitCsv(String line) {
        return line.split(";");
    }

    private String unquote(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(SEARCH_LOCALE);
    }

    private boolean cityMatchesQuery(City city, String normalizedQuery) {
        String normalizedName = normalize(city.getName());
        if (normalizedName.startsWith(normalizedQuery)) {
            return true;
        }

        String[] segments = DASH_SPLIT_PATTERN.split(city.getName());
        for (String segment : segments) {
            String normalizedSegment = normalize(segment);
            if (!normalizedSegment.isEmpty() && normalizedSegment.startsWith(normalizedQuery)) {
                return true;
            }
        }
        return false;
    }

    public static class Country {
        private final String id;
        private final String name;

        public Country(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }
    }

    public static class City {
        private final String id;
        private final String countryId;
        private final String name;

        public City(String id, String countryId, String name) {
            this.id = id;
            this.countryId = countryId;
            this.name = name;
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
