/*
 * Copyright 2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.tasks.compile

import groovy.transform.stc.ClosureParams
import groovy.transform.stc.SimpleType
import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.integtests.fixtures.problems.ReceivedProblem
import org.gradle.test.fixtures.file.TestFile
/**
 * Test class verifying the integration between the {@code JavaCompile} and the {@code Problems} service.
 */
class JavaCompileProblemsIntegrationTest extends AbstractIntegrationSpec {

    /**
     * A map of all possible file locations, and the number of occurrences we expect to find in the problems.
     */
    private final Map<String, Integer> possibleFileLocations = [:]

    /**
     * A map of all visited file locations, and the number of occurrences we have found in the problems.
     * <p>
     * This field will be updated by {@link #assertProblem(ReceivedProblem, String, Boolean, Closure)} as it asserts a problem.
     */
    private final Map<String, Integer> visitedFileLocations = [:]

    def setup() {
        enableProblemsApiCheck()
        // Explicitly enable the toolchain detection, as the test will use the toolchain to compile the code
        executer.withToolchainDetectionEnabled()

        propertiesFile << """
            # Feature flag as of 8.6 to enable the Problems API
            systemProp.org.gradle.internal.emit-compiler-problems=true
        """

        buildFile << """
            plugins {
                id 'java'
            }

            tasks {
                compileJava {
                    options.compilerArgs += ["-Xlint:all"]
                }
            }
        """
    }

    def cleanup() {
        assert possibleFileLocations == visitedFileLocations: "Not all expected files were visited: ${possibleFileLocations.keySet() - visitedFileLocations.keySet()}"
    }

    def "problem is received when a single-file compilation failure happens"() {
        given:
        possibleFileLocations.put(writeJavaCausingTwoCompilationErrors("Foo"), 2)

        when:
        fails("compileJava", "--stacktrace", "--info")

        then:
        def problems = collectedProblems
        problems.size() == 2
        for (def problem in (problems)) {
            assertProblem(problem, "ERROR", true) { details ->
                assert details == "';' expected"
            }
        }
    }

    def "problems are received when a multi-file compilation failure happens"() {
        given:
        possibleFileLocations.put(writeJavaCausingTwoCompilationErrors("Foo"), 2)
        possibleFileLocations.put(writeJavaCausingTwoCompilationErrors("Bar"), 2)

        when:
        fails("compileJava")

        then:
        collectedProblems.size() == 4
        for (def problem in collectedProblems) {
            assertProblem(problem, "ERROR", true) { details ->
                assert details == "';' expected"
            }
        }
    }

    def "problem is received when a single-file warning happens"() {
        given:
        possibleFileLocations.put(writeJavaCausingTwoCompilationWarnings("Foo"), 2)

        when:
        def result = run("compileJava")

        then:
        collectedProblems.size() == 2
        for (def problem in collectedProblems) {
            assertProblem(problem, "WARNING", true) { details ->
                assert details == "redundant cast to java.lang.String"
            }
        }
    }

    def "problems are received when a multi-file warning happens"() {
        given:
        possibleFileLocations.put(writeJavaCausingTwoCompilationWarnings("Foo"), 2)
        possibleFileLocations.put(writeJavaCausingTwoCompilationWarnings("Bar"), 2)

        when:
        def result = run("compileJava")

        then:
        collectedProblems.size() == 4
        for (def problem in collectedProblems) {
            assertProblem(problem, "WARNING", true) { details ->
                assert details == "redundant cast to java.lang.String"
            }
        }
    }

    def "only failures are received when a multi-file compilation failure and warning happens"() {
        given:
        possibleFileLocations.put(writeJavaCausingTwoCompilationErrorsAndTwoWarnings("Foo"), 2)
        possibleFileLocations.put(writeJavaCausingTwoCompilationErrorsAndTwoWarnings("Bar"), 2)

        when:
        def result = fails("compileJava")

        then:
        collectedProblems.size() == 4
        for (def problem in collectedProblems) {
            assertProblem(problem, "ERROR", true) { details ->
                assert details == "';' expected"
            }
        }
    }

