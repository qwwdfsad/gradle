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
package org.gradle.api.internal.artifacts.ivyservice.ivyresolve.parser

import org.gradle.api.Action
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ComponentResolvers
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.DefaultVersionComparator
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.DefaultVersionSelectorScheme
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionParser
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelector
import org.gradle.api.internal.artifacts.repositories.resolver.ExternalResourceArtifactResolver
import org.gradle.api.internal.artifacts.repositories.resolver.ExternalResourceResolverDescriptorParseContext
import org.gradle.api.internal.component.ArtifactType
import org.gradle.internal.component.model.DefaultModuleDescriptorArtifactMetadata
import org.gradle.internal.component.model.MutableModuleSources
import org.gradle.internal.hash.ChecksumService
import org.gradle.internal.operations.BuildOperationContext
import org.gradle.internal.operations.BuildOperationExecutor
import org.gradle.internal.operations.BuildOperationQueue
import org.gradle.internal.operations.RunnableBuildOperation
import org.gradle.internal.resource.local.LocallyAvailableExternalResource
import org.gradle.internal.work.WorkerLeaseService

class ParallelBomResolutionTest extends AbstractGradlePomModuleDescriptorParserTest {
    private static final String PROPERTY = 'org.gradle.internal.resolve.metadata.parallelBom'
    def executor = Mock(BuildOperationExecutor)
    def workerLeaseService = Mock(WorkerLeaseService)
    boolean workerLeaseHeld
    def operationContext = Stub(BuildOperationContext)
    def batchSizes = []
    def events = []
    String previousProperty
    def parallelParser = new GradlePomModuleDescriptorParser(
        new DefaultVersionSelectorScheme(new DefaultVersionComparator(), new VersionParser()),
        moduleIdentifierFactory, fileRepository, mavenMetadataFactory, executor, workerLeaseService
    )

    def setup() {
        previousProperty = System.getProperty(PROPERTY)
        System.clearProperty(PROPERTY)
        workerLeaseService.runAsWorkerThread(_ as Runnable) >> { Runnable action ->
            assert !workerLeaseHeld
            workerLeaseHeld = true
            try {
                action.run()
            } finally {
                workerLeaseHeld = false
            }
        }
        executor.runAll(_) >> { Action action ->
            assert workerLeaseHeld
            def operations = []
            def queue = Stub(BuildOperationQueue) {
                addUnconstrained(_) >> { RunnableBuildOperation operation -> operations.add(operation) }
            }
            action.execute(queue)
            batchSizes.add(operations.size())
            operations.reverseEach { it.run(operationContext) }
        }
    }

    def cleanup() {
        assert !workerLeaseHeld
        if (previousProperty == null) {
            System.clearProperty(PROPERTY)
        } else {
            System.setProperty(PROPERTY, previousProperty)
        }
    }

    def "prefetch completion order and failures do not change first import wins"() {
        given:
        pomFile.text = pom('root', imported('first') + imported('second'))
        def first = resource('first', dependency('library', '1'))
        def second = resource('second', dependency('library', '2'))
        parseContext.prefetchPom(_) >> { ModuleComponentIdentifier id ->
            events.add("prefetch:${id.module}".toString())
            if (prefetchFails) {
                throw new IllegalStateException('speculative failure')
            }
        }
        parseContext.getMetaDataArtifact(_, _, _) >> { ModuleComponentSelector selector, VersionSelector acceptor, ArtifactType type ->
            assert !workerLeaseHeld
            events.add("parse:${selector.module}".toString())
            selector.module == 'first' ? first : second
        }

        when:
        def result = parallelParser.parseMetaData(parseContext, pomFile, true).result

        then:
        events == ['prefetch:second', 'prefetch:first', 'parse:first', 'parse:second']
        result.dependencies*.selector == [moduleId('test', 'library', '1')]

        where:
        prefetchFails << [false, true]
    }

    def "only fixed resolved non-snapshot import POM coordinates are prefetched for #version"() {
        given:
        pomFile.text = pom('root', imported('first') + imported('second') + imported('candidate', version) + dependency('ordinary', '1'))
        def empty = resource('empty')
        def prefetched = []
        def resolved = []
        parseContext.prefetchPom(_) >> { ModuleComponentIdentifier id -> prefetched.add(id.module) }
        parseContext.getMetaDataArtifact(_, _, _) >> { ModuleComponentSelector selector, VersionSelector acceptor, ArtifactType type ->
            resolved.add(selector.module)
            empty
        }

        when:
        parallelParser.parseMetaData(parseContext, pomFile, true)

        then:
        prefetched.toSet() == (eligible ? ['first', 'second', 'candidate'] : ['first', 'second']).toSet()
        resolved == ['first', 'second', 'candidate']

        where:
        version         | eligible
        '1.2'           | true
        '1.+'           | false
        '[1,2)'         | false
        '[1]'           | false
        'LATEST'        | false
        'RELEASE'       | false
        '1-SNAPSHOT'    | false
        '${unresolved}' | false
    }

