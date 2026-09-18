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
package org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.builder

import com.google.common.collect.ImmutableList
import org.gradle.api.artifacts.component.ProjectComponentSelector
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.AttributeCompatibilityRule
import org.gradle.api.attributes.CompatibilityCheckDetails
import org.gradle.api.internal.artifacts.DefaultModuleIdentifier
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.DefaultVersionComparator
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.DefaultVersionSelectorScheme
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionParser
import org.gradle.api.internal.attributes.ImmutableAttributes
import org.gradle.api.internal.attributes.immutable.ImmutableAttributesSchema
import org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier
import org.gradle.internal.component.external.model.DefaultModuleComponentSelector
import org.gradle.internal.component.external.model.ExternalModuleComponentGraphResolveMetadata
import org.gradle.internal.component.external.model.ExternalModuleComponentGraphResolveState
import org.gradle.internal.component.model.ComponentGraphSpecificResolveState
import org.gradle.internal.component.model.DefaultComponentOverrideMetadata
import org.gradle.internal.component.model.DependencyMetadata
import org.gradle.internal.component.model.GraphSelectionCandidates
import org.gradle.internal.component.model.IvyArtifactName
import org.gradle.internal.component.model.VariantGraphResolveState
import org.gradle.internal.operations.BuildOperationContext
import org.gradle.internal.operations.BuildOperationQueue
import org.gradle.internal.operations.RunnableBuildOperation
import org.gradle.internal.resolve.resolver.ComponentMetaDataResolver
import org.gradle.internal.resolve.result.BuildableComponentResolveResult
import org.gradle.internal.resolve.result.DefaultBuildableComponentResolveResult
import org.gradle.util.AttributeTestUtil
import spock.lang.Specification

class OptimisticMetadataResolverTest extends Specification {
    def delegate = Mock(ComponentMetaDataResolver)
    def scheme = new DefaultVersionSelectorScheme(new DefaultVersionComparator(), new VersionParser())
    def operations = new ArrayDeque<RunnableBuildOperation>()
    def queue = Stub(BuildOperationQueue) {
        addUnconstrained(_) >> { RunnableBuildOperation operation -> operations.add(operation) }
    }
    def resolver = newResolver()
    def context = Stub(BuildOperationContext)

    def setup() {
        resolver.start(queue)
    }

    def "looks ahead into metadata before any component is demanded and deduplicates cycles"() {
        resolver = newResolver(3, 1, 1024)
        resolver.start(queue)
        def a = dependency("a")
        def b = dependency("b")
        def aState = state([b])
        def bState = state([a])

        when:
        resolver.prefetch(a)
        resolver.prefetch(a)
        operations.remove().run(context)

        then:
        1 * delegate.resolve(id("a"), DefaultComponentOverrideMetadata.EMPTY, _) >> { identifier, overrides, result ->
            result.resolved(aState, ComponentGraphSpecificResolveState.EMPTY_STATE)
        }
        operations.size() == 1

        when:
        operations.remove().run(context)

        then:
        1 * delegate.resolve(id("b"), DefaultComponentOverrideMetadata.EMPTY, _) >> { identifier, overrides, result ->
            result.resolved(bState, ComponentGraphSpecificResolveState.EMPTY_STATE)
        }
        operations.empty
        0 * delegate._
    }

    def "limits recursive depth"() {
        resolver = newResolver(1, 32, 1024)
        resolver.start(queue)
        def metadata = state([dependency("child")])

        when:
        resolver.prefetch(dependency("parent"))
        operations.remove().run(context)

        then:
        1 * delegate.resolve(id("parent"), _, _) >> { identifier, overrides, result ->
            result.resolved(metadata, ComponentGraphSpecificResolveState.EMPTY_STATE)
        }
        operations.empty
    }

    def "does not expand non-transitive speculative dependencies"() {
        def metadata = state([dependency("child")])

        when:
        resolver.prefetch(dependency("parent", "1", false))
        operations.remove().run(context)

        then:
        1 * delegate.resolve(id("parent"), _, _) >> { identifier, overrides, result ->
            result.resolved(metadata, ComponentGraphSpecificResolveState.EMPTY_STATE)
        }
        operations.empty
    }

