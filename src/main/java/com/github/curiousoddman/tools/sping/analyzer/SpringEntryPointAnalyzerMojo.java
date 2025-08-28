package com.github.curiousoddman.tools.sping.analyzer;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Mojo(name = "scan", defaultPhase = LifecyclePhase.VERIFY, requiresDependencyResolution = ResolutionScope.COMPILE)
public class SpringEntryPointAnalyzerMojo extends AbstractMojo {
    private static final Set<String> LIFECYCLE_INTERFACES = Set.of(
            "org.springframework.boot.ApplicationRunner",
            "org.springframework.boot.CommandLineRunner",
            "org.springframework.beans.factory.InitializingBean",
            "org.springframework.beans.factory.Disposableßean",
            "org.springframework.context.SmartLifecycle"
    );

    private static final Set<String> ANNOTATIONS = Set.of(
            "org.springframework.stereotype.Controller",
            "org.springframework.context.annotation.Configuration",
            "jakarta.annotation.PostConstruct",
            "jakarta.annotation.PreDestroy",
            "org.springframework.scheduling.annotation.Scheduled",
            "org.springframework.context.event.EventListener"
    );

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(property = "spring-boot-scanner.interfaces", readonly = true, defaultValue = "")
    private String[] interfaces;

    @Parameter(property = "spring-boot-scanner.annotations", readonly = true, defaultValue = "")
    private String[] annotations;

    @Override
    public void execute() throws MojoExecutionException {
        getLog().info("User configured interfaces " + Arrays.toString(interfaces));
        getLog().info("User configured annotations " + Arrays.toString(annotations));
        Map<String, List<String>> result = new SpringEntryPointAnalyzer(
                Stream.concat(LIFECYCLE_INTERFACES.stream(), Arrays.stream(this.interfaces)).collect(Collectors.toSet()),
                Stream.concat(ANNOTATIONS.stream(), Arrays.stream(this.annotations)).collect(Collectors.toSet()),
                project,
                getLog()
        ).execute();

        getLog().info("Found " + result.size() + " spring launch entry points");
        result.forEach((className, triggers) -> {
            getLog().info("Class: " + className);
            triggers.forEach(trigger -> getLog().info(" - " + trigger));
        });
    }
}

