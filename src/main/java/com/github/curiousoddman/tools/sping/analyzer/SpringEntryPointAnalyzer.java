package com.github.curiousoddman.tools.sping.analyzer;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

public class SpringEntryPointAnalyzer {
    private static final Set<String> LIFECYCLE_INTERFACES = Set.of("ApplicationRunner", "CommandLineRunner", "InitializingBean", "Disposableßean", "SmartLifecycle");
    private static final Set<String> ANNOTATIONS = Set.of("Controller", "Configuration");
    private static final Set<String> METHOD_ANNOTATIONS = Set.of("PostConstruct", "PreDestroy", "Scheduled", "EventListener");

    private static File root;

    public static void main(String[] args) throws IOException {
        root = new File(args[0]);

        Map<String, List<String>> springlaunchMap = new HashMap<>();

        scanDirectory(root, springlaunchMap);
        springlaunchMap.forEach((className, triggers) -> {
            System.out.println("Class: " + className);
            triggers.forEach(trigger -> System.out.println(" - " + trigger));
        });
    }

    private static void scanDirectory(File dir, Map<String, List<String>> result) throws IOException {
        for (File file : Objects.requireNonNull(dir.listFiles())) {
            if (file.isDirectory()) {
                scanDirectory(file, result);
            } else if (file.getName().endsWith(".java")) {
                parseJavaFile(file, result);
            }
        }
    }

    private static void parseJavaFile(File file, Map<String, List<String>> result) throws IOException {
        CompilationUnit cu = new JavaParser().parse(file).getResult().get();
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            int classStartLine = clazz.getBegin().get().line;
            Path filePath = file.toPath().toAbsolutePath();
            String pathString = filePath.toString().replace('\\', '/');
            String title = "%s file:///%s".formatted(clazz.asClassOrInterfaceDeclaration().getNameAsString(), pathString) + ":%d";
            List<Trigger> triggers = new ArrayList<>();

// Check-for lifecycle interfaces
            clazz.getImplementedTypes()
                    .stream()
                    .map(ClassOrInterfaceType::getNameAsString)
                    .filter(LIFECYCLE_INTERFACES::contains)
                    .forEach(i -> triggers.add(new Trigger("Implements " + i, classStartLine)));

// Check-for class-level annotations
            clazz.getAnnotations()
                    .stream()
                    .map(AnnotationExpr::getNameAsString)
                    .filter(ANNOTATIONS::contains)
                    .forEach(a -> triggers.add(new Trigger("Annotated with " + a, classStartLine)));
// Check-for method-level annotations
            clazz.getMethods()
                    .forEach(method -> method.getAnnotations().stream().map(AnnotationExpr::getNameAsString).filter(METHOD_ANNOTATIONS::contains).
                            forEach(a -> triggers.add(new Trigger("Method annotated with " + a, method.getBegin().get().line))));
            triggers.
                    forEach(trigger -> result.computeIfAbsent(trigger.name(), k -> new ArrayList<>()).add(title.formatted(trigger.line())));
        });
    }
}

