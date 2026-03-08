package com.fpt.comparison_tool.service;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fpt.comparison_tool.model.TestGroup;
import com.fpt.comparison_tool.model.TestSuite;
import org.springframework.stereotype.Service;

import java.io.InputStream;

@Service
public class XmlImportService {

    private final XmlMapper xmlMapper = new XmlMapper();

    public TestSuite importFrom(InputStream in) throws Exception {
        return xmlMapper.readValue(in, TestSuite.class);
    }

    /** Import a single TestGroup from a standalone XML file. */
    public TestGroup importGroup(InputStream in) throws Exception {
        return xmlMapper.readValue(in, TestGroup.class);
    }
}