    def "problems are received when two separate compilation task is executed"() {
        given:
        // The main source set will only cause warnings, as otherwise compilation will fail with the `compileJava` task
        possibleFileLocations.put(writeJavaCausingTwoCompilationWarnings("Foo"), 2)
        // The test source set will cause errors
        possibleFileLocations.put(writeJavaCausingTwoCompilationErrors("FooTest", "test"), 2)

        when:
        // Special flag to fork the compiler, see the setup()
        fails("compileTestJava")

        then:
        collectedProblems.size() == 4

        def warningProblems = collectedProblems.findAll {
            it['definition']["severity"] == "WARNING"
        }
        warningProblems.size() == 2
        for (def problem in warningProblems) {
            assertProblem(problem, "WARNING", true) { details ->
                assert details == "redundant cast to java.lang.String"
            }
        }

        def errorProblems = collectedProblems.findAll {
            it['definition']["severity"] == "ERROR"
        }
        errorProblems.size() == 2
        for (def problem in errorProblems) {
            assertProblem(problem, "ERROR", true) { details ->
                assert details == "';' expected"
            }
        }
    }

    def "the compiler flag -Werror correctly reports"() {
        given:
        buildFile << "tasks.compileJava.options.compilerArgs += ['-Werror']"
        possibleFileLocations.put(writeJavaCausingTwoCompilationWarnings("Foo"), 3)

        when:
        fails("compileJava")

        then:
        // 2 warnings + 1 special error
        collectedProblems.size() == 3

        // The two expected warnings are still reported as warnings
        def warningProblems = collectedProblems.findAll {
            it['definition']["severity"] == "WARNING"
        }
        warningProblems.size() == 2
        for (def problem in warningProblems) {
            assertProblem(problem, "WARNING", true) { details ->
                assert details == "redundant cast to java.lang.String"
            }
        }

        // The compiler will report a single error, implying that the warnings were treated as errors
        def errorProblems = collectedProblems.findAll {
            it['definition']["severity"] == "ERROR"
        }
        errorProblems.size() == 1
        assertProblem(errorProblems[0], "ERROR", false) { details ->
            assert details == "warnings found and -Werror specified"
        }
    }

    def "problems are reported when using a JDK #jvmVersion toolchain"(int jvmVersion) {
        given:
        buildFile << """
            java {
                toolchain {
                    languageVersion = JavaLanguageVersion.of(${jvmVersion})
                }
            }

            // Force the compiler forking
            // This will fork a compiler daemon, even if the Gradle daemon is running the same JVM version
            tasks.compileJava.options.fork = true
        """
        possibleFileLocations.put(writeJavaCausingTwoCompilationErrors("Foo"), 2)

        when:
        fails("compileJava")

        then:
        collectedProblems.size() == 2
        for (ReceivedProblem problem in collectedProblems) {
            assertProblem(problem, "ERROR", true) { details ->
                assert details == "';' expected"
            }
        }

        where:
        jvmVersion << [8, 11, 21]
    }