    def "refills outstanding work from a bounded backlog without rediscovering candidates"() {
        resolver = newResolver(2, 1, 2)
        resolver.start(queue)

        when:
        resolver.prefetch(dependency("a"))
        resolver.prefetch(dependency("b"))
        resolver.prefetch(dependency("b"))
        resolver.prefetch(dependency("c"))

        then:
        operations.size() == 1

        when:
        operations.remove().run(context)

        then:
        1 * delegate.resolve(id("a"), _, _) >> { identifier, overrides, result -> result.notFound(identifier) }
        operations.size() == 1

        when:
        operations.remove().run(context)
        resolver.prefetch(dependency("c"))

        then:
        1 * delegate.resolve(id("b"), _, _) >> { identifier, overrides, result -> result.notFound(identifier) }
        operations.empty
    }

    def "keeps all speculative children when the outstanding window is full"() {
        resolver = newResolver(2, 1, 1024)
        resolver.start(queue)
        def metadata = state([dependency("a"), dependency("b"), dependency("c")])

        when:
        resolver.prefetch(dependency("parent"))
        operations.remove().run(context)

        then:
        1 * delegate.resolve(id("parent"), _, _) >> { identifier, overrides, result ->
            result.resolved(metadata, ComponentGraphSpecificResolveState.EMPTY_STATE)
        }
        operations.size() == 1

        when:
        operations.remove().run(context)

        then:
        1 * delegate.resolve(id("a"), _, _) >> { identifier, overrides, result -> result.notFound(identifier) }
        operations.size() == 1

        when:
        operations.remove().run(context)

        then:
        1 * delegate.resolve(id("b"), _, _) >> { identifier, overrides, result -> result.notFound(identifier) }
        operations.size() == 1

        when:
        operations.remove().run(context)

        then:
        1 * delegate.resolve(id("c"), _, _) >> { identifier, overrides, result -> result.notFound(identifier) }
        operations.empty
    }

    def "reuses cheap metadata estimates without submitting speculative tasks but expands on demand"() {
        def metadata = state([dependency("child")])

        when:
        resolver.prefetch(dependency("parent"))
        resolver.prefetch(dependency("parent"))

        then:
        1 * delegate.isFetchingMetadataCheap(id("parent")) >> true
        resolver.isFetchingMetadataCheap(id("parent"))
        resolver.isFetchingMetadataCheap(id("parent"))
        0 * delegate.resolve(_, _, _)
        operations.empty

        when:
        resolver.resolve(id("parent"), DefaultComponentOverrideMetadata.EMPTY, new DefaultBuildableComponentResolveResult())

        then:
        1 * delegate.resolve(id("parent"), _, _) >> { identifier, overrides, result ->
            result.resolved(metadata, ComponentGraphSpecificResolveState.EMPTY_STATE)
        }
        operations.size() == 1
    }

    def "does not retain expensive metadata estimates after the cache is warmed"() {
        when:
        resolver.prefetch(dependency("a"))

        then:
        1 * delegate.isFetchingMetadataCheap(id("a")) >> false
        operations.size() == 1

        when:
        def cheap = resolver.isFetchingMetadataCheap(id("a"))

        then:
        1 * delegate.isFetchingMetadataCheap(id("a")) >> true
        cheap
    }

    def "skips queued and backlogged candidates taken over by demand resolution"() {
        resolver = newResolver(2, 1, 1024)
        resolver.start(queue)
        resolver.prefetch(dependency("a"))
        resolver.prefetch(dependency("b"))
        resolver.prefetch(dependency("c"))

        when:
        resolver.resolve(id("a"), DefaultComponentOverrideMetadata.EMPTY, new DefaultBuildableComponentResolveResult())
        resolver.resolve(id("b"), DefaultComponentOverrideMetadata.EMPTY, new DefaultBuildableComponentResolveResult())
        operations.remove().run(context)

        then:
        1 * delegate.resolve(id("a"), _, _) >> { identifier, overrides, result -> result.notFound(identifier) }
        1 * delegate.resolve(id("b"), _, _) >> { identifier, overrides, result -> result.notFound(identifier) }
        operations.size() == 1

        when:
        operations.remove().run(context)
        resolver.prefetch(dependency("b"))

        then:
        1 * delegate.resolve(id("c"), _, _) >> { identifier, overrides, result -> result.notFound(identifier) }
        operations.empty
    }

