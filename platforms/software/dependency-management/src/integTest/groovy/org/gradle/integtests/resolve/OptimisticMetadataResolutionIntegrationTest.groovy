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

package org.gradle.integtests.resolve

import org.gradle.integtests.fixtures.AbstractHttpDependencyResolutionTest
import org.gradle.test.fixtures.server.http.BlockingHttpServer
import org.junit.Rule

class OptimisticMetadataResolutionIntegrationTest extends AbstractHttpDependencyResolutionTest {
    @Rule
    public BlockingHttpServer blockingServer = new BlockingHttpServer()

    def setup() {
        blockingServer.start()

        buildFile << """
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
            tasks.register('resolve') {
                def rootComponent = configurations.conf.incoming.resolutionResult.rootComponent
                doLast {
                    def pending = [rootComponent.get()]
                    def visited = [] as Set
                    def components = []
                    def edges = []
                    def unresolved = []
                    while (!pending.empty) {
                        def component = pending.remove(0)
                        if (visited.add(component.id)) {
                            if (component.id instanceof ModuleComponentIdentifier) {
                                components.add(component.id.displayName)
                            }
                            component.dependencies.each { dependency ->
                                if (dependency instanceof ResolvedDependencyResult) {
                                    edges.add(dependency.requested.displayName + ' -> ' + dependency.selected.id.displayName)
                                    pending.add(dependency.selected)
                                } else {
                                    unresolved.add(dependency.requested.displayName)
                                }
                            }
                        }
                    }
                    println 'components: ' + components.sort()
                    println 'edges: ' + edges.sort()
                    println 'unresolved: ' + unresolved.sort()
                }
            }
        """
    }

    def "fetches child #format metadata by default while a root sibling is blocked and reuses metadata without downloading artifacts"() {
        given:
        def child = mavenRepo.module('test', 'child', '1.0').withModuleMetadata().publish()
        def parent = mavenRepo.module('test', 'parent', '1.0').dependsOn('test', 'child', '1.0').withModuleMetadata().publish()
        def sibling = mavenRepo.module('test', 'sibling', '1.0').withModuleMetadata().publish()
        if (format == 'module') {
            buildFile << """
                repositories.withType(MavenArtifactRepository).configureEach { repository ->
                    repository.metadataSources { gradleMetadata() }
                }
            """
        }
        buildFile << """
            dependencies {
                conf 'test:parent:1.0'
                conf 'test:sibling:1.0'
            }
        """

        def parentMetadata = format == 'pom' ? parent.pom : parent.moduleMetadata
        def siblingMetadata = format == 'pom' ? sibling.pom : sibling.moduleMetadata
        def childMetadata = format == 'pom' ? child.pom : child.moduleMetadata
        def roots = blockingServer.expectConcurrentAndBlock(
            blockingServer.get(parentMetadata.path).sendFile(parentMetadata.file),
            blockingServer.get(siblingMetadata.path).sendFile(siblingMetadata.file)
        )
        def childRequest = blockingServer.expectAndBlock(
            blockingServer.get(childMetadata.path).sendFile(childMetadata.file)
        )

        when:
        executer.withArguments('--max-workers=1')
        def build = executer.withTasks('resolve').start()
        roots.waitForAllPendingCalls()
        roots.release(parentMetadata.path)

        // The child must be requested before the unrelated root's metadata is available.
        childRequest.waitForAllPendingCalls()
        childRequest.releaseAll()
        roots.release(siblingMetadata.path)
        def result = build.waitForFinish()

        then:
        result.assertOutputContains('components: [test:child:1.0, test:parent:1.0, test:sibling:1.0]')
        result.assertOutputContains('unresolved: []')

        when:
        // No requests, including artifact requests, are allowed on the next online resolution.
        blockingServer.resetExpectations()
        // Resolve a fresh graph rather than reusing a configuration-cached resolution result.
        executer.withArguments('--max-workers=1', '--no-configuration-cache')
        succeeds('resolve')

        then:
        outputContains('components: [test:child:1.0, test:parent:1.0, test:sibling:1.0]')
        outputContains('unresolved: []')

        where:
        format << ['pom', 'module']
    }

