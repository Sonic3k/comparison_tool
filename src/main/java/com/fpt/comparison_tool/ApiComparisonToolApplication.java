package com.fpt.comparison_tool;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

@SpringBootApplication
public class ApiComparisonToolApplication {
	public static void main(String[] args) {
        // "ci-run" as first argument = Mode B CLI: boot without the web
        // server, run the suite (CiCliRunner), exit with the run's code.
        if (args.length > 0 && "ci-run".equals(args[0])) {
            System.exit(SpringApplication.exit(
                    new SpringApplicationBuilder(ApiComparisonToolApplication.class)
                            .web(WebApplicationType.NONE)
                            .run(args)));
        }
        SpringApplication.run(ApiComparisonToolApplication.class, args);
    }
}
