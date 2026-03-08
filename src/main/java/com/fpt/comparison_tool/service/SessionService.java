package com.fpt.comparison_tool.service;

import com.fpt.comparison_tool.dto.ExecutionProgress;
import com.fpt.comparison_tool.model.TestSuite;
import org.springframework.stereotype.Service;
import org.springframework.web.context.annotation.SessionScope;

import java.io.Serializable;

@Service
@SessionScope
public class SessionService implements Serializable {

    private TestSuite testSuite;
    private final ExecutionProgress progress = new ExecutionProgress();

    public boolean hasSuite() {
        return testSuite != null;
    }

    public void loadSuite(TestSuite suite) {
        this.testSuite = suite;
        this.progress.reset();
    }

    public void clearSuite() {
        this.testSuite = null;
        this.progress.reset();
    }

    public TestSuite getTestSuite() { return testSuite; }
    public ExecutionProgress getProgress() { return progress; }
}