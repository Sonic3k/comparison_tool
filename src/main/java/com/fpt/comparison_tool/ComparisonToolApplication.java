package com.fpt.comparison_tool;

import com.fpt.comparison_tool.cli.Cli;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Entry point. A first argument naming a command (run, convert, validate, report, export, help) runs the command
 * line mode; otherwise the web UI starts, optionally with a suite file opened (Save then writes back to that file).
 */
@SpringBootApplication
public class ComparisonToolApplication {

    public static void main(String[] args) {
        if (args.length > 0 && Cli.isCommand(args[0])) {
            System.exit(new Cli(System.out, System.err).execute(args));
        }
        List<String> rest = new ArrayList<>();
        for (String a : args) {
            if (!a.startsWith("-") && System.getProperty("ct.startup-file") == null) {
                Path p = Paths.get(a).toAbsolutePath();
                if (!Files.isRegularFile(p)) {
                    System.err.println("Suite file not found: " + p);
                    System.exit(Cli.FAILURE);
                }
                System.setProperty("ct.startup-file", p.toString());
            } else {
                rest.add(a);
            }
        }
        SpringApplication.run(ComparisonToolApplication.class, rest.toArray(String[]::new));
    }
}
