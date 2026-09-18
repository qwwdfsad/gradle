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
import org.gradle.integtests.fixtures.modes.UnsupportedWithConfigurationCache
import org.gradle.test.fixtures.server.http.BlockingHttpServer
import org.junit.Rule

@UnsupportedWithConfigurationCache(because = "Artifact resolution queries are not supported by the configuration cache")
class ParallelArtifactResolutionQueryIntegrationTest extends AbstractHttpDependencyResolutionTest {
    @Rule
    public BlockingHttpServer blockingServer = new BlockingHttpServer()

    def setup() {
        blockingServer.start()
    }

    def "overlaps source probes and downloads with one worker and reuses warm and offline results"() {
        given:
        queryBuild(blockingServer.uri, ['first', 'second'])
        def first = mavenRepo.module('test', 'first', '1').artifact(classifier: 'sources')
        def second = mavenRepo.module('test', 'second', '1').artifact(classifier: 'sources')
        def sources = [first, second].collect { it.getArtifact(classifier: 'sources') }
        [first, second].each {
            it.publish()
            blockingServer.expect(blockingServer.get(it.pom.path).sendFile(it.pom.file))
        }
        def probes = blockingServer.expectConcurrentAndBlock(*sources.collect { blockingServer.head(it.path) })
        def downloads = blockingServer.expectConcurrentAndBlock(*sources.collect { blockingServer.get(it.path).sendFile(it.file) })

        when:
        def build = executer.withArguments('--max-workers=1').withTasks('query').start()
        probes.waitForAllPendingCalls()
        probes.releaseAll()
        downloads.waitForAllPendingCalls()
        downloads.releaseAll()
        def result = build.waitForFinish()

        then:
        result.assertOutputContains('query: [first:[first-1-sources.jar], second:[second-1-sources.jar]]')

        when:
        executer.withArguments('--max-workers=1')
        succeeds('query')

        then:
        outputContains('query: [first:[first-1-sources.jar], second:[second-1-sources.jar]]')

        when:
        executer.withArguments('--max-workers=1', '--offline')
        succeeds('query')

        then:
        outputContains('query: [first:[first-1-sources.jar], second:[second-1-sources.jar]]')
    }

    def "opt out retains sequential metadata probes and downloads"() {
        given:
        queryBuild(blockingServer.uri, ['first', 'second'])
        ['first', 'second'].each { name ->
            def module = mavenRepo.module('test', name, '1').artifact(classifier: 'sources')
            def sources = module.getArtifact(classifier: 'sources')
            module.publish()
            blockingServer.expect(blockingServer.get(module.pom.path).sendFile(module.pom.file))
            blockingServer.expect(blockingServer.head(sources.path))
            blockingServer.expect(blockingServer.get(sources.path).sendFile(sources.file))
        }

        when:
        executer.withArguments('--max-workers=1', '-Dorg.gradle.internal.resolve.artifacts.parallelQuery=false')
        succeeds('query')

        then:
        outputContains('query: [first:[first-1-sources.jar], second:[second-1-sources.jar]]')
    }

    def "keeps missing optional artifacts distinct from component and artifact failures"() {
        given:
        queryBuild(mavenHttpRepo.uri, ['success', 'missing', 'downloadFailure', 'metadataFailure'])
        def success = mavenHttpRepo.module('test', 'success', '1')
        def successfulSources = success.artifact(classifier: 'sources')
        success.publish()
        def missing = mavenHttpRepo.module('test', 'missing', '1').publish()
        def downloadFailure = mavenHttpRepo.module('test', 'downloadFailure', '1')
        def failedSources = downloadFailure.artifact(classifier: 'sources')
        downloadFailure.publish()
        [success, missing, downloadFailure].each { it.pom.expectGet() }
        mavenHttpRepo.module('test', 'metadataFailure', '1').pom.expectGetMissing()
        successfulSources.expectHead()
        successfulSources.expectGet()
        missing.artifact(classifier: 'sources').expectHeadMissing()
        failedSources.expectHead()
        failedSources.expectGetMissing()

        when:
        executer.withArguments('--max-workers=1')
        succeeds('query')

        then:
        outputContains('query: [success:[success-1-sources.jar], missing:[], downloadFailure:[artifact failure], metadataFailure:component failure]')
    }

    private void queryBuild(URI repository, List<String> modules) {
        buildFile << """
            import org.gradle.api.artifacts.result.UnresolvedArtifactResult
            import org.gradle.api.artifacts.result.UnresolvedComponentResult
            import org.gradle.jvm.JvmLibrary
            import org.gradle.language.base.artifact.SourcesArtifact

            repositories {
                maven {
                    url = '$repository'
                    metadataSources {
                        mavenPom()
                        ignoreGradleMetadataRedirection()
                    }
                }
            }
            tasks.register('query') {
                doLast {
                    def query = dependencies.createArtifactResolutionQuery()
                    ${modules.collect { "query.forModule('test', '$it', '1')" }.join('\n')}
                    def result = query.withArtifacts(JvmLibrary, SourcesArtifact).execute()
                    println 'query: ' + result.components.collect { component ->
                        def artifacts = component instanceof UnresolvedComponentResult ? 'component failure' :
                            component.getArtifacts(SourcesArtifact).collect { artifact ->
                                artifact instanceof UnresolvedArtifactResult ? 'artifact failure' : artifact.file.name
                            }
                        component.id.module + ':' + artifacts
                    }
                }
            }
        """
    }
}