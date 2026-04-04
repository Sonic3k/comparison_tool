package com.fpt.comparison_tool.service;

import com.fpt.comparison_tool.model.TestSuite;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global registry of loaded TestSuites.
 * Replaces SessionService — suites are now app-scoped, not session-scoped.
 * Thread-safe: multiple users/requests can import/query simultaneously.
 */
@Service
public class SuiteRegistry {

    private final Map<String, TestSuite> suites = new ConcurrentHashMap<>();

    /** Load (or replace) a suite. Returns its ID. */
    public String register(TestSuite suite) {
        String id = suite.getId();
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
            suite.setId(id);
        }
        suites.put(id, suite);
        return id;
    }

    public Optional<TestSuite> get(String suiteId) {
        return Optional.ofNullable(suites.get(suiteId));
    }

    public boolean exists(String suiteId) {
        return suites.containsKey(suiteId);
    }

    public void remove(String suiteId) {
        suites.remove(suiteId);
    }

    public Collection<TestSuite> getAll() {
        return Collections.unmodifiableCollection(suites.values());
    }

    public int count() {
        return suites.size();
    }
}