    def "JVM lookahead skips native metadata while fetching useful children before a blocked sibling"() {
        given:
        def child = mavenRepo.module('test', 'child-jvm', '1.0').withModuleMetadata().publish()
        def parent = mavenRepo.module('test', 'parent', '1.0').withModuleMetadata().withoutDefaultVariants()
            .variant('mingw', ['org.jetbrains.kotlin.platform.type': 'native']) {
                dependsOn('test', 'child-mingwx64', '1.0')
            }
            .variant('jvm', ['org.jetbrains.kotlin.platform.type': 'jvm']) {
                dependsOn('test', 'child-jvm', '1.0')
            }.publish()
        def sibling = mavenRepo.module('test', 'sibling', '1.0').withModuleMetadata().publish()
        buildFile << """
            repositories.withType(MavenArtifactRepository).configureEach { repository ->
                repository.metadataSources { gradleMetadata() }
            }
            configurations.conf.attributes {
                attribute(Attribute.of('org.jetbrains.kotlin.platform.type', String), 'jvm')
                attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.JAVA_RUNTIME))
            }
            dependencies {
                conf 'test:parent:1.0'
                conf 'test:sibling:1.0'
            }
        """
        def roots = blockingServer.expectConcurrentAndBlock(
            blockingServer.get(parent.moduleMetadata.path).sendFile(parent.moduleMetadata.file),
            blockingServer.get(sibling.moduleMetadata.path).sendFile(sibling.moduleMetadata.file)
        )
        // Any request for child-mingwx64 (or for artifacts) is unexpected and fails the test.
        def childRequest = blockingServer.expectAndBlock(
            blockingServer.get(child.moduleMetadata.path).sendFile(child.moduleMetadata.file)
        )

        when:
        executer.withArguments('--max-workers=1', '-Dorg.gradle.internal.resolve.metadata.lookahead.maxPending=1')
        def build = executer.withTasks('resolve').start()
        roots.waitForAllPendingCalls()
        roots.release(parent.moduleMetadata.path)
        childRequest.waitForAllPendingCalls()
        childRequest.releaseAll()
        roots.release(sibling.moduleMetadata.path)
        def result = build.waitForFinish()

        then:
        result.assertOutputContains('components: [test:child-jvm:1.0, test:parent:1.0, test:sibling:1.0]')
        result.assertOutputContains('unresolved: []')
    }

    def "can opt out of speculative metadata for a conflict loser"() {
        given:
        def winner = mavenRepo.module('test', 'lib', '2.0').publish()
        buildFile << """
            dependencies {
                conf 'test:lib:1.0'
                conf 'test:lib:2.0'
            }
        """
        blockingServer.expect(blockingServer.get(winner.pom.path).sendFile(winner.pom.file))

        when:
        executer.withArguments('--max-workers=1', '-Dorg.gradle.internal.resolve.metadata.lookahead=false')
        succeeds('resolve')

        then:
        outputContains('components: [test:lib:2.0]')
        outputContains('edges: [test:lib:1.0 -> test:lib:2.0, test:lib:2.0 -> test:lib:2.0]')
        outputContains('unresolved: []')
    }

    def "refills a one-request lookahead window while an unrelated root remains blocked"() {
        given:
        def first = mavenRepo.module('test', 'first', '1.0').publish()
        def second = mavenRepo.module('test', 'second', '1.0').publish()
        def parent = mavenRepo.module('test', 'parent', '1.0')
            .dependsOn('test', 'first', '1.0')
            .dependsOn('test', 'second', '1.0').publish()
        def sibling = mavenRepo.module('test', 'sibling', '1.0').publish()
        buildFile << """
            dependencies {
                conf 'test:parent:1.0'
                conf 'test:sibling:1.0'
            }
        """
        def roots = blockingServer.expectConcurrentAndBlock(
            blockingServer.get(parent.pom.path).sendFile(parent.pom.file),
            blockingServer.get(sibling.pom.path).sendFile(sibling.pom.file)
        )
        def firstRequest = blockingServer.expectAndBlock(blockingServer.get(first.pom.path).sendFile(first.pom.file))
        def secondRequest = blockingServer.expectAndBlock(blockingServer.get(second.pom.path).sendFile(second.pom.file))

        when:
        executer.withArguments('--max-workers=1', '-Dorg.gradle.internal.resolve.metadata.lookahead.maxPending=1')
        def build = executer.withTasks('resolve').start()
        roots.waitForAllPendingCalls()
        roots.release(parent.pom.path)
        firstRequest.waitForAllPendingCalls()
        firstRequest.releaseAll()
        secondRequest.waitForAllPendingCalls()
        secondRequest.releaseAll()
        roots.release(sibling.pom.path)
        def result = build.waitForFinish()

        then:
        result.assertOutputContains('components: [test:first:1.0, test:parent:1.0, test:second:1.0, test:sibling:1.0]')
        result.assertOutputContains('unresolved: []')
    }

    def "missing speculative metadata for a conflict loser does not fail the winning graph with root versions #versions"() {
        given:
        def loser = mavenRepo.module('test', 'lib', '1.0')
        def winner = mavenRepo.module('test', 'lib', '2.0').publish()
        buildFile << """
            dependencies {
                ${versions.collect { "conf 'test:lib:$it'" }.join('\n')}
            }
        """

        // Require speculation of the losing version, without prescribing which request arrives first.
        blockingServer.expectConcurrent(
            blockingServer.get(loser.pom.path).missing(),
            blockingServer.get(winner.pom.path).sendFile(winner.pom.file)
        )

        when:
        executer.withArguments('--max-workers=1', '-Dorg.gradle.internal.resolve.metadata.lookahead=true')
        succeeds('resolve')

        then:
        outputContains('components: [test:lib:2.0]')
        outputContains('edges: [test:lib:1.0 -> test:lib:2.0, test:lib:2.0 -> test:lib:2.0]')
        outputContains('unresolved: []')

        where:
        versions << [['1.0', '2.0'], ['2.0', '1.0']]
    }
}