package com.github.curiousoddman.tools.sping.analyzer;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithName;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.SymbolResolver;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.*;

import static com.github.javaparser.ast.Node.SYMBOL_RESOLVER_KEY;


public class SpringEntryPointAnalyzer {
    private final Set<String> interfaces;
    private final Set<String> annotations;
    private final MavenProject project;
    private final Log log;
    private final Map<String, List<String>> result;

    public SpringEntryPointAnalyzer(Set<String> interfaces, Set<String> annotations, MavenProject project, Log log) {
        this.interfaces = interfaces;
        this.annotations = annotations;
        this.project = project;
        this.log = log;
        result = new HashMap<>();
    }

    public Map<String, List<String>> execute() throws MojoExecutionException {
        AnnotationsMap annotationsMap = new AnnotationsMap();

        ParserConfiguration parserConfiguration = new ParserConfiguration();

        // Setup type solver
        buildTypeSolver(project.getCompileSourceRoots(), parserConfiguration);
        ClassLoader classLoader = makeClassLoader();

        JavaParser javaParser = new JavaParser(parserConfiguration);

        for (Object root : project.getCompileSourceRoots()) {
            try {
                log.info("Scanning " + root);
                scanDirectory(new File(String.valueOf(root)), javaParser, classLoader, annotationsMap);
            } catch (IOException e) {
                throw new MojoExecutionException(e);
            }
        }

        return result;
    }

    private void scanDirectory(File dir, JavaParser javaParser, ClassLoader classLoader, AnnotationsMap annotationsMap) throws IOException {
        for (File file : Objects.requireNonNull(dir.listFiles())) {
            if (file.isDirectory()) {
                scanDirectory(file, javaParser, classLoader, annotationsMap);
            } else if (file.getName().endsWith(".java")) {
                parseJavaFile(file, javaParser, classLoader, annotationsMap);
                //log.info("Scanned " + file + " annotationsMap: " + annotationsMap.keySet());
            }
        }
    }