    def "prefetch is enabled by default with an opt out and the legacy parser constructor remains supported"() {
        given:
        if (propertyValue == null) {
            System.clearProperty(PROPERTY)
        } else {
            System.setProperty(PROPERTY, propertyValue)
        }
        pomFile.text = pom('root', imported('first') + imported('second'))
        def empty = resource('empty')

        when:
        (legacy ? parser : parallelParser).parseMetaData(parseContext, pomFile, true)

        then:
        2 * parseContext.getMetaDataArtifact(_, _, _) >> empty
        (enabled ? 2 : 0) * parseContext.prefetchPom(_)
        batchSizes == (enabled ? [2] : [])

        where:
        propertyValue | legacy | enabled
        null          | false  | true
        'true'        | false  | true
        'false'       | false  | false
        null          | true   | false
        'true'        | true   | false
    }

    def "bounds batches and performs authoritative resolution in declaration order"() {
        given:
        def names = (1..18).collect { "bom$it".toString() }
        pomFile.text = pom('root', names.collect { imported(it) }.join())
        def empty = resource('empty')
        def resolved = []
        parseContext.getMetaDataArtifact(_, _, _) >> { ModuleComponentSelector selector, VersionSelector acceptor, ArtifactType type ->
            resolved.add(selector.module)
            empty
        }

        when:
        parallelParser.parseMetaData(parseContext, pomFile, true)

        then:
        18 * parseContext.prefetchPom(_)
        batchSizes == [8, 8, 2]
        resolved == names
    }

    def "busy admission skips speculation without blocking and is released after the batch"() {
        given:
        pomFile.text = pom('root', imported('first') + imported('second'))
        def empty = resource('empty')

        when:
        2.times { parallelParser.parseMetaData(parseContext, pomFile, true) }

        then:
        2 * executor.runAll(_) >> { Action action ->
            // Simulate another caller arriving while this parser's prefetch admission is occupied.
            parallelParser.parseMetaData(parseContext, pomFile, true)
            def queue = Stub(BuildOperationQueue) {
                addUnconstrained(_) >> { RunnableBuildOperation operation -> operation.run(operationContext) }
            }
            action.execute(queue)
        }
        4 * parseContext.prefetchPom(_)
        8 * parseContext.getMetaDataArtifact(_, _, _) >> empty
    }

    def "first authoritative failure wins and later batches are not prefetched"() {
        given:
        pomFile.text = pom('root', (1..10).collect { imported("bom$it") }.join())
        def failure = new IllegalStateException('first authoritative failure')

        when:
        parallelParser.parseMetaData(parseContext, pomFile, true)

        then:
        8 * parseContext.prefetchPom(_) >> { throw new IllegalStateException('ignored speculative failure') }
        1 * parseContext.getMetaDataArtifact({ it.module == 'bom1' }, _, _) >> { throw failure }
        0 * parseContext.getMetaDataArtifact(_, _, _)
        def actual = thrown(MetaDataParseException)
        actual.cause.is(failure)
        batchSizes == [8]
    }

    def "contexts implementing only authoritative resolution retain the default no-op hook"() {
        given:
        pomFile.text = pom('root', imported('first') + imported('second'))
        def empty = resource('empty')
        def resolved = []
        def legacyContext = new DescriptorParseContext() {
            @Override
            LocallyAvailableExternalResource getMetaDataArtifact(ModuleComponentIdentifier id, ArtifactType type) {
                throw new UnsupportedOperationException()
            }

            @Override
            LocallyAvailableExternalResource getMetaDataArtifact(ModuleComponentSelector selector, VersionSelector acceptor, ArtifactType type) {
                resolved.add(selector.module)
                empty
            }
        }

        when:
        parallelParser.parseMetaData(legacyContext, pomFile, true)

        then:
        resolved == ['first', 'second']
    }

    def "raw prefetch uses a descriptor artifact and does not resolve components or append metadata sources"() {
        given:
        def components = Mock(ComponentResolvers)
        def rawResolver = Mock(ExternalResourceArtifactResolver)
        def checksums = Mock(ChecksumService)
        def sources = Mock(MutableModuleSources)
        def context = new ExternalResourceResolverDescriptorParseContext(components, fileRepository, checksums, rawResolver)
        def id = componentId('test', 'bom', '1')

        when:
        context.prefetchPom(id)
        context.appendSources(sources)

        then:
        1 * rawResolver.resolveArtifact({
            it instanceof DefaultModuleDescriptorArtifactMetadata && it.componentId == id && it.name.type == 'pom' && it.name.extension == 'pom'
        }, _)
        0 * components._
        0 * checksums._
        0 * sources._
    }

    private LocallyAvailableExternalResource resource(String name, String management = '') {
        def file = tmpDir.file("${name}.pom")
        file.text = pom(name, management)
        asResource(file)
    }

    private static String pom(String name, String management) {
        """<project><modelVersion>4.0.0</modelVersion>
            <groupId>test</groupId><artifactId>$name</artifactId><version>1</version><packaging>pom</packaging>
            <dependencyManagement><dependencies>$management</dependencies></dependencyManagement>
        </project>"""
    }

    private static String imported(String name, String version = '1') {
        dependency(name, version, '<type>pom</type><scope>import</scope>')
    }

    private static String dependency(String name, String version, String extra = '') {
        """<dependency><groupId>test</groupId><artifactId>$name</artifactId><version>$version</version>$extra</dependency>"""
    }
}