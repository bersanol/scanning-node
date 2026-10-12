package org.example.scanning.manager;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@SpringBootApplication
public class ManagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ManagerApplication.class, args);
    }

    /** Release artifacts (node JARs, nmap packages, installer) are plain files served read-only at /releases/. */
    @Bean
    WebMvcConfigurer releases(@Value("${scanning.releases-dir:releases}") Path dir) throws IOException {
        Files.createDirectories(dir);
        String location = dir.toAbsolutePath().toUri().toString();
        return new WebMvcConfigurer() {
            @Override
            public void addResourceHandlers(ResourceHandlerRegistry registry) {
                registry.addResourceHandler("/releases/**").addResourceLocations(location.endsWith("/") ? location : location + "/");
            }
        };
    }
}