    private void parseJavaFile(File file, JavaParser javaParser, ClassLoader classLoader, AnnotationsMap annotationsMap) throws IOException {
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
                    .map((ClassOrInterfaceType object) -> getFqdn(object, javaParser.getParserConfiguration().getSymbolResolver().get()))
                    .filter(fqdn -> checkContains(fqdn, interfaces))
                    .forEach(i -> triggers.add(new Trigger("Implements " + i, classStartLine)));

// Check-for class-level annotations
            getAnnotationsRecursively(clazz.getAnnotations(), classLoader, annotationsMap, javaParser.getParserConfiguration().getSymbolResolver().get())
                    .stream()
                    .filter(fqdn -> checkContains(fqdn, annotations))
                    .forEach(a -> triggers.add(new Trigger("Annotated with " + a, classStartLine)));

// Check-for method-level annotations
            clazz.getMethods()
                    .forEach(method -> getAnnotationsRecursively(method.getAnnotations(), classLoader, annotationsMap, javaParser.getParserConfiguration().getSymbolResolver().get())
                            .stream()
                            .filter(fqdn -> checkContains(fqdn, annotations))
                            .forEach(a -> triggers.add(new Trigger("Method annotated with " + a, method.getBegin().get().line))));
            triggers.
                    forEach(trigger -> result.computeIfAbsent(trigger.name(), k -> new ArrayList<>()).add(title.formatted(trigger.line())));
        }
    }

    private boolean checkContains(String fqdn, Set<String> set) {
        if (set.contains(fqdn)) {
            return true;
        }

        String[] names = splitFqdn(fqdn);
        if (set.contains(names[1])) {
            log.error(names[1] + " should be FQDN. Did you mean " + fqdn);
        }
        return false;
    }

    private static String[] splitFqdn(String fqdn) {
        int lastIndexOf = fqdn.lastIndexOf('.');
        return new String[]{
                fqdn.substring(0, lastIndexOf),
                fqdn.substring(lastIndexOf + 1)
        };
    }

    public Set<String> getAnnotationsRecursively(List<AnnotationExpr> annotations, ClassLoader classLoader, AnnotationsMap annotationsMap, SymbolResolver symbolResolver) {
        Set<String> annotationNames = new HashSet<>();
        for (AnnotationExpr annotation : annotations) {
            try {
                String name = getFqdn(annotation, symbolResolver);
                // Basic java annotations on annotations
                if (name.startsWith("java.lang")) {
                    continue;
                }
                List<String> annotationSubAnnotations = annotationsMap.findAll(name);
                if (!annotationSubAnnotations.isEmpty()) {
                    annotationNames.addAll(annotationSubAnnotations);
                } else {
                    List<String> visited = resolveMetaAnnotations(name, annotationsMap, "    ", classLoader);
                    annotationNames.addAll(visited);
                }
            } catch (Exception e) {
                log.error("Can't resolve: " + annotation.getNameAsString() + " in " + annotation.getParentNode().get(), e);
            }
        }
        return annotationNames;
    }

    private static String getFqdn(Object object, SymbolResolver symbolResolver) {
        // For unknown reason sometimes SYMBOL_RESOLVER_KEY is not properly set in cu, that blocks symbol resolution
        if (object instanceof Node node) {
            CompilationUnit cu = node.findCompilationUnit().get();
            if (!cu.getDataKeys().contains(SYMBOL_RESOLVER_KEY)) {
                cu.setData(SYMBOL_RESOLVER_KEY, symbolResolver);
            }
        }
        try {
            if (object instanceof AnnotationExpr annotationExpr) {
                return annotationExpr.resolve().getQualifiedName();
            } else if (object instanceof ClassOrInterfaceType classOrInterfaceType) {
                return classOrInterfaceType.resolve().asReferenceType().getQualifiedName();
            }
            throw new IllegalArgumentException("Unexpected type " + object.getClass().getName());
        } catch (UnsolvedSymbolException | IllegalStateException e) {
            return tryManuallyResolveAnnotation(object, e);
        }
    }

    private static String tryManuallyResolveAnnotation(Object object, Exception e) {
        String name;
        if (object instanceof NodeWithName<?> nodeWithName) {
            name = nodeWithName.getNameAsString();
        } else if (object instanceof NodeWithSimpleName<?> nodeWithSimpleName) {
            name = nodeWithSimpleName.getNameAsString();
        } else {
            throw new IllegalArgumentException("Cannot manually resolve object " + object.getClass().getName());
        }

        boolean isNameFqdn = name.contains(".");
        if (isNameFqdn) {
            return name;
        }

        CompilationUnit cu;
        if (object instanceof Node node) {
            cu = node.findCompilationUnit().orElseThrow();
        } else {
            throw new IllegalArgumentException("Cannot manually resolve object " + object.getClass().getName());
        }

        for (ImportDeclaration anImport : cu.getImports()) {
            String importString = anImport.getNameAsString();
            String classFromImport = getClassFromFqdn(importString);
            if (classFromImport.equals(name)) {
                return importString;
            }
        }
        throw new IllegalStateException("Failed in: " + cu.getStorage().get().getPath(), e);
    }

    private static String getClassFromFqdn(String importString) {
        return splitFqdn(importString)[1];
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
            log.error(indent + "Could not resolve: " + fqdn, e);
        }
        return result;
    }

    private void buildTypeSolver(List projectSourcesRoots, ParserConfiguration cfg) {
        CombinedTypeSolver solver = new CombinedTypeSolver();
        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(solver);
        cfg.setSymbolResolver(symbolSolver);
        CombinedTypeSolver combinedJarSolver = new CombinedTypeSolver();

        for (Artifact artifact : (Set<Artifact>) project.getArtifacts()) {
            File jarFile = artifact.getFile();
            if (jarFile != null && jarFile.getName().endsWith(".jar")) {
                //log.debug("Adding JAR to solver: " + jarFile.getAbsolutePath());
                try {
                    combinedJarSolver.add(new JarTypeSolver(jarFile));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        for (Object projectSourcesRoot : projectSourcesRoots) {
            solver.add(new JavaParserTypeSolver(new File(String.valueOf(projectSourcesRoot)), cfg));
        }
        solver.add(new ReflectionTypeSolver());
        solver.add(combinedJarSolver);
    }

    private ClassLoader makeClassLoader() {
        List<File> jars = new ArrayList<>();
        for (Artifact artifact : (Set<Artifact>) project.getArtifacts()) {
            File jarFile = artifact.getFile();
            if (jarFile != null && jarFile.getName().endsWith(".jar")) {
                //log.info("Adding JAR to classpath: " + jarFile.getAbsolutePath());
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

