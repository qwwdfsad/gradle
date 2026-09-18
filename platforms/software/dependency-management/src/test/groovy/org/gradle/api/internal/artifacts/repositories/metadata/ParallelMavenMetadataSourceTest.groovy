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
package org.gradle.api.internal.artifacts.repositories.metadata

import org.gradle.api.Action
import org.gradle.api.internal.artifacts.DefaultModuleIdentifier
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ComponentResolvers
import org.gradle.api.internal.artifacts.repositories.resolver.ExternalResourceArtifactResolver
import org.gradle.internal.component.external.model.DefaultModuleComponentArtifactMetadata
import org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier
import org.gradle.internal.component.external.model.MutableModuleComponentResolveMetadata
import org.gradle.internal.component.model.DefaultComponentOverrideMetadata
import org.gradle.internal.component.model.DefaultIvyArtifactName
import org.gradle.internal.component.model.DefaultModuleDescriptorArtifactMetadata
import org.gradle.internal.operations.BuildOperationContext
import org.gradle.internal.operations.BuildOperationExecutor
import org.gradle.internal.operations.BuildOperationQueue
import org.gradle.internal.operations.RunnableBuildOperation
import org.gradle.internal.resolve.result.DefaultBuildableModuleComponentMetaDataResolveResult
import org.gradle.internal.resource.local.LocallyAvailableExternalResource
import org.gradle.internal.work.WorkerLeaseService
import spock.lang.Specification

class ParallelMavenMetadataSourceTest extends Specification {
    def pomSource = Mock(MetadataSource)
    def moduleSource = Mock(MetadataSource)
    def executor = Mock(BuildOperationExecutor)
    def workerLeaseService = Mock(WorkerLeaseService)
    def queue = Mock(BuildOperationQueue)
    def context = Stub(BuildOperationContext)
    def resolver = Mock(ExternalResourceArtifactResolver)
    def componentResolvers = Stub(ComponentResolvers)
    def id = DefaultModuleComponentIdentifier.newId(DefaultModuleIdentifier.newId('test', 'lib'), '1')
    def pom = new DefaultModuleDescriptorArtifactMetadata(id, new DefaultIvyArtifactName('lib', 'pom', 'pom'))
    def module = new DefaultModuleComponentArtifactMetadata(id, new DefaultIvyArtifactName('lib', 'module', 'module'))
    def pomResource = Stub(LocallyAvailableExternalResource)
    def moduleResource = Stub(LocallyAvailableExternalResource)
    def pomMetadata = Stub(MutableModuleComponentResolveMetadata)
    def moduleMetadata = Stub(MutableModuleComponentResolveMetadata)
    def result = new DefaultBuildableModuleComponentMetaDataResolveResult()
    def source = new ParallelMavenMetadataSource(pomSource, moduleSource, executor, workerLeaseService)

    def setup() {
        workerLeaseService.runAsWorkerThread(_ as Runnable) >> { Runnable action -> action.run() }
        executor.runAll(_) >> { Action action -> action.execute(queue) }
        queue.addUnconstrained(_) >> { RunnableBuildOperation operation -> operation.run(context) }
    }

    def "downloads once and only parses module metadata when the POM redirects"() {
        given:
        learnRedirectingGroup()

        when:
        def metadata = source.create('repo', componentResolvers, id, DefaultComponentOverrideMetadata.EMPTY, resolver, result)

        then:
        1 * resolver.resolveArtifact({ it.id == pom.id }, _) >> { artifact, target ->
            target.attempted('pom-url')
            pomResource
        }
        1 * resolver.resolveArtifact({ it.id == module.id }, _) >> { artifact, target ->
            target.attempted('module-url')
            moduleResource
        }
        1 * pomSource.create(_, _, _, _, _, _) >> { repo, components, identifier, overrides, downloaded, target ->
            assert downloaded.resolveArtifact(pom, target) == pomResource
            if (redirect) {
                target.redirectToGradleMetadata()
            }
            pomMetadata
        }
        (redirect ? 1 : 0) * moduleSource.create(_, _, _, _, _, _) >> { repo, components, identifier, overrides, downloaded, target ->
            assert downloaded.resolveArtifact(module, target) == moduleResource
            moduleMetadata
        }
        metadata == (redirect ? moduleMetadata : pomMetadata)
        result.attempted == (redirect ? ['pom-url', 'module-url'] : ['pom-url'])

        where:
        redirect << [true, false]
    }

