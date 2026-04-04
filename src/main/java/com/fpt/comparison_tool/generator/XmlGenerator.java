package com.fpt.comparison_tool.generator;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.ser.ToXmlGenerator;
import com.fpt.comparison_tool.model.TestGroup;
import com.fpt.comparison_tool.model.TestSuite;

import java.io.OutputStream;

/**
 * Serializes a {@link TestSuite} or a single {@link TestGroup} to XML
 * using Jackson {@link XmlMapper}. No manual DOM — POJO annotations drive output.
 */
public class XmlGenerator {

    private final XmlMapper xmlMapper;

    public XmlGenerator() {
        this.xmlMapper = XmlMapper.builder()
                .configure(ToXmlGenerator.Feature.WRITE_XML_DECLARATION, true)
                .build();
    }

    public void generate(TestSuite suite, OutputStream out) throws Exception {
        xmlMapper.writerWithDefaultPrettyPrinter().writeValue(out, suite);
    }

    /** Export a single TestGroup as a standalone XML file. */
    public void generateGroup(TestGroup group, OutputStream out) throws Exception {
        xmlMapper.writerWithDefaultPrettyPrinter().writeValue(out, group);
    }
}
    /** Export suite + task results combined as XML. */
    public void generateWithResults(com.fpt.comparison_tool.model.TestSuite suite,
                                    com.fpt.comparison_tool.model.ExecutionTask task,
                                    OutputStream out) throws Exception {
        // Wrap results into a container object for XML serialization
        TaskResultExport export = new TaskResultExport(suite, task);
        xmlMapper.writerWithDefaultPrettyPrinter().writeValue(out, export);
    }

    /** Simple wrapper for XML export combining suite + task results */
    public static class TaskResultExport {
        public final com.fpt.comparison_tool.model.TestSuite suite;
        public final com.fpt.comparison_tool.model.ExecutionTask task;
        public TaskResultExport(com.fpt.comparison_tool.model.TestSuite s,
                                com.fpt.comparison_tool.model.ExecutionTask t) {
            this.suite = s; this.task = t;
        }
    }
}
