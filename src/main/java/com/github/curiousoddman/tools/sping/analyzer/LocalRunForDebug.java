package com.github.curiousoddman.tools.sping.analyzer;

import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class LocalRunForDebug {
    public static void main(String[] args) throws NoSuchFieldException, IllegalAccessException, IOException, MojoExecutionException, MojoFailureException {
        List<String> strings = Files.readAllLines(Path.of("<<< FIXME: Path to file with list of dependencies used in a project >>>"));  // FIXME
        File root = new File("<<< sources directory path >>> "); // FIXME

        SpringEntryPointAnalyzer springEntryPointAnalyzer = new SpringEntryPointAnalyzer();
        SystemStreamLog log = new SystemStreamLog();
        springEntryPointAnalyzer.setLog(log);
        MyMavenProject mp = new MyMavenProject();
        Set artifacts = new HashSet();
        int i = 0;
        for (String string : strings) {
            i++;
            DefaultArtifact artifact = new DefaultArtifact("cont.herr.sadf", "asdfasdfsa" + i, VersionRange.createFromVersion("1"), "", "", "", new DefaultArtifactHandler());
            artifact.setFile(new File(string.replace("[INFO] Adding JAR to solver: ", "")));
            artifacts.add(artifact);
        }
        mp.setArtifacts(artifacts);
        mp.setSrc(root);
        Field projectField = SpringEntryPointAnalyzer.class.getDeclaredField("project");
        projectField.setAccessible(true);
        projectField.set(springEntryPointAnalyzer, mp);

        springEntryPointAnalyzer.execute();
    }

    private static class MyMavenProject extends MavenProject {
        public void setSrc(File src) {
            super.setCompileSourceRoots(List.of(src));
        }
    }
}