    def "unused module download failure does not fail POM resolution"() {
        given:
        learnRedirectingGroup()

        when:
        def metadata = source.create('repo', componentResolvers, id, DefaultComponentOverrideMetadata.EMPTY, resolver, result)

        then:
        1 * resolver.resolveArtifact({ it.id == pom.id }, _) >> pomResource
        1 * resolver.resolveArtifact({ it.id == module.id }, _) >> { throw new IllegalStateException('unused') }
        1 * pomSource.create(_, _, _, _, _, _) >> { repo, components, identifier, overrides, downloaded, target ->
            assert downloaded.resolveArtifact(pom, target) == pomResource
            pomMetadata
        }
        0 * moduleSource._
        metadata == pomMetadata
    }

    def "redirected module download failure is authoritative"() {
        given:
        learnRedirectingGroup()
        def failure = new IllegalStateException('required module')

        when:
        source.create('repo', componentResolvers, id, DefaultComponentOverrideMetadata.EMPTY, resolver, result)

        then:
        1 * resolver.resolveArtifact({ it.id == pom.id }, _) >> pomResource
        1 * resolver.resolveArtifact({ it.id == module.id }, _) >> { throw failure }
        1 * pomSource.create(_, _, _, _, _, _) >> { repo, components, identifier, overrides, downloaded, target ->
            downloaded.resolveArtifact(pom, target)
            target.redirectToGradleMetadata()
            pomMetadata
        }
        1 * moduleSource.create(_, _, _, _, _, _) >> { repo, components, identifier, overrides, downloaded, target ->
            downloaded.resolveArtifact(module, target)
        }
        def actual = thrown(IllegalStateException)
        actual.is(failure)
    }

    def "missing redirected module still falls back to the POM"() {
        given:
        learnRedirectingGroup()

        when:
        def metadata = source.create('repo', componentResolvers, id, DefaultComponentOverrideMetadata.EMPTY, resolver, result)

        then:
        1 * resolver.resolveArtifact({ it.id == pom.id }, _) >> pomResource
        1 * resolver.resolveArtifact({ it.id == module.id }, _) >> null
        1 * pomSource.create(_, _, _, _, _, _) >> { repo, components, identifier, overrides, downloaded, target ->
            downloaded.resolveArtifact(pom, target)
            target.redirectToGradleMetadata()
            pomMetadata
        }
        1 * moduleSource.create(_, _, _, _, _, _) >> { repo, components, identifier, overrides, downloaded, target ->
            assert downloaded.resolveArtifact(module, target) == null
            null
        }
        metadata == pomMetadata
    }

    def "POM download failure remains authoritative and releases admission"() {
        given:
        learnRedirectingGroup()
        def failure = new IllegalStateException('required POM')

        when:
        9.times {
            try {
                def identifier = DefaultModuleComponentIdentifier.newId(DefaultModuleIdentifier.newId('test', 'lib'), "$it")
                source.create('repo', componentResolvers, identifier, DefaultComponentOverrideMetadata.EMPTY, resolver, new DefaultBuildableModuleComponentMetaDataResolveResult())
                throw new AssertionError('POM failure was ignored')
            } catch (IllegalStateException actual) {
                assert actual.is(failure)
            }
        }

        then:
        9 * resolver.resolveArtifact({ it.name.extension == 'pom' }, _) >> { throw failure }
        9 * resolver.resolveArtifact({ it.name.extension == 'module' }, _) >> moduleResource
        9 * pomSource.create(_, _, _, _, _, _) >> { repo, components, identifier, overrides, downloaded, target ->
            downloaded.resolveArtifact(new DefaultModuleDescriptorArtifactMetadata(identifier, new DefaultIvyArtifactName('lib', 'pom', 'pom')), target)
        }
        0 * moduleSource._
    }

