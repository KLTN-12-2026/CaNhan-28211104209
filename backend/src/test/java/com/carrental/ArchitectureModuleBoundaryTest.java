package com.carrental;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class ArchitectureModuleBoundaryTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java/com/carrental");

    private static final Map<String, Set<String>> ALLOWED_DEPENDENCIES = Map.ofEntries(
        Map.entry("auth", Set.of("user")),
        Map.entry("user", Set.of()),
        Map.entry("vehicle", Set.of("branch")),
        Map.entry("branch", Set.of("user")),
        Map.entry("location", Set.of()),
        Map.entry("booking", Set.of("user", "vehicle", "branch")),
        Map.entry("operation", Set.of("booking", "vehicle", "user", "contract", "invoice", "branch", "payment")),
        Map.entry("payment", Set.of("booking")),
        Map.entry("contract", Set.of("booking")),
        Map.entry("invoice", Set.of("booking")),
        Map.entry("review", Set.of("booking")),
        Map.entry("ai", Set.of("vehicle", "booking", "location"))
    );

    static String stripImportsAndComments(String sourceCode) {
        // Loại bỏ block comments /* ... */
        String noBlockComments = sourceCode.replaceAll("(?s)/\\*.*?\\*/", " ");
        // Loại bỏ line comments // ...
        String noComments = noBlockComments.replaceAll("//.*", " ");
        // Loại bỏ các dòng/khối import
        return noComments.replaceAll("import\\s+(?:static\\s+)?[^;]+;", " ");
    }

    static List<String> detectViolations(String sourceCode, String disallowedPackagePrefix) {
        List<String> violations = new ArrayList<>();

        // 1. Nhận diện import (bắt được cả nhiều import trên cùng 1 dòng, wildcard, subpackage, static import)
        Pattern importPattern = Pattern.compile("import\\s+(?:static\\s+)?([a-zA-Z0-9_.]*" + Pattern.quote(disallowedPackagePrefix) + "[a-zA-Z0-9_.*]*)\\s*;");
        var importMatcher = importPattern.matcher(sourceCode);
        while (importMatcher.find()) {
            violations.add("Import violation: " + importMatcher.group(1));
        }

        // 2. Nhận diện Fully-Qualified Name (FQN) references trực tiếp trong code
        String strippedCode = stripImportsAndComments(sourceCode);
        Pattern fqnPattern = Pattern.compile("\\b" + Pattern.quote(disallowedPackagePrefix) + "(?:\\.[a-zA-Z0-9_]+)+\\b");
        var fqnMatcher = fqnPattern.matcher(strippedCode);
        while (fqnMatcher.find()) {
            violations.add("FQN reference violation: " + fqnMatcher.group());
        }

        return violations;
    }

    private List<Path> getJavaFiles(Path directory) throws IOException {
        assertTrue(Files.exists(directory), "Thư mục mã nguồn phải tồn tại: " + directory);
        List<Path> files;
        try (var stream = Files.walk(directory)) {
            files = stream.filter(p -> p.toString().endsWith(".java")).toList();
        }
        assertFalse(files.isEmpty(), "Phải có ít nhất một file .java cần kiểm tra trong: " + directory);
        return files;
    }

    static Set<String> extractReferencedModules(String sourceCode, String currentModule) {
        Set<String> referenced = new HashSet<>();
        // Tìm mọi tham chiếu com.carrental.modules.<targetModule>
        Pattern pattern = Pattern.compile("com\\.carrental\\.modules\\.([a-zA-Z0-9_]+)");
        var matcher = pattern.matcher(sourceCode);
        while (matcher.find()) {
            String target = matcher.group(1);
            if (!target.equals(currentModule)) {
                referenced.add(target);
            }
        }
        return referenced;
    }

    static boolean hasCycle(Map<String, Set<String>> graph) {
        Map<String, Integer> state = new HashMap<>(); // 0: unvisited, 1: visiting, 2: visited
        for (String node : graph.keySet()) {
            if (dfsCheckCycle(node, graph, state)) {
                return true;
            }
        }
        return false;
    }

    private static boolean dfsCheckCycle(String node, Map<String, Set<String>> graph, Map<String, Integer> state) {
        int s = state.getOrDefault(node, 0);
        if (s == 1) return true; // Chu trình phát hiện
        if (s == 2) return false;

        state.put(node, 1);
        for (String neighbor : graph.getOrDefault(node, Set.of())) {
            if (dfsCheckCycle(neighbor, graph, state)) {
                return true;
            }
        }
        state.put(node, 2);
        return false;
    }

    @Test
    void bookingModuleDoesNotDependOnOperationDirectly() throws Exception {
        Path bookingDir = SOURCE_ROOT.resolve("modules/booking");
        List<Path> javaFiles = getJavaFiles(bookingDir);
        for (Path file : javaFiles) {
            String content = Files.readString(file);
            List<String> violations = detectViolations(content, "com.carrental.modules.operation");
            assertTrue(violations.isEmpty(),
                "Booking module must not reference operation module in " + file.getFileName() + ": " + violations);
        }
    }

    @Test
    void vehicleModuleDoesNotDependOnBookingModule() throws Exception {
        Path vehicleDir = SOURCE_ROOT.resolve("modules/vehicle");
        List<Path> javaFiles = getJavaFiles(vehicleDir);
        for (Path file : javaFiles) {
            String content = Files.readString(file);
            List<String> violations = detectViolations(content, "com.carrental.modules.booking");
            assertTrue(violations.isEmpty(),
                "Vehicle module must not depend on booking module in " + file.getFileName() + ": " + violations);
        }
    }

    @Test
    void userModuleDoesNotDependOnUpperModules() throws Exception {
        Path userDir = SOURCE_ROOT.resolve("modules/user");
        List<Path> javaFiles = getJavaFiles(userDir);
        List<String> forbidden = List.of(
            "com.carrental.modules.booking",
            "com.carrental.modules.operation",
            "com.carrental.modules.vehicle",
            "com.carrental.modules.review"
        );
        for (Path file : javaFiles) {
            String content = Files.readString(file);
            for (String pkg : forbidden) {
                List<String> violations = detectViolations(content, pkg);
                assertTrue(violations.isEmpty(),
                    "User module must not import " + pkg + " in " + file.getFileName() + ": " + violations);
            }
        }
    }

    @Test
    void branchModuleOnlyDependsOnAllowedModules() throws Exception {
        Path branchDir = SOURCE_ROOT.resolve("modules/branch");
        List<Path> javaFiles = getJavaFiles(branchDir);
        List<String> forbidden = List.of(
            "com.carrental.modules.booking",
            "com.carrental.modules.operation",
            "com.carrental.modules.vehicle",
            "com.carrental.modules.review"
        );
        for (Path file : javaFiles) {
            String content = Files.readString(file);
            for (String pkg : forbidden) {
                List<String> violations = detectViolations(content, pkg);
                assertTrue(violations.isEmpty(),
                    "Branch module must not import " + pkg + " in " + file.getFileName() + ": " + violations);
            }
        }
    }

    @Test
    void commonInfrastructureDoesNotContainBusinessRules() throws Exception {
        Path exceptionDir = SOURCE_ROOT.resolve("exception");
        List<Path> javaFiles = getJavaFiles(exceptionDir);
        for (Path file : javaFiles) {
            String content = Files.readString(file);
            List<String> violations = detectViolations(content, "com.carrental.modules");
            assertTrue(violations.isEmpty(),
                "Common exceptions must not depend on domain modules in " + file.getFileName() + ": " + violations);
        }
    }

    @Test
    void entireCodebaseModuleDependencyGraphConformsToSpecificationAndHasNoCycles() throws Exception {
        Path modulesRoot = SOURCE_ROOT.resolve("modules");
        assertTrue(Files.exists(modulesRoot), "Modules directory must exist: " + modulesRoot);

        Map<String, Set<String>> actualGraph = new HashMap<>();

        try (var dirs = Files.list(modulesRoot)) {
            for (Path moduleDir : dirs.filter(Files::isDirectory).toList()) {
                String moduleName = moduleDir.getFileName().toString();
                Set<String> dependencies = new HashSet<>();

                try (var stream = Files.walk(moduleDir)) {
                    List<Path> javaFiles = stream.filter(p -> p.toString().endsWith(".java")).toList();
                    for (Path file : javaFiles) {
                        String content = Files.readString(file);
                        dependencies.addAll(extractReferencedModules(content, moduleName));
                    }
                }
                actualGraph.put(moduleName, dependencies);
            }
        }

        // 1. Kiểm tra từng cạnh phụ thuộc thực tế so với ma trận quy định
        for (var entry : actualGraph.entrySet()) {
            String module = entry.getKey();
            Set<String> allowed = ALLOWED_DEPENDENCIES.getOrDefault(module, Set.of());
            for (String target : entry.getValue()) {
                assertTrue(allowed.contains(target),
                    "Module [" + module + "] is NOT allowed to depend on [" + target + "]. Allowed: " + allowed);
            }
        }

        // 2. Kiểm tra chu trình phụ thuộc (Cycle Detection)
        assertFalse(hasCycle(actualGraph), "Module dependency graph must be a DAG and contain NO CYCLES: " + actualGraph);
    }

    @Test
    void detectorCatchesSubpackageWildcardAndStaticImportFixtures() {
        String subpackageFixture = "package sample;\nimport com.carrental.modules.operation.service.RentalWorkflow;\nclass A{}";
        List<String> v1 = detectViolations(subpackageFixture, "com.carrental.modules.operation");
        assertEquals(1, v1.size());
        assertTrue(v1.get(0).contains("RentalWorkflow"));

        String wildcardFixture = "package sample;\nimport com.carrental.modules.operation.*;\nclass B{}";
        List<String> v2 = detectViolations(wildcardFixture, "com.carrental.modules.operation");
        assertEquals(1, v2.size());

        String staticFixture = "package sample;\nimport static com.carrental.modules.operation.service.RentalWorkflow.apply;\nclass C{}";
        List<String> v3 = detectViolations(staticFixture, "com.carrental.modules.operation");
        assertEquals(1, v3.size());

        String legalFixture = "package sample;\nimport com.carrental.modules.booking.service.BookingService;\nclass D{}";
        List<String> v4 = detectViolations(legalFixture, "com.carrental.modules.operation");
        assertTrue(v4.isEmpty());
    }

    @Test
    void detectorCatchesFqnReferencesAndSameLineImports() {
        // Nhiều import trên cùng một dòng
        String sameLineFixture = "package sample; import foo.Bar; import com.carrental.modules.operation.service.RentalWorkflow; class X{}";
        List<String> v1 = detectViolations(sameLineFixture, "com.carrental.modules.operation");
        assertEquals(1, v1.size(), "Detector must catch illegal import even when on the same line as another import");

        // FQN reference trong thân hàm (không qua import)
        String fqnFixture = "package sample;\nclass Y { void run() { com.carrental.modules.operation.service.RentalWorkflow wf = null; } }";
        List<String> v2 = detectViolations(fqnFixture, "com.carrental.modules.operation");
        assertEquals(1, v2.size(), "Detector must catch FQN reference inside code body");
        assertTrue(v2.get(0).contains("FQN reference violation"));

        // Cycle detector catches artificial cycle
        Map<String, Set<String>> cyclicGraph = Map.of(
            "booking", Set.of("operation"),
            "operation", Set.of("booking")
        );
        assertTrue(hasCycle(cyclicGraph), "Cycle detector must flag circular dependencies");

        Map<String, Set<String>> acyclicGraph = Map.of(
            "operation", Set.of("booking"),
            "booking", Set.of("vehicle"),
            "vehicle", Set.of()
        );
        assertFalse(hasCycle(acyclicGraph), "Cycle detector must confirm DAG");
    }
}
