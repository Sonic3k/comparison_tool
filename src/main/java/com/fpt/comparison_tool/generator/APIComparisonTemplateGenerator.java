package com.fpt.comparison_tool.generator;

import com.fpt.comparison_tool.model.TestSuite;

import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * Entry point for generating sample test suite template files.
 *
 * Produces:
 *   - API_Comparison_Template.xlsx
 *   - API_Comparison_Template.xml
 *
 * Run as a plain Java main — no Spring context needed.
 */
public class APIComparisonTemplateGenerator {

    private static final String EXCEL_OUTPUT = "API_Comparison_Template.xlsx";
    private static final String XML_OUTPUT   = "API_Comparison_Template.xml";

    public static void main(String[] args) {
        System.out.println("🔧 Building sample test suite...");
        TestSuite suite = SampleDataBuilder.build();

        generateExcel(suite);
        generateXml(suite);

        System.out.println();
        System.out.println("✅ Done! Files generated:");
        System.out.println("   📊 " + EXCEL_OUTPUT);
        System.out.println("   📄 " + XML_OUTPUT);
        System.out.println();
        System.out.println("Sheet structure (Excel):");
        System.out.println("   • Settings");
        System.out.println("   • Environment Setting");
        System.out.println("   • Auth Profiles");
        suite.getTestGroups().forEach(g -> System.out.println("   • " + g.getSheetName()));
    }

    public static void generateExcel(TestSuite suite) {
        System.out.println("📊 Generating Excel...");
        try (OutputStream out = new FileOutputStream(EXCEL_OUTPUT)) {
            new ExcelGenerator().generate(suite, out);
            System.out.println("   ✓ " + EXCEL_OUTPUT);
        } catch (Exception e) {
            System.err.println("   ✗ Excel generation failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void generateXml(TestSuite suite) {
        System.out.println("📄 Generating XML...");
        try (OutputStream out = new FileOutputStream(XML_OUTPUT)) {
            new XmlGenerator().generate(suite, out);
            System.out.println("   ✓ " + XML_OUTPUT);
        } catch (Exception e) {
            System.err.println("   ✗ XML generation failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}