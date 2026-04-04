package com.fpt.comparison_tool.service;

import com.fpt.comparison_tool.model.TestSuite;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * App-scoped registry of TestSuites.
 * Each suite owns its own TC definitions + results.
 */
@Service
public class SuiteRegistry {

    private final Map<String, TestSuite> suites = new ConcurrentHashMap<>();

    public String register(TestSuite suite) {
        String id = suite.getId();
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
            suite.setId(id);
        }
        suites.put(id, suite);
        return id;
    }

    public Optional<TestSuite> get(String id) {
        return Optional.ofNullable(suites.get(id));
    }

    public boolean exists(String id) { return suites.containsKey(id); }

    public void remove(String id) { suites.remove(id); }

    public Collection<TestSuite> getAll() {
        return Collections.unmodifiableCollection(suites.values());
    }
}
