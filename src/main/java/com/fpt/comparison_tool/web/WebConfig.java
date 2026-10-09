package com.fpt.comparison_tool.web;

import com.fpt.comparison_tool.engine.RunEngine;
import com.fpt.comparison_tool.suite.Json;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/** Web wiring: one run engine, the shared JSON settings, optional password, startup message. */
@Configuration
public class WebConfig {

    private final Environment env;

    public WebConfig(Environment env) {
        this.env = env;
    }

    @Bean(destroyMethod = "close")
    public RunEngine runEngine() {
        return new RunEngine();
    }

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jsonSettings() {
        return b -> b.postConfigurer(m -> {
            m.configure(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true);
            m.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            m.configure(com.fasterxml.jackson.databind.cfg.JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false);
            m.configure(com.fasterxml.jackson.core.JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN, true);
        });
    }

    /** With APP_PASSWORD set (for a shared pod), every request needs HTTP basic auth with that password. */
    @Bean
    public FilterRegistrationBean<Filter> passwordFilter() {
        String password = System.getenv("APP_PASSWORD");
        FilterRegistrationBean<Filter> reg = new FilterRegistrationBean<>();
        reg.setOrder(1);
        reg.setFilter((req, res, chain) -> {
            if (password == null || password.isBlank()) {
                chain.doFilter(req, res);
                return;
            }
            HttpServletRequest r = (HttpServletRequest) req;
            String header = r.getHeader("Authorization");
            boolean ok = false;
            if (header != null && header.startsWith("Basic ")) {
                try {
                    String decoded = new String(Base64.getDecoder().decode(header.substring(6)), StandardCharsets.UTF_8);
                    String given = decoded.substring(decoded.indexOf(':') + 1);
                    ok = MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), password.getBytes(StandardCharsets.UTF_8));
                } catch (Exception ignored) {
                    ok = false;
                }
            }
            if (ok) {
                chain.doFilter(req, res);
            } else {
                HttpServletResponse w = (HttpServletResponse) res;
                w.setHeader("WWW-Authenticate", "Basic realm=\"Comparison Tool\"");
                w.sendError(401);
            }
        });
        return reg;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ready() {
        String port = env.getProperty("local.server.port", env.getProperty("server.port", "8080"));
        String file = System.getProperty("ct.startup-file");
        System.out.println();
        System.out.println("Comparison Tool is running: http://localhost:" + port);
        if (file != null) System.out.println("Suite: " + file + " (Save writes back to this file, results go to results/ next to it)");
        System.out.println();
        Json.MAPPER.getFactory();
        if (shouldOpenBrowser()) openBrowser("http://localhost:" + port + "/");
    }

    /** Opens the default browser on a desktop machine; never on a pod (PORT or Kubernetes variables set). */
    private static boolean shouldOpenBrowser() {
        String flag = System.getProperty("ct.open-browser");
        if (flag != null) return Boolean.parseBoolean(flag);
        if (System.getenv("PORT") != null || System.getenv("KUBERNETES_SERVICE_HOST") != null) return false;
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        return os.contains("win") || os.contains("mac");
    }

    private static void openBrowser(String url) {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        java.util.List<String> cmd = os.contains("win") ? java.util.List.of("rundll32", "url.dll,FileProtocolHandler", url)
                : os.contains("mac") ? java.util.List.of("open", url) : java.util.List.of("xdg-open", url);
        try {
            new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        } catch (java.io.IOException | RuntimeException ignored) {
            // no browser available: the URL is printed above
        }
    }
}