    def "unknown POM only groups never probe module metadata or use a worker"() {
        given:
        def sibling = DefaultModuleComponentIdentifier.newId(DefaultModuleIdentifier.newId('test', 'sibling'), '1')
        def other = DefaultModuleComponentIdentifier.newId(DefaultModuleIdentifier.newId('other', 'lib'), '1')

        when:
        [id, sibling, other].each { identifier ->
            source.create('repo', componentResolvers, identifier, DefaultComponentOverrideMetadata.EMPTY, resolver, new DefaultBuildableModuleComponentMetaDataResolveResult())
        }

        then:
        3 * pomSource.create('repo', componentResolvers, _, DefaultComponentOverrideMetadata.EMPTY, resolver, _) >> { repo, components, identifier, overrides, downloaded, target ->
            downloaded.resolveArtifact(new DefaultModuleDescriptorArtifactMetadata(identifier, new DefaultIvyArtifactName(identifier.module, 'pom', 'pom')), target)
            pomMetadata
        }
        3 * resolver.resolveArtifact({ it.name.extension == 'pom' }, _) >> pomResource
        0 * resolver._
        0 * moduleSource._
        0 * workerLeaseService._
        0 * executor._
    }

    def "learned groups are isolated by source and publisher group"() {
        given:
        learnRedirectingGroup()
        def otherSource = new ParallelMavenMetadataSource(pomSource, moduleSource, executor, workerLeaseService)
        def otherGroup = DefaultModuleComponentIdentifier.newId(DefaultModuleIdentifier.newId('other', 'lib'), '1')

        when:
        def first = otherSource.create('repo', componentResolvers, id, DefaultComponentOverrideMetadata.EMPTY, resolver, result)
        def second = source.create('repo', componentResolvers, otherGroup, DefaultComponentOverrideMetadata.EMPTY, resolver, new DefaultBuildableModuleComponentMetaDataResolveResult())

        then:
        1 * pomSource.create('repo', componentResolvers, id, DefaultComponentOverrideMetadata.EMPTY, resolver, result) >> pomMetadata
        1 * pomSource.create('repo', componentResolvers, otherGroup, DefaultComponentOverrideMetadata.EMPTY, resolver, _) >> pomMetadata
        0 * moduleSource._
        0 * resolver._
        0 * workerLeaseService._
        0 * executor._
        first == pomMetadata
        second == pomMetadata
    }

    def "an authoritative redirect learns a group even when its module metadata is missing #missing"() {
        given:
        def bootstrap = DefaultModuleComponentIdentifier.newId(DefaultModuleIdentifier.newId('test', 'bootstrap'), '1')

        when:
        def initial = source.create('repo', componentResolvers, bootstrap, DefaultComponentOverrideMetadata.EMPTY, resolver, result)

        then:
        1 * pomSource.create('repo', componentResolvers, bootstrap, DefaultComponentOverrideMetadata.EMPTY, resolver, result) >> { repo, components, identifier, overrides, downloaded, target ->
            target.redirectToGradleMetadata()
            pomMetadata
        }
        1 * moduleSource.create('repo', componentResolvers, bootstrap, DefaultComponentOverrideMetadata.EMPTY, resolver, result) >> (missing ? null : moduleMetadata)
        0 * resolver._
        0 * workerLeaseService._
        0 * executor._
        initial == (missing ? pomMetadata : moduleMetadata)

        when:
        def metadata = source.create('repo', componentResolvers, id, DefaultComponentOverrideMetadata.EMPTY, resolver, new DefaultBuildableModuleComponentMetaDataResolveResult())

        then:
        1 * workerLeaseService.runAsWorkerThread(_ as Runnable) >> { Runnable action -> action.run() }
        1 * executor.runAll(_) >> { Action action -> action.execute(queue) }
        2 * queue.addUnconstrained(_) >> { RunnableBuildOperation operation -> operation.run(context) }
        1 * resolver.resolveArtifact({ it.id == pom.id }, _) >> pomResource
        1 * resolver.resolveArtifact({ it.id == module.id }, _) >> moduleResource
        1 * pomSource.create(_, _, id, _, _, _) >> { repo, components, identifier, overrides, downloaded, target ->
            assert downloaded.resolveArtifact(pom, target) == pomResource
            target.redirectToGradleMetadata()
            pomMetadata
        }
        1 * moduleSource.create(_, _, id, _, _, _) >> { repo, components, identifier, overrides, downloaded, target ->
            assert downloaded.resolveArtifact(module, target) == moduleResource
            moduleMetadata
        }
        0 * resolver._
        metadata == moduleMetadata

        where:
        missing << [false, true]
    }

