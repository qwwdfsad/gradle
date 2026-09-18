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

class ParallelMetadataRedirectionIntegrationTest extends AbstractHttpDependencyResolutionTest {
    @Rule
    public BlockingHttpServer blockingServer = new BlockingHttpServer()

    def setup() {
        blockingServer.start()
        executer.withArguments('--max-workers=1')
        buildFile << """
            repositories {
                maven {
                    url = '$blockingServer.uri'
                    metadataSources { mavenPom() }
                }
            }
            configurations { conf }
            dependencies { conf 'test:lib:1.0' }
            tasks.register('resolve') {
                def root = configurations.conf.incoming.resolutionResult.rootComponent
                doLast {
                    def dependency = root.get().dependencies.iterator().next()
                    if (dependency instanceof UnresolvedDependencyResult) {
                        throw dependency.failure
                    }
                    println 'selected: ' + dependency.selected.id.displayName
                }
            }
        """
    }

    def "overlaps POM and module downloads by default with one worker and reuses warm metadata"() {
        given:
        def lib = mavenRepo.module('test', 'lib', '1.0').withModuleMetadata().publish()
        blockingServer.expectConcurrent(
            blockingServer.get(lib.pom.path).sendFile(lib.pom.file),
            blockingServer.get(lib.moduleMetadata.path).sendFile(lib.moduleMetadata.file)
        )

        when:
        succeeds('resolve')

        then:
        outputContains('selected: test:lib:1.0')

        when:
        executer.withArguments('--max-workers=1')
        succeeds('resolve')

        then:
        outputContains('selected: test:lib:1.0')
    }

    def "does not parse unused module metadata without a POM marker"() {
        given:
        def lib = mavenRepo.module('test', 'lib', '1.0').publish()
        blockingServer.expectConcurrent(
            blockingServer.get(lib.pom.path).sendFile(lib.pom.file),
            blockingServer.get(lib.moduleMetadata.path).send('invalid unused module metadata')
        )

        when:
        succeeds('resolve')

        then:
        outputContains('selected: test:lib:1.0')
    }

    def "missing module preserves POM fallback with marker #marker"() {
        given:
        def lib = mavenRepo.module('test', 'lib', '1.0')
        if (marker) {
            lib.withModuleMetadata()
        }
        lib.publish()
        blockingServer.expectConcurrent(
            blockingServer.get(lib.pom.path).sendFile(lib.pom.file),
            blockingServer.get(lib.moduleMetadata.path).missing()
        )

        when:
        succeeds('resolve')

        then:
        outputContains('selected: test:lib:1.0')

        where:
        marker << [true, false]
    }

    def "ignore redirection also disables speculative downloads"() {
        given:
        def lib = mavenRepo.module('test', 'lib', '1.0').withModuleMetadata().publish()
        buildFile << """
            repositories.withType(MavenArtifactRepository).configureEach {
                it.metadataSources { sources -> sources.mavenPom(); sources.ignoreGradleMetadataRedirection() }
            }
        """
        blockingServer.expect(blockingServer.get(lib.pom.path).sendFile(lib.pom.file))

        when:
        succeeds('resolve')

        then:
        outputContains('selected: test:lib:1.0')
    }

    def "opt out retains sequential POM then module requests"() {
        given:
        def lib = mavenRepo.module('test', 'lib', '1.0').withModuleMetadata().publish()
        executer.withArguments('-Dorg.gradle.internal.resolve.metadata.parallelRedirect=false', '--max-workers=1')
        blockingServer.expect(blockingServer.get(lib.pom.path).sendFile(lib.pom.file))
        blockingServer.expect(blockingServer.get(lib.moduleMetadata.path).sendFile(lib.moduleMetadata.file))

        when:
        succeeds('resolve')

        then:
        outputContains('selected: test:lib:1.0')
    }
}