    def "does not prefetch metadata already demanded before it was discovered"() {
        when:
        resolver.resolve(id("a"), DefaultComponentOverrideMetadata.EMPTY, new DefaultBuildableComponentResolveResult())
        resolver.prefetch(dependency("a"))

        then:
        1 * delegate.resolve(id("a"), _, _) >> { identifier, overrides, result -> result.notFound(identifier) }
        operations.empty
    }

    def "does not speculate on dynamic or empty versions #version"() {
        when:
        resolver.prefetch(dependency("a", version))

        then:
        operations.empty
        0 * delegate._

        where:
        version << ["", "1.+", "latest.release", "[1,2)"]
    }

    def "normalizes exact version ranges"() {
        when:
        resolver.prefetch(dependency("a", "[1]"))
        operations.remove().run(context)

        then:
        1 * delegate.resolve(id("a"), _, _) >> { identifier, overrides, result -> result.notFound(identifier) }
    }

    def "skips constraints changing modules project selectors and explicit artifacts"() {
        when:
        resolver.prefetch(Stub(DependencyMetadata) { isConstraint() >> true })
        resolver.prefetch(Stub(DependencyMetadata) { isChanging() >> true })
        resolver.prefetch(Stub(DependencyMetadata) {
            getArtifacts() >> ImmutableList.of()
            getSelector() >> Stub(ProjectComponentSelector)
        })
        resolver.prefetch(Stub(DependencyMetadata) { getArtifacts() >> ImmutableList.of(Stub(IvyArtifactName)) })

        then:
        operations.empty
        0 * delegate._
    }

    def "ignores speculative exceptions but preserves demanded failures and overrides"() {
        def result = new DefaultBuildableComponentResolveResult()
        def overrides = DefaultComponentOverrideMetadata.EMPTY.withChanging()

        when:
        resolver.prefetch(dependency("a"))
        operations.remove().run(context)

        then:
        1 * delegate.resolve(id("a"), _, _) >> { throw new IllegalStateException("speculative failure") }
        noExceptionThrown()

        when:
        resolver.resolve(id("a"), overrides, result)

        then:
        1 * delegate.resolve(id("a"), overrides, result) >> { identifier, override, target -> target.notFound(identifier) }
        result.failure != null
    }

    def "stopping skips queued work and prevents new submissions"() {
        resolver = newResolver(2, 1, 1024)
        resolver.start(queue)

        when:
        resolver.prefetch(dependency("a"))
        resolver.prefetch(dependency("b"))
        resolver.stop()
        operations.remove().run(context)
        resolver.prefetch(dependency("c"))

        then:
        operations.empty
        1 * delegate.isFetchingMetadataCheap(id("a")) >> false
        1 * delegate.isFetchingMetadataCheap(id("b")) >> false
        0 * delegate._
    }

    def "refills the backlog after a speculative exception"() {
        resolver = newResolver(2, 1, 1024)
        resolver.start(queue)
        resolver.prefetch(dependency("a"))
        resolver.prefetch(dependency("b"))

        when:
        operations.remove().run(context)

        then:
        1 * delegate.resolve(id("a"), _, _) >> { throw new IllegalStateException("speculative failure") }
        operations.size() == 1

        when:
        operations.remove().run(context)

        then:
        1 * delegate.resolve(id("b"), _, _) >> { identifier, overrides, result -> result.notFound(identifier) }
        operations.empty
    }

    def "ignores speculative cost estimation failures without affecting normal resolution"() {
        when:
        resolver.prefetch(dependency("a"))

        then:
        1 * delegate.isFetchingMetadataCheap(id("a")) >> { throw new IllegalStateException("cost unavailable") }
        operations.empty

        when:
        def result = new DefaultBuildableComponentResolveResult()
        resolver.resolve(id("a"), DefaultComponentOverrideMetadata.EMPTY, result)

        then:
        1 * delegate.resolve(id("a"), _, _) >> { identifier, overrides, target -> target.notFound(identifier) }
        result.failure != null
    }

