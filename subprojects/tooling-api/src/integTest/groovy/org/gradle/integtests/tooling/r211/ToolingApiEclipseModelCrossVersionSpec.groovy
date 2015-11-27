/*
 * Copyright 2015 the original author or authors.
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

package org.gradle.integtests.tooling.r211
import org.gradle.api.JavaVersion
import org.gradle.integtests.tooling.fixture.TargetGradleVersion
import org.gradle.integtests.tooling.fixture.ToolingApiSpecification
import org.gradle.integtests.tooling.fixture.ToolingApiVersion
import org.gradle.tooling.model.UnsupportedMethodException
import org.gradle.tooling.model.eclipse.EclipseProject

@ToolingApiVersion('>=2.11')
@TargetGradleVersion(">=2.11")
class ToolingApiEclipseModelCrossVersionSpec extends ToolingApiSpecification {

    def setup() {
        settingsFile << "rootProject.name = 'root'"
    }

    def "Java project has target language level"() {
        given:
        buildFile << "apply plugin: 'java'"

        when:
        EclipseProject rootProject = loadEclipseProjectModel()

        then:
        rootProject.javaSourceSettings.targetCompatibilityLevel== JavaVersion.current()
    }

    def "Java project has target runtime"() {
        given:
        buildFile << """
apply plugin: 'java'

description = org.gradle.internal.jvm.Jvm.current().javaHome.toString()
"""
        when:
        EclipseProject rootProject = loadEclipseProjectModel()

        then:
        rootProject.javaSourceSettings.targetRuntime != null
        rootProject.javaSourceSettings.targetRuntime.javaVersion == JavaVersion.current()
        rootProject.javaSourceSettings.targetRuntime.homeDirectory.toString() == rootProject.gradleProject.description
    }

    def "target language level respects explicit targetCompatibility configuration"() {
        given:
        buildFile << """
        apply plugin:'java'
        targetCompatibility = 1.5
"""
        when:
        EclipseProject rootProject = loadEclipseProjectModel()

        then:
        rootProject.javaSourceSettings.targetCompatibilityLevel == JavaVersion.VERSION_1_5
    }

    def "target language level respects explicit configured eclipse config"() {
        given:
        buildFile << """
        apply plugin:'java'
        apply plugin:'eclipse'

        targetCompatibility = 1.6

        eclipse {
            jdt {
                targetCompatibility = 1.5
            }
        }
        """
        when:
        EclipseProject rootProject = loadEclipseProjectModel()

        then:
        rootProject.javaSourceSettings.targetCompatibilityLevel == JavaVersion.VERSION_1_5
    }

    @TargetGradleVersion("=2.10")
    def "older Gradle versions throw exception when querying target language level"() {
        when:
        EclipseProject rootProject = loadEclipseProjectModel()
        rootProject.javaSourceSettings.targetCompatibilityLevel

        then:
        thrown(UnsupportedMethodException)
    }

    @TargetGradleVersion("=2.10")
    def "older Gradle versions throw exception when querying target runtime"() {
        when:
        EclipseProject rootProject = loadEclipseProjectModel()
        rootProject.javaSourceSettings.targetRuntime

        then:
        thrown(UnsupportedMethodException)
    }

    def "Multi-project build can define different target language level for subprojects"() {
        given:
        settingsFile << """
            include 'subproject-a', 'subproject-b', 'subproject-c'
        """

        buildFile << """
            project(':subproject-a') {
                apply plugin: 'java'
                targetCompatibility = 1.1
            }
            project(':subproject-b') {
                apply plugin: 'java'
                apply plugin: 'eclipse'
                eclipse {
                    jdt {
                        targetCompatibility = 1.2
                    }
                }
            }
            project(':subproject-c') {
                apply plugin: 'java'
                apply plugin: 'eclipse'
                targetCompatibility = 1.6
                eclipse {
                    jdt {
                        targetCompatibility = 1.3
                    }
                }
            }
        """

        when:
        EclipseProject rootProject = loadEclipseProjectModel()
        EclipseProject subprojectA = rootProject.children.find { it.name == 'subproject-a' }
        EclipseProject subprojectB = rootProject.children.find { it.name == 'subproject-b' }
        EclipseProject subprojectC = rootProject.children.find { it.name == 'subproject-c' }

        then:
        subprojectA.javaSourceSettings.targetCompatibilityLevel == JavaVersion.VERSION_1_1
        subprojectB.javaSourceSettings.targetCompatibilityLevel == JavaVersion.VERSION_1_2
        subprojectC.javaSourceSettings.targetCompatibilityLevel == JavaVersion.VERSION_1_3
    }

    private EclipseProject loadEclipseProjectModel() {
        withConnection { connection -> connection.getModel(EclipseProject) }
    }
}
