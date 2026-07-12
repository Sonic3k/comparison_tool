package com.fpt.comparison_tool.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.comparison_tool.model.TestGroup;
import com.fpt.comparison_tool.model.TestSuite;
import org.springframework.stereotype.Service;

import java.io.InputStream;

@Service
public class JsonImportService {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public TestSuite importFrom(InputStream in) throws Exception {
        return objectMapper.readValue(in, TestSuite.class);
    }

    public TestGroup importGroup(InputStream in) throws Exception {
        return objectMapper.readValue(in, TestGroup.class);
    }
}
