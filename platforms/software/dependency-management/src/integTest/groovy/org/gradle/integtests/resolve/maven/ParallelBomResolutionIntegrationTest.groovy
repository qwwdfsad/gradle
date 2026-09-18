/*
 * Copyright 2026 Gradle and contributors.
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
package org.gradle.integtests.resolve.maven

import org.gradle.integtests.fixtures.AbstractHttpDependencyResolutionTest
import org.gradle.test.fixtures.server.http.BlockingHttpServer
import org.junit.Rule

class ParallelBomResolutionIntegrationTest extends AbstractHttpDependencyResolutionTest {
    @Rule
    public BlockingHttpServer blockingServer = new BlockingHttpServer()

    def setup() {
        blockingServer.start()
        buildFile << """
            plugins { id 'jvm-ecosystem' }
            repositories {
                maven {
                    url = '$blockingServer.uri'
                    metadataSources {
                        mavenPom()
                        ignoreGradleMetadataRedirection()
                    }
                }
            }
            configurations { conf }
            dependencies {
                conf platform('test:root:1')
                conf 'test:library'
            }
            tasks.register('resolve') {
                def files = configurations.conf
                doLast {
                    files.incoming.resolutionResult.allDependencies.each { dependency ->
                        if (dependency instanceof org.gradle.api.artifacts.result.UnresolvedDependencyResult) {
                            throw dependency.failure
                        }
                    }
                    println 'files: ' + files.files*.name.sort()
                }
            }
        """
    }

    def "overlaps imported BOM downloads by default with one worker and reuses each download preserving first import wins"() {
        given:
        def library = mavenRepo.module('test', 'library', '1').publish()
        def otherLibrary = mavenRepo.module('test', 'library', '2').publish()
        def first = mavenRepo.module('test', 'first', '1').hasType('pom').dependencyConstraint(library).publish()
        def second = mavenRepo.module('test', 'second', '1').hasType('pom').dependencyConstraint(otherLibrary).publish()
        def root = mavenRepo.module('test', 'root', '1').hasType('pom')
            .dependencyConstraint([type: 'pom', scope: 'import'], first)
            .dependencyConstraint([type: 'pom', scope: 'import'], second).publish()

        blockingServer.expect(blockingServer.get(root.pom.path).sendFile(root.pom.file))
        def imports = blockingServer.expectConcurrentAndBlock(
            blockingServer.get(first.pom.path).sendFile(first.pom.file),
            blockingServer.get(second.pom.path).sendFile(second.pom.file)
        )
        blockingServer.expect(blockingServer.get(library.pom.path).sendFile(library.pom.file))
        blockingServer.expect(blockingServer.get(library.artifact.path).sendFile(library.artifact.file))

        when:
        def build = executer.withArguments('--max-workers=1').withTasks('resolve').start()
        imports.waitForAllPendingCalls()
        imports.release(second.pom.path)
        imports.release(first.pom.path)
        def result = build.waitForFinish()

        then:
        result.assertOutputContains('files: [library-1.jar]')
    }

    def "BOM prefetch and POM module overlap run together by default with one worker"() {
        given:
        buildFile << """
            repositories.clear()
            repositories.maven {
                url = '$blockingServer.uri'
                metadataSources { mavenPom() }
            }
        """
        def child = mavenRepo.module('test', 'child', '1').withModuleMetadata().publish()
        def library = mavenRepo.module('test', 'library', '1').withModuleMetadata().dependsOn(child).publish()
        def first = mavenRepo.module('test', 'first', '1').hasType('pom').dependencyConstraint(library).publish()
        def second = mavenRepo.module('test', 'second', '1').hasType('pom').publish()
        def root = mavenRepo.module('test', 'root', '1').hasType('pom')
            .dependencyConstraint([type: 'pom', scope: 'import'], first)
            .dependencyConstraint([type: 'pom', scope: 'import'], second).publish()
        blockingServer.expect(blockingServer.get(root.pom.path).sendFile(root.pom.file))
        blockingServer.expectConcurrent(
            blockingServer.get(first.pom.path).sendFile(first.pom.file),
            blockingServer.get(second.pom.path).sendFile(second.pom.file)
        )
        blockingServer.expect(blockingServer.get(library.pom.path).sendFile(library.pom.file))
        blockingServer.expect(blockingServer.get(library.moduleMetadata.path).sendFile(library.moduleMetadata.file))
        blockingServer.expectConcurrent(
            blockingServer.get(child.pom.path).sendFile(child.pom.file),
            blockingServer.get(child.moduleMetadata.path).sendFile(child.moduleMetadata.file)
        )
        blockingServer.expect(blockingServer.get(library.artifact.path).sendFile(library.artifact.file))
        blockingServer.expect(blockingServer.get(child.artifact.path).sendFile(child.artifact.file))

        when:
        executer.withArguments('--max-workers=1')
        succeeds('resolve')

        then:
        outputContains('files: [child-1.jar, library-1.jar]')
    }

    def "disabled prefetch retains sequential requests and the result remains usable offline"() {
        given:
        def library = mavenRepo.module('test', 'library', '1').publish()
        def first = mavenRepo.module('test', 'first', '1').hasType('pom').dependencyConstraint(library).publish()
        def second = mavenRepo.module('test', 'second', '1').hasType('pom').publish()
        def root = mavenRepo.module('test', 'root', '1').hasType('pom')
            .dependencyConstraint([type: 'pom', scope: 'import'], first)
            .dependencyConstraint([type: 'pom', scope: 'import'], second).publish()
        [root.pom, first.pom, second.pom, library.pom, library.artifact].each {
            blockingServer.expect(blockingServer.get(it.path).sendFile(it.file))
        }

        when:
        executer.withArguments('--max-workers=1', '-Dorg.gradle.internal.resolve.metadata.parallelBom=false')
        succeeds('resolve')

        then:
        outputContains('files: [library-1.jar]')

        when:
        executer.withArguments('--offline', '--max-workers=1')
        succeeds('resolve')

        then:
        outputContains('files: [library-1.jar]')
    }

    def "prefetch from the current repository does not override BOMs owned by an earlier repository"() {
        given:
        def library = mavenRepo.module('test', 'library', '1').publish()
        def first = mavenRepo.module('test', 'first', '1').hasType('pom').dependencyConstraint(library).publish()
        def second = mavenRepo.module('test', 'second', '1').hasType('pom').publish()
        def root = mavenRepo.module('test', 'root', '1').hasType('pom')
            .dependencyConstraint([type: 'pom', scope: 'import'], first)
            .dependencyConstraint([type: 'pom', scope: 'import'], second).publish()
        def earlierRepo = mavenHttpRepo('earlier')
        def authoritativeLibrary = earlierRepo.module('test', 'library', '3').publish()
        def authoritativeFirst = earlierRepo.module('test', 'first', '1').hasType('pom').dependencyConstraint(authoritativeLibrary).publish()
        def authoritativeSecond = earlierRepo.module('test', 'second', '1').hasType('pom').publishWithChangedContent()
        buildFile << """
            def earlierRepository = repositories.maven {
                url = '$earlierRepo.uri'
                metadataSources {
                    mavenPom()
                    ignoreGradleMetadataRedirection()
                }
            }
            repositories.remove(earlierRepository)
            repositories.addFirst(earlierRepository)
        """
        earlierRepo.module('test', 'root', '1').pom.expectGetMissing()
        authoritativeFirst.pom.expectHead()
        authoritativeFirst.pom.sha1.expectGet()
        authoritativeFirst.pom.expectGet()
        authoritativeSecond.pom.expectHead()
        authoritativeSecond.pom.sha1.expectGet()
        authoritativeSecond.pom.expectGet()
        authoritativeLibrary.pom.expectGet()
        authoritativeLibrary.artifact.expectGet()
        blockingServer.expect(blockingServer.get(root.pom.path).sendFile(root.pom.file))
        blockingServer.expectConcurrent(
            blockingServer.get(first.pom.path).sendFile(first.pom.file),
            blockingServer.get(second.pom.path).sendFile(second.pom.file)
        )

        when:
        executer.withArguments('--max-workers=1')
        succeeds('resolve')

        then:
        outputContains('files: [library-3.jar]')
    }
}