    def "stopping during a request prevents recursive submission"() {
        def metadata = state([dependency("child")])

        when:
        resolver.prefetch(dependency("parent"))
        operations.remove().run(context)

        then:
        1 * delegate.resolve(id("parent"), _, _) >> { identifier, overrides, result ->
            resolver.stop()
            result.resolved(metadata, ComponentGraphSpecificResolveState.EMPTY_STATE)
        }
        operations.empty
    }

    def "an invalid unselected variant does not fail demand resolution"() {
        def metadata = Stub(ExternalModuleComponentGraphResolveState) {
            getCandidatesForGraphVariantSelection() >> { throw new IllegalStateException("unused variant") }
        }
        def result = new DefaultBuildableComponentResolveResult()

        when:
        resolver.resolve(id("parent"), DefaultComponentOverrideMetadata.EMPTY, result)

        then:
        1 * delegate.resolve(id("parent"), _, _) >> { identifier, overrides, target ->
            target.resolved(metadata, ComponentGraphSpecificResolveState.EMPTY_STATE)
        }
        result.state == metadata
        operations.empty
    }

    def "demanded metadata seeds lookahead for every compatible variant without disambiguation"() {
        def result = new DefaultBuildableComponentResolveResult()
        def variants = [
            variant([dependency("a")], AttributeTestUtil.attributes(platform: 'jvm')),
            variant([dependency("b"), dependency("a")])
        ]
        def metadata = variantState(variants)

        when:
        resolver.resolve(id("parent"), DefaultComponentOverrideMetadata.EMPTY, result)

        then:
        1 * delegate.resolve(id("parent"), _, _ as BuildableComponentResolveResult) >> { identifier, overrides, target ->
            target.resolved(metadata, ComponentGraphSpecificResolveState.EMPTY_STATE)
        }
        operations.size() == 2
    }

    def "filters #candidateAttributes for #consumerAttributes on demanded=#demanded metadata"() {
        def consumer = AttributeTestUtil.attributes(consumerAttributes)
        resolver = newResolver(2, 32, 1024, consumer)
        resolver.start(queue)
        def child = variant([dependency("child")], AttributeTestUtil.attributes(candidateAttributes))
        def metadata = variantState([child])
        def result = new DefaultBuildableComponentResolveResult()

        when:
        if (demanded) {
            resolver.resolve(id("parent"), DefaultComponentOverrideMetadata.EMPTY, result)
        } else {
            resolver.prefetch(dependency("parent"))
            operations.remove().run(context)
        }

        then:
        1 * delegate.resolve(id("parent"), _, _) >> { identifier, overrides, target ->
            target.resolved(metadata, ComponentGraphSpecificResolveState.EMPTY_STATE)
        }
        operations.size() == expected
        !demanded || result.state == metadata

        when:
        while (!operations.empty) {
            operations.remove().run(context)
        }

        then:
        expected * delegate.resolve(id("child"), _, _) >> { identifier, overrides, target -> target.notFound(identifier) }
        0 * delegate.resolve(_, _, _)

        where:
        demanded | consumerAttributes | candidateAttributes     | expected
        true     | [platform: 'jvm']  | [platform: 'jvm']       | 1
        true     | [platform: 'jvm']  | [platform: 'native']    | 0
        true     | [platform: 'jvm']  | [:]                    | 1
        true     | [platform: 'jvm']  | [usage: 'java-runtime'] | 1
        true     | [:]               | [platform: 'native']    | 1
        false    | [platform: 'jvm']  | [platform: 'jvm']       | 1
        false    | [platform: 'jvm']  | [platform: 'native']    | 0
        false    | [platform: 'jvm']  | [:]                    | 1
        false    | [platform: 'jvm']  | [usage: 'java-runtime'] | 1
        false    | [:]               | [platform: 'native']    | 1
    }

    def "honors compatibility rules from the #ruleSource schema"() {
        def schema = AttributeTestUtil.immutableSchema {
            attribute(Attribute.of('platform', String)).compatibilityRules.add(JvmCompatibilityRule)
        }
        resolver = newResolver(2, 32, 1024, AttributeTestUtil.attributes(platform: 'jvm'),
            ruleSource == 'consumer' ? schema : ImmutableAttributesSchema.EMPTY)
        resolver.start(queue)
        def metadata = variantState(
            [variant([dependency("child")], AttributeTestUtil.attributes(platform: 'common'))],
            ruleSource == 'producer' ? schema : ImmutableAttributesSchema.EMPTY
        )

        when:
        resolver.resolve(id("parent"), DefaultComponentOverrideMetadata.EMPTY, new DefaultBuildableComponentResolveResult())

        then:
        1 * delegate.resolve(id("parent"), _, _) >> { identifier, overrides, target ->
            target.resolved(metadata, ComponentGraphSpecificResolveState.EMPTY_STATE)
        }
        operations.size() == 1

        where:
        ruleSource << ['consumer', 'producer']
    }

