package com.github.curiousoddman.tools.sping.analyzer;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.declarations.ResolvedAnnotationDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.*;


@Mojo(name = "scan", defaultPhase = LifecyclePhase.VERIFY, requiresDependencyResolution = ResolutionScope.COMPILE)
public class SpringEntryPointAnalyzer extends AbstractMojo {
    private static final Set<String> LIFECYCLE_INTERFACES = Set.of("ApplicationRunner", "CommandLineRunner", "InitializingBean", "Disposableßean", "SmartLifecycle");
    private static final Set<String> ANNOTATIONS = Set.of("Controller", "Configuration");
    private static final Set<String> METHOD_ANNOTATIONS = Set.of("PostConstruct", "PreDestroy", "Scheduled", "EventListener");

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;


    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        Map<String, List<String>> springlaunchMap = new HashMap<>();
        AnnotationsMap annotationsMap = new AnnotationsMap();

        ParserConfiguration parserConfiguration = new ParserConfiguration();

        // Setup type solver
        buildTypeSolver(project.getCompileSourceRoots(), parserConfiguration);
        ClassLoader classLoader = makeClassLoader();

        JavaParser javaParser = new JavaParser(parserConfiguration);

        for (Object root : project.getCompileSourceRoots()) {
            try {
                getLog().info("Scanning " + root);
                scanDirectory(new File(String.valueOf(root)), springlaunchMap, javaParser, classLoader, annotationsMap);
            } catch (IOException e) {
                throw new MojoExecutionException(e);
            }
        }