    def "overlap revokes the hint when redirect is #redirect and module is missing #missing but a later redirect relearns it"() {
        given:
        learnRedirectingGroup()

        when:
        def metadata = source.create('repo', componentResolvers, id, DefaultComponentOverrideMetadata.EMPTY, resolver, result)

        then:
        1 * resolver.resolveArtifact({ it.id == pom.id }, _) >> pomResource
        1 * resolver.resolveArtifact({ it.id == module.id }, _) >> (missing ? null : moduleResource)
        1 * pomSource.create(_, _, id, _, _, _) >> { repo, components, identifier, overrides, downloaded, target ->
            assert downloaded.resolveArtifact(pom, target) == pomResource
            if (redirect) {
                target.redirectToGradleMetadata()
            }
            pomMetadata
        }
        (redirect ? 1 : 0) * moduleSource.create(_, _, id, _, _, _) >> { repo, components, identifier, overrides, downloaded, target ->
            assert downloaded.resolveArtifact(module, target) == null
            null
        }
        0 * resolver._
        metadata == pomMetadata

        when:
        def relearned = source.create('repo', componentResolvers, id, DefaultComponentOverrideMetadata.EMPTY, resolver, new DefaultBuildableModuleComponentMetaDataResolveResult())

        then:
        1 * pomSource.create('repo', componentResolvers, id, DefaultComponentOverrideMetadata.EMPTY, resolver, _) >> { repo, components, identifier, overrides, downloaded, target ->
            target.redirectToGradleMetadata()
            pomMetadata
        }
        1 * moduleSource.create('repo', componentResolvers, id, DefaultComponentOverrideMetadata.EMPTY, resolver, _) >> moduleMetadata
        0 * resolver._
        0 * workerLeaseService._
        0 * executor._
        relearned == moduleMetadata

        when:
        def next = source.create('repo', componentResolvers, id, DefaultComponentOverrideMetadata.EMPTY, resolver, new DefaultBuildableModuleComponentMetaDataResolveResult())

        then:
        1 * workerLeaseService.runAsWorkerThread(_ as Runnable) >> { Runnable action -> action.run() }
        1 * executor.runAll(_) >> { Action action -> action.execute(queue) }
        2 * queue.addUnconstrained(_) >> { RunnableBuildOperation operation -> operation.run(context) }
        1 * resolver.resolveArtifact({ it.id == pom.id }, _) >> pomResource
        1 * resolver.resolveArtifact({ it.id == module.id }, _) >> moduleResource
        1 * pomSource.create(_, _, id, _, _, _) >> { repo, components, identifier, overrides, downloaded, target ->
            assert downloaded.resolveArtifact(pom, target) == pomResource
            target.redirectToGradleMetadata()
            pomMetadata
        }
        1 * moduleSource.create(_, _, id, _, _, _) >> { repo, components, identifier, overrides, downloaded, target ->
            assert downloaded.resolveArtifact(module, target) == moduleResource
            moduleMetadata
        }
        0 * resolver._
        next == moduleMetadata

        where:
        redirect | missing
        false    | false
        false    | true
        true     | true
    }

    def "snapshot metadata retains the sequential path"() {
        given:
        learnRedirectingGroup()
        def identifier = DefaultModuleComponentIdentifier.newId(DefaultModuleIdentifier.newId('test', 'lib'), version)
        def overrides = changing ? DefaultComponentOverrideMetadata.EMPTY.withChanging() : DefaultComponentOverrideMetadata.EMPTY

        when:
        source.create('repo', componentResolvers, identifier, overrides, resolver, result)

        then:
        1 * pomSource.create('repo', componentResolvers, identifier, overrides, resolver, result) >> pomMetadata
        0 * executor._
        0 * resolver._

        where:
        version      | changing
        '1-SNAPSHOT' | false
        '1-SNAPSHOT' | true
    }

    private void learnRedirectingGroup() {
        def bootstrap = DefaultModuleComponentIdentifier.newId(DefaultModuleIdentifier.newId('test', 'bootstrap'), '1')
        def bootstrapResult = new DefaultBuildableModuleComponentMetaDataResolveResult()
        1 * pomSource.create('repo', componentResolvers, bootstrap, DefaultComponentOverrideMetadata.EMPTY, resolver, bootstrapResult) >> { repo, components, identifier, overrides, downloaded, target ->
            target.redirectToGradleMetadata()
            pomMetadata
        }
        1 * moduleSource.create('repo', componentResolvers, bootstrap, DefaultComponentOverrideMetadata.EMPTY, resolver, bootstrapResult) >> moduleMetadata
        assert source.create('repo', componentResolvers, bootstrap, DefaultComponentOverrideMetadata.EMPTY, resolver, bootstrapResult) == moduleMetadata
    }
}