    def "does not fall back to legacy metadata when all attribute matching variants are incompatible"() {
        def incompatible = Mock(VariantGraphResolveState) {
            getAttributes() >> AttributeTestUtil.attributes(platform: 'native')
        }
        def selectionCandidates = Mock(GraphSelectionCandidates) {
            getVariantsForAttributeMatching() >> [incompatible]
        }
        def metadata = Stub(ExternalModuleComponentGraphResolveState) {
            getMetadata() >> Stub(ExternalModuleComponentGraphResolveMetadata) {
                getAttributesSchema() >> ImmutableAttributesSchema.EMPTY
            }
            getCandidatesForGraphVariantSelection() >> selectionCandidates
        }

        when:
        resolver.resolve(id("parent"), DefaultComponentOverrideMetadata.EMPTY, new DefaultBuildableComponentResolveResult())

        then:
        1 * delegate.resolve(id("parent"), _, _) >> { identifier, overrides, target ->
            target.resolved(metadata, ComponentGraphSpecificResolveState.EMPTY_STATE)
        }
        0 * incompatible.getDependencies()
        0 * selectionCandidates.getLegacyVariant()
        operations.empty
    }

    static class JvmCompatibilityRule implements AttributeCompatibilityRule<String> {
        @Override
        void execute(CompatibilityCheckDetails<String> details) {
            if (details.consumerValue == 'jvm' && details.producerValue == 'common') {
                details.compatible()
            }
        }
    }

    private OptimisticMetadataResolver newResolver(
        int depth = 2,
        int maxPending = 32,
        int maxCandidates = 1024,
        ImmutableAttributes consumerAttributes = AttributeTestUtil.attributes(platform: 'jvm'),
        ImmutableAttributesSchema consumerSchema = ImmutableAttributesSchema.EMPTY
    ) {
        new OptimisticMetadataResolver(delegate, scheme, consumerAttributes, consumerSchema, AttributeTestUtil.services(), depth, maxPending, maxCandidates)
    }

    private ExternalModuleComponentGraphResolveState variantState(List<VariantGraphResolveState> variants, ImmutableAttributesSchema producerSchema = ImmutableAttributesSchema.EMPTY) {
        Stub(ExternalModuleComponentGraphResolveState) {
            getMetadata() >> Stub(ExternalModuleComponentGraphResolveMetadata) {
                getAttributesSchema() >> producerSchema
            }
            getCandidatesForGraphVariantSelection() >> Stub(GraphSelectionCandidates) {
                getVariantsForAttributeMatching() >> variants
            }
        }
    }

    private DependencyMetadata dependency(String name, String version = "1", boolean transitive = true) {
        Stub(DependencyMetadata) {
            getSelector() >> DefaultModuleComponentSelector.newSelector(DefaultModuleIdentifier.newId("test", name), version)
            getArtifacts() >> ImmutableList.of()
            isTransitive() >> transitive
        }
    }

    private static id(String name) {
        DefaultModuleComponentIdentifier.newId(DefaultModuleIdentifier.newId("test", name), "1")
    }

    private VariantGraphResolveState variant(List<DependencyMetadata> dependencies, ImmutableAttributes attributes = ImmutableAttributes.EMPTY) {
        Stub(VariantGraphResolveState) {
            getAttributes() >> attributes
            getDependencies() >> dependencies
        }
    }

    private ExternalModuleComponentGraphResolveState state(List<DependencyMetadata> dependencies) {
        def legacy = variant(dependencies)
        Stub(ExternalModuleComponentGraphResolveState) {
            getCandidatesForGraphVariantSelection() >> Stub(GraphSelectionCandidates) {
                getVariantsForAttributeMatching() >> []
                getLegacyVariant() >> legacy
            }
        }
    }
}