        getLog().info("Found " + springlaunchMap.size() + " spring launch entry points");
        springlaunchMap.forEach((className, triggers) -> {
            getLog().info("Class: " + className);
            triggers.forEach(trigger -> getLog().info(" - " + trigger));
        });
    }

    private void scanDirectory(File dir, Map<String, List<String>> result, JavaParser javaParser, ClassLoader classLoader, AnnotationsMap annotationsMap) throws IOException {
        for (File file : Objects.requireNonNull(dir.listFiles())) {
            if (file.isDirectory()) {
                scanDirectory(file, result, javaParser, classLoader, annotationsMap);
            } else if (file.getName().endsWith(".java")) {
                parseJavaFile(file, result, javaParser, classLoader, annotationsMap);
                getLog().info("Scanned " + file + " annotationsMap: " + annotationsMap.keySet());
            }
        }
    }

    private void parseJavaFile(File file, Map<String, List<String>> result, JavaParser javaParser, ClassLoader classLoader, AnnotationsMap annotationsMap) throws IOException {
        CompilationUnit cu = javaParser.parse(file).getResult().get();
        for (ClassOrInterfaceDeclaration clazz : cu.findAll(ClassOrInterfaceDeclaration.class)) {
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
            getAnnotationsRecursively(clazz.getAnnotations(), classLoader, annotationsMap)
                    .stream()
                    .filter(ANNOTATIONS::contains)
                    .forEach(a -> triggers.add(new Trigger("Annotated with " + a, classStartLine)));
// Check-for method-level annotations
            clazz.getMethods()
                    .forEach(method -> getAnnotationsRecursively(method.getAnnotations(), classLoader, annotationsMap).stream().filter(METHOD_ANNOTATIONS::contains).
                            forEach(a -> triggers.add(new Trigger("Method annotated with " + a, method.getBegin().get().line))));
            triggers.
                    forEach(trigger -> result.computeIfAbsent(trigger.name(), k -> new ArrayList<>()).add(title.formatted(trigger.line())));
        }
    }

    public Set<String> getAnnotationsRecursively(List<AnnotationExpr> annotations, ClassLoader classLoader, AnnotationsMap annotationsMap) {
        Set<String> annotationNames = new HashSet<>();
        for (AnnotationExpr annotation : annotations) {
            try {
                String name;
                try {
                    ResolvedAnnotationDeclaration resolved = annotation.resolve();
                    name = resolved.getQualifiedName();
                } catch (IllegalStateException e) {
                    name = tryManuallyResolveAnnotation(annotation, e);
                }
                List<String> annotationSubAnnotations = annotationsMap.findAll(name);
                if (!annotationSubAnnotations.isEmpty()) {
                    annotationNames.addAll(
                            annotationSubAnnotations.stream().map(s -> s.substring(s.lastIndexOf('.') + 1)).toList()
                    );
                } else {
                    List<String> visited = resolveMetaAnnotations(name, annotationsMap, "    ", classLoader);
                    annotationNames.addAll(
                            visited.stream().map(s -> s.substring(s.lastIndexOf('.') + 1)).toList()
                    );
                }
            } catch (Exception e) {
                getLog().error("Can't resolve: " + annotation.getNameAsString() + " in " + annotation.getParentNode().get(), e);
            }
        }
        return annotationNames;
    }

    private static String tryManuallyResolveAnnotation(AnnotationExpr annotation, IllegalStateException e) {
        // For some reason getting this error: java.lang.IllegalStateException: Symbol resolution not configured: to configure consider setting a SymbolResolver in the ParserConfiguration
        CompilationUnit cu = annotation.findCompilationUnit().orElseThrow();
        for (ImportDeclaration anImport : cu.getImports()) {
            String importString = anImport.getNameAsString();
            String classFromImport = importString.substring(importString.lastIndexOf('.') + 1);
            if (annotation.getNameAsString().equals(classFromImport)) {
                return importString;
            }
        }
        throw new IllegalStateException("Failed in: " + cu.getStorage().get().getPath(), e);
    }

    private List<String> resolveMetaAnnotations(String fqdn, AnnotationsMap annotationsMap, String indent, ClassLoader classLoader) {
        // Basic java annotations on annotations
        if (fqdn.startsWith("java.lang.annotation")) {
            return List.of();
        }
        List<String> result = new ArrayList<>();

        try {
            List<String> all = annotationsMap.findAll(fqdn);
            if (!all.isEmpty()) {
                return all;
            }

            try {
                Class<?> annotationClass = Class.forName(fqdn, true, classLoader);
                for (Annotation meta : annotationClass.getAnnotations()) {
                    String name = meta.annotationType().getName();
                    resolveMetaAnnotations(name, annotationsMap, indent + "  ", classLoader);
                    result.add(name);
                }
                annotationsMap.put(fqdn, result);
                return result;
            } catch (ClassNotFoundException e) {
                System.out.println("Could not load class: " + fqdn);
            }
        } catch (Exception e) {
            getLog().error(indent + "Could not resolve: " + fqdn, e);
        }
        return result;
    }

    private void buildTypeSolver(List projectSourcesRoots, ParserConfiguration cfg) {
        CombinedTypeSolver solver = new CombinedTypeSolver();
        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(solver);
        cfg.setSymbolResolver(symbolSolver);

        for (Artifact artifact : (Set<Artifact>) project.getArtifacts()) {
            File jarFile = artifact.getFile();
            if (jarFile != null && jarFile.getName().endsWith(".jar")) {
                //getLog().debug("Adding JAR to solver: " + jarFile.getAbsolutePath());
                try {
                    solver.add(new JarTypeSolver(jarFile));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        solver.add(new ReflectionTypeSolver());
        for (Object projectSourcesRoot : projectSourcesRoots) {
            solver.add(new JavaParserTypeSolver(new File(String.valueOf(projectSourcesRoot)), cfg));
        }
    }

    private ClassLoader makeClassLoader() {
        List<File> jars = new ArrayList<>();
        for (Artifact artifact : (Set<Artifact>) project.getArtifacts()) {
            File jarFile = artifact.getFile();
            if (jarFile != null && jarFile.getName().endsWith(".jar")) {
                getLog().info("Adding JAR to solver: " + jarFile.getAbsolutePath());
                jars.add(jarFile);
            }
        }

        List<URL> list = new ArrayList<>();
        for (File jar : jars) {
            try {
                list.add(jar.toURI().toURL());
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
        }

        return new URLClassLoader(
                list.toArray(new URL[0]),
                Thread.currentThread().getContextClassLoader()
        );
    }
}

