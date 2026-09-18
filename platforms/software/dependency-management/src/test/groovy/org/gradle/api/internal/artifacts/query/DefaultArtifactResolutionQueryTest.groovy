/*
 * Copyright 2014 the original author or authors.
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

package org.gradle.api.internal.artifacts.query

import org.gradle.api.Action
import org.gradle.api.artifacts.component.ComponentArtifactIdentifier
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ArtifactResolutionResult
import org.gradle.api.artifacts.result.UnresolvedArtifactResult
import org.gradle.api.artifacts.result.UnresolvedComponentResult
import org.gradle.api.component.Artifact
import org.gradle.api.component.Component
import org.gradle.api.internal.artifacts.DefaultModuleIdentifier
import org.gradle.api.internal.artifacts.configurations.ResolutionStrategyFactory
import org.gradle.api.internal.artifacts.dsl.ComponentMetadataHandlerInternal
import org.gradle.api.internal.artifacts.dsl.ComponentMetadataRulesSupplier
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ComponentResolvers
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ExternalModuleComponentResolverFactory
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvableArtifact
import org.gradle.api.internal.artifacts.result.DefaultResolvedArtifactResult
import org.gradle.api.internal.component.ArtifactType
import org.gradle.api.internal.component.ComponentTypeRegistration
import org.gradle.api.internal.component.ComponentTypeRegistry
import org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier
import org.gradle.internal.component.model.ComponentArtifactMetadata
import org.gradle.internal.component.model.ComponentArtifactResolveMetadata
import org.gradle.internal.component.model.ComponentArtifactResolveState
import org.gradle.internal.component.model.ComponentGraphResolveState
import org.gradle.internal.component.model.ComponentGraphSpecificResolveState
import org.gradle.internal.component.model.ComponentOverrideMetadata
import org.gradle.internal.operations.BuildOperationContext
import org.gradle.internal.operations.BuildOperationExecutor
import org.gradle.internal.operations.BuildOperationQueue
import org.gradle.internal.operations.RunnableBuildOperation
import org.gradle.internal.resolve.resolver.ArtifactResolver
import org.gradle.internal.resolve.resolver.ComponentMetaDataResolver
import org.gradle.internal.resolve.result.BuildableArtifactResolveResult
import org.gradle.internal.resolve.result.BuildableArtifactSetResolveResult
import org.gradle.internal.resolve.result.BuildableComponentResolveResult
import org.gradle.internal.work.WorkerLeaseService
import spock.lang.Shared
import spock.lang.Specification

class DefaultArtifactResolutionQueryTest extends Specification {

    private static final String PARALLEL_QUERY_PROPERTY = 'org.gradle.internal.resolve.artifacts.parallelQuery'

    def resolutionStrategyFactory = Stub(ResolutionStrategyFactory)
    def externalResolverFactory = Mock(ExternalModuleComponentResolverFactory)
    def componentMetadataRulesSupplier = Stub(ComponentMetadataRulesSupplier)
    def componentMetadataHandler = Stub(ComponentMetadataHandlerInternal)
    def componentTypeRegistry = Mock(ComponentTypeRegistry)
    def artifactResolver = Mock(ArtifactResolver)
    def repositoryChain = Mock(ComponentResolvers)
    def componentMetaDataResolver = Mock(ComponentMetaDataResolver)
    def buildOperationExecutor = Mock(BuildOperationExecutor)
    def workerLeaseService = Mock(WorkerLeaseService)
    def batchSizes = []
    def events = []
    boolean workerLeaseHeld
    boolean retrieving
    String previousParallelQueryProperty

    def setup() {
        previousParallelQueryProperty = System.getProperty(PARALLEL_QUERY_PROPERTY)
        System.clearProperty(PARALLEL_QUERY_PROPERTY)
        workerLeaseService.runAsWorkerThread(_ as Runnable) >> { Runnable action ->
            workerLeaseHeld = true
            try {
                action.run()
            } finally {
                workerLeaseHeld = false
            }
        }
        buildOperationExecutor.runAll(_) >> { Action action ->
            assert workerLeaseHeld
            def operations = []
            action.execute(Stub(BuildOperationQueue) {
                addUnconstrained(_) >> { RunnableBuildOperation operation -> operations.add(operation) }
            })
            batchSizes.add(operations.size())
            retrieving = true
            try {
                operations.reverseEach { it.run(Stub(BuildOperationContext)) }
            } finally {
                retrieving = false
            }
        }
    }

    def cleanup() {
        if (previousParallelQueryProperty == null) {
            System.clearProperty(PARALLEL_QUERY_PROPERTY)
        } else {
            System.setProperty(PARALLEL_QUERY_PROPERTY, previousParallelQueryProperty)
        }
    }

    @Shared
    ComponentTypeRegistry testComponentTypeRegistry = createTestComponentTypeRegistry()

    def "cannot call withArtifacts multiple times"() {
        def query = createArtifactResolutionQuery(componentTypeRegistry)

        given:
        query.withArtifacts(Component, Artifact)

        when:
        query.withArtifacts(Component, Artifact)

        then:
        def e = thrown IllegalStateException
        e.message == "Cannot specify component type multiple times."
    }

    def "cannot call execute without first specifying arguments"() {
        def query = createArtifactResolutionQuery(componentTypeRegistry)

        when:
        query.execute()

        then:
        def e = thrown IllegalStateException
        e.message == "Must specify component type and artifacts to query."
    }

    def "invalid component type #selectedComponentType and artifact type #selectedArtifactType is wrapped in UnresolvedComponentResult"() {
        withArtifactResolutionInteractions()

        given:
        def query = createArtifactResolutionQuery(givenComponentTypeRegistry)

        when:
        ModuleComponentIdentifier componentIdentifier = new DefaultModuleComponentIdentifier(DefaultModuleIdentifier.newId('mygroup', 'mymodule'), '1.0')
        ArtifactResolutionResult result = query
            .forComponents(componentIdentifier)
            .withArtifacts(selectedComponentType, selectedArtifactType)
            .execute()

        then:
        result
        result.components.size() == 1
        def componentResult = result.components.iterator().next()
        componentResult.id.displayName == componentIdentifier.displayName
        componentResult instanceof UnresolvedComponentResult
        UnresolvedComponentResult unresolvedComponentResult = (UnresolvedComponentResult) componentResult
        unresolvedComponentResult.failure instanceof IllegalArgumentException
        unresolvedComponentResult.failure.message == failureMessage

        where:
        givenComponentTypeRegistry | selectedComponentType | selectedArtifactType | failureMessage
        testComponentTypeRegistry  | UnknownComponent      | TestArtifact         | "Not a registered component type: ${UnknownComponent.name}."
        testComponentTypeRegistry  | TestComponent         | UnknownArtifact      | "Artifact type $UnknownArtifact.name is not registered for component type ${TestComponent.name}."
    }

    def "forModule is cumulative"() {
        withArtifactResolutionInteractions(2)

        given:
        def query = createArtifactResolutionQuery(testComponentTypeRegistry)

        when:
        def result = query
            .forModule("g1", "n1", "v1")
            .forModule("g2", "n2", "v2")
            .withArtifacts(TestComponent, TestArtifact)
            .execute()

        then:
        result.components*.id.displayName.containsAll(["g1:n1:v1", "g2:n2:v2"])
    }

    def "prepares bounded batches and verifies in input order regardless of retrieval completion order"() {
        given:
        withSuccessfulArtifactResolution()
        def ids = (1..18).collect { DefaultModuleComponentIdentifier.newId(DefaultModuleIdentifier.newId('test', "lib$it"), '1') }

        when:
        def result = createArtifactResolutionQuery(componentTypeRegistry).forComponents(ids).withArtifacts(TestComponent, TestArtifact).execute()

        then:
        batchSizes == [8, 8, 2]
        result.components*.id == ids
        result.resolvedComponents.size() == 18
        events == ids.collate(8).collectMany { batch ->
            batch.collectMany { ["metadata:${it.module}", 'map'] } +
                batch.reverse().collect { "retrieve:${it.module}" } +
                batch.collect { "verify:${it.module}" }
        }
    }

    def "preserves component failures and artifact failures without losing successful components"() {
        given:
        withSuccessfulArtifactResolution(true)
        def names = ['metadataFailure', 'setFailure', 'downloadFailure', 'verificationFailure', 'missing', 'success']
        def ids = names.collect { DefaultModuleComponentIdentifier.newId(DefaultModuleIdentifier.newId('test', it), '1') }

        when:
        def result = createArtifactResolutionQuery(componentTypeRegistry).forComponents(ids).withArtifacts(TestComponent, TestArtifact).execute()

        then:
        result.components*.id == ids
        result.components.findAll { it instanceof UnresolvedComponentResult }*.id == ids.take(2)
        result.resolvedComponents*.id == ids.drop(2)
        result.resolvedComponents.take(2).every { it.getArtifacts(TestArtifact).first() instanceof UnresolvedArtifactResult }
        result.resolvedComponents.find { it.id.module == 'missing' }.getArtifacts(TestArtifact).empty
        result.resolvedComponents.last().getArtifacts(TestArtifact).first().file.name == 'success.jar'
        events.findAll { it.startsWith('verify:') } == ['verify:verificationFailure', 'verify:success']
    }

    def "zero and one component queries and opt out do not schedule parallel work"() {
        given:
        withSuccessfulArtifactResolution()
        if (optOut) {
            System.setProperty(PARALLEL_QUERY_PROPERTY, 'false')
        }
        def ids = (0..<count).collect { DefaultModuleComponentIdentifier.newId(DefaultModuleIdentifier.newId('test', "lib$it"), '1') }

        when:
        def result = createArtifactResolutionQuery(componentTypeRegistry).forComponents(ids).withArtifacts(TestComponent, TestArtifact).execute()

        then:
        result.resolvedComponents*.id == ids
        batchSizes.empty
        !workerLeaseHeld

        where:
        count | optOut
        0     | false
        1     | false
        2     | true
    }

    private void withSuccessfulArtifactResolution(boolean failures = false) {
        externalResolverFactory.createResolvers(_, _, _, _, _, _, _) >> repositoryChain
        repositoryChain.artifactResolver >> artifactResolver
        repositoryChain.componentResolver >> componentMetaDataResolver
        componentTypeRegistry.getComponentRegistration(TestComponent) >> Stub(ComponentTypeRegistration) {
            getArtifactType(TestArtifact) >> {
                assert !retrieving
                events.add('map')
                ArtifactType.SOURCES
            }
        }
        componentMetaDataResolver.resolve(_, _, _) >> { ComponentIdentifier id, ComponentOverrideMetadata overrides, BuildableComponentResolveResult result ->
            assert !retrieving
            events.add("metadata:${id.module}".toString())
            if (failures && id.module == 'metadataFailure') {
                throw new IllegalStateException('metadata failed')
            }
            def metadata = Stub(ComponentArtifactResolveMetadata) { getId() >> id }
            def artifactState = Stub(ComponentArtifactResolveState) { getArtifactMetadata() >> metadata }
            result.resolved(Stub(ComponentGraphResolveState) {
                prepareForArtifactResolution() >> {
                    assert !retrieving
                    artifactState
                }
            }, Stub(ComponentGraphSpecificResolveState))
        }
        artifactResolver.resolveArtifactsWithType(_, ArtifactType.SOURCES, _) >> { ComponentArtifactResolveMetadata component, ArtifactType type, BuildableArtifactSetResolveResult result ->
            events.add("retrieve:${component.id.module}".toString())
            if (failures && component.id.module == 'setFailure') {
                throw new IllegalStateException('artifact set failed')
            }
            def id = Stub(ComponentArtifactIdentifier) { getComponentIdentifier() >> component.id }
            result.resolved(failures && component.id.module == 'missing' ? [] : [Stub(ComponentArtifactMetadata) { getId() >> id }])
        }
        artifactResolver.resolveArtifact(_, _, _) >> { ComponentArtifactResolveMetadata component, ComponentArtifactMetadata artifact, BuildableArtifactResolveResult result ->
            if (failures && component.id.module == 'downloadFailure') {
                result.notFound(artifact.id)
            } else {
                result.resolved(Stub(ResolvableArtifact) { getFile() >> new File("${component.id.module}.jar") })
            }
        }
        externalResolverFactory.verifiedArtifact(_) >> { DefaultResolvedArtifactResult artifact ->
            assert !retrieving
            events.add("verify:${artifact.id.componentIdentifier.module}".toString())
            if (failures && artifact.id.componentIdentifier.module == 'verificationFailure') {
                throw new IllegalStateException('verification failed')
            }
            artifact
        }
    }

    private def withArtifactResolutionInteractions(int numberOfComponentsToResolve = 1) {
        1 * externalResolverFactory.createResolvers(_, _, _, _, _, _, _) >> repositoryChain
        1 * repositoryChain.artifactResolver >> artifactResolver
        1 * repositoryChain.componentResolver >> componentMetaDataResolver
        numberOfComponentsToResolve * componentMetaDataResolver.resolve(_, _, _) >> { ComponentIdentifier componentId, ComponentOverrideMetadata requestMetaData, BuildableComponentResolveResult resolveResult ->
            resolveResult.resolved(
                Stub(ComponentGraphResolveState),
                Stub(ComponentGraphSpecificResolveState)
            )
        }
    }

    private DefaultArtifactResolutionQuery createArtifactResolutionQuery(ComponentTypeRegistry componentTypeRegistry) {
        new DefaultArtifactResolutionQuery(resolutionStrategyFactory, { [] }, externalResolverFactory, componentMetadataRulesSupplier, componentMetadataHandler, componentTypeRegistry, buildOperationExecutor, workerLeaseService)
    }

    private ComponentTypeRegistry createTestComponentTypeRegistry() {
        return Stub(ComponentTypeRegistry) {
            getComponentRegistration(_) >> { Class componentType ->
                if (componentType == TestComponent) {
                    return createStubComponentRegistration()
                } else {
                    throw new IllegalArgumentException(String.format("Not a registered component type: %s.", componentType.getName()));
                }
            }
        }
    }

    private ComponentTypeRegistration createStubComponentRegistration() {
        return Stub(ComponentTypeRegistration) {
            getArtifactType(_) >> { Class artifactType ->
                throw new IllegalArgumentException(String.format("Artifact type %s is not registered for component type %s.", artifactType.getName(), TestComponent.getName()));
            }
        }
    }

    private static class TestComponent implements Component {
    }

    private static interface TestArtifact extends Artifact {
    }

    private static class UnknownComponent implements Component {
    }

    private static interface UnknownArtifact extends Artifact {
    }

}