    /**
     * Assert if a compilation problems looks like how we expect it to look like.
     * <p>
     * In addition, the method will update the {@link #possibleFileLocations} with the location found in the problem.
     * This could be used to assert that all expected files have been visited.
     *
     * @param problem the problem to assert
     * @param severity the expected severity of the problem
     * @param expectPreciseLocation if it is expected that the problem has a precise location (path, line, column, length) and (path, offset, length)
     * @param extraChecks an optional closure to perform any custom checks on the problem, like checking the precise message of the problem
     *
     * @throws AssertionError if the problem does not look like how we expect it to look like
     */
    void assertProblem(
        ReceivedProblem problem,
        String severity,
        boolean expectPreciseLocation = true,
        @ClosureParams(
            value = SimpleType,
            options = [
                "java.lang.String"
            ]
        ) Closure extraChecks = null
    ) {
        assert problem['definition']["severity"] == severity: "Expected severity to be ${severity}, but was ${problem['definition']["severity"]}"
        switch (severity) {
            case "ERROR":
                assert problem['definition']["id"]["displayName"] == "Java compilation error": "Expected label 'Java compilation error', but was ${problem['definition']["id"]["displayName"]}"
                break
            case "WARNING":
                assert problem['definition']["id"]["displayName"] == "Java compilation warning": "Expected label 'Java compilation warning', but was ${problem['definition']["id"]["displayName"]}"
                break
            default:
                throw new IllegalArgumentException("Unknown severity: ${severity}")
        }

        def details = problem["details"] as String
        assert details: "Expected details to be non-null, but was null"

        def locations = problem["locations"] as List<Map<String, Object>>
        // We use this counter to assert that we have visited all locations
        def assertedLocationCount = 0

        def fileLocation = locations.find {
            it.keySet() == ["path"].toSet()
        }
        assert fileLocation != null: "Expected a file location, but it was null"
        def fileLocationPath = fileLocation["path"] as String
        // Register that we've asserted this location
        assertedLocationCount += 1
        // Check if we expect this file location
        def occurrences = possibleFileLocations.get(fileLocationPath)
        assert occurrences: "Not found file location '${fileLocationPath}' in the expected file locations: ${possibleFileLocations.keySet()}"
        visitedFileLocations.putIfAbsent(fileLocationPath, 0)
        visitedFileLocations[fileLocationPath] += 1

        if (expectPreciseLocation) {
            def positionLocation = locations.find {
                it.keySet() == ["path", "line", "column", "length"].toSet()
            }
            assert positionLocation != null: "Expected a precise file location, but it was null"
            // Register that we've asserted this location
            assertedLocationCount += 1

            def offsetLocation = locations.find {
                it.keySet() == ["path", "offset", "length"].toSet()
            }
            assert offsetLocation != null: "Expected a precise file location, but it was null"
            // Register that we've asserted this location
            assertedLocationCount += 1
        }

        assert assertedLocationCount == locations.size(): "Expected to assert all locations, but only visited ${assertedLocationCount} out of ${locations.size()}"

        if (extraChecks != null) {
            extraChecks.call(details)
        }
    }

    String writeJavaCausingTwoCompilationErrors(String className, String sourceSet = "main") {
        def file = file("src/${sourceSet}/java/${className}.java")
        file << """
            public class ${className} {
                public static void problemOne(String[] args) {
                    // Missing semicolon will trigger an error
                    String s = "Hello, World!"
                }

                public static void problemTwo(String[] args) {
                    // Missing semicolon will trigger an error
                    String s = "Hello, World!"
                }
            }
        """

        return formatFilePath(file)
    }

    String writeJavaCausingTwoCompilationWarnings(String className) {
        def file = file("src/main/java/${className}.java")
        file << """
            public class ${className} {
                public static void warningOne(String[] args) {
                    // Unnecessary cast will trigger a warning
                    String s = (String)"Hello World";
                }

                public static void warningTwo(String[] args) {
                    // Unnecessary cast will trigger a warning
                    String s = (String)"Hello World";
                }
            }
        """

        return formatFilePath(file)
    }

    String writeJavaCausingTwoCompilationErrorsAndTwoWarnings(String className) {
        def file = file("src/main/java/${className}.java")
        file << """
            public class ${className} {
                public static void problemOne(String[] args) {
                    // Missing semicolon will trigger an error
                    String s = "Hello, World!"
                }

                public static void problemTwo(String[] args) {
                    // Missing semicolon will trigger an error
                    String s = "Hello, World!"
                }

                public static void warningOne(String[] args) {
                    // Unnecessary cast will trigger a warning
                    String s = (String)"Hello World";
                }

                public static void warningTwo(String[] args) {
                    // Unnecessary cast will trigger a warning
                    String s = (String)"Hello World";
                }
            }
        """

        return formatFilePath(file)
    }

    def formatFilePath(TestFile file) {
        return file.absolutePath.toString()
    }

}
