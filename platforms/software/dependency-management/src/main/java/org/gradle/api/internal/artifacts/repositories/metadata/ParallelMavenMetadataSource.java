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
package org.gradle.api.internal.artifacts.repositories.metadata;

import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ComponentResolvers;
import org.gradle.api.internal.artifacts.repositories.resolver.ExternalResourceArtifactResolver;
import org.gradle.api.internal.artifacts.repositories.resolver.MavenUniqueSnapshotComponentIdentifier;
import org.gradle.internal.component.external.model.DefaultModuleComponentArtifactMetadata;
import org.gradle.internal.component.external.model.ModuleComponentArtifactMetadata;
import org.gradle.internal.component.external.model.ModuleComponentResolveMetadata;
import org.gradle.internal.component.external.model.MutableModuleComponentResolveMetadata;
import org.gradle.internal.component.model.ComponentOverrideMetadata;
import org.gradle.internal.component.model.DefaultIvyArtifactName;
import org.gradle.internal.component.model.DefaultModuleDescriptorArtifactMetadata;
import org.gradle.internal.operations.BuildOperationContext;
import org.gradle.internal.operations.BuildOperationDescriptor;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.operations.RunnableBuildOperation;
import org.gradle.internal.resolve.result.BuildableModuleComponentMetaDataResolveResult;
import org.gradle.internal.resolve.result.DefaultResourceAwareResolveResult;
import org.gradle.internal.resolve.result.ResourceAwareResolveResult;
import org.gradle.internal.resource.local.LocallyAvailableExternalResource;
import org.gradle.internal.work.WorkerLeaseService;
import org.jspecify.annotations.Nullable;

import javax.inject.Inject;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;

public class ParallelMavenMetadataSource extends RedirectingGradleMetadataModuleMetadataSource {
    private final BuildOperationExecutor executor;
    private final WorkerLeaseService workerLeaseService;
    private final Semaphore slots = new Semaphore(8);
    private final ConcurrentMap<String, Boolean> redirectingGroups = new ConcurrentHashMap<>();

    @Inject
    public ParallelMavenMetadataSource(MetadataSource<?> pomSource, MetadataSource<MutableModuleComponentResolveMetadata> moduleSource, BuildOperationExecutor executor, WorkerLeaseService workerLeaseService) {
        super(pomSource, moduleSource);
        this.executor = executor;
        this.workerLeaseService = workerLeaseService;
    }

    @Override
    public MutableModuleComponentResolveMetadata create(String repositoryName, ComponentResolvers componentResolvers, ModuleComponentIdentifier id, ComponentOverrideMetadata overrides, ExternalResourceArtifactResolver resolver, BuildableModuleComponentMetaDataResolveResult<ModuleComponentResolveMetadata> result) {
        if (id instanceof MavenUniqueSnapshotComponentIdentifier || id.getVersion().endsWith("-SNAPSHOT")) {
            return super.create(repositoryName, componentResolvers, id, overrides, resolver, result);
        }
        // Learn from authoritative POMs before speculating about a publisher in this repository.
        if (!Boolean.TRUE.equals(redirectingGroups.get(id.getGroup())) || !slots.tryAcquire()) {
            MutableModuleComponentResolveMetadata metadata = super.create(repositoryName, componentResolvers, id, overrides, resolver, result);
            redirectingGroups.put(id.getGroup(), result.shouldUseGradleMetatada());
            return metadata;
        }
        try {
            Download pom = new Download(new DefaultModuleDescriptorArtifactMetadata(id, new DefaultIvyArtifactName(id.getModule(), "pom", "pom")), resolver);
            Download module = new Download(new DefaultModuleComponentArtifactMetadata(id, new DefaultIvyArtifactName(id.getModule(), "module", "module")), resolver);
            workerLeaseService.runAsWorkerThread(() -> executor.runAll(queue -> {
                queue.addUnconstrained(pom);
                queue.addUnconstrained(module);
            }));
            // Parsing, redirection, and validation remain authoritative and ordered. Unused downloads are never parsed.
            ExternalResourceArtifactResolver downloaded = new ExternalResourceArtifactResolver() {
                @Override
                public LocallyAvailableExternalResource resolveArtifact(ModuleComponentArtifactMetadata artifact, ResourceAwareResolveResult target) {
                    if (artifact.getId().equals(pom.artifact.getId())) {
                        return pom.get(target);
                    }
                    if (artifact.getId().equals(module.artifact.getId())) {
                        return module.get(target);
                    }
                    return resolver.resolveArtifact(artifact, target);
                }

                @Override
                public boolean artifactExists(ModuleComponentArtifactMetadata artifact, ResourceAwareResolveResult target) {
                    return resolver.artifactExists(artifact, target);
                }
            };
            MutableModuleComponentResolveMetadata metadata = super.create(repositoryName, componentResolvers, id, overrides, downloaded, result);
            redirectingGroups.put(id.getGroup(), result.shouldUseGradleMetatada() && module.resource != null);
            return metadata;
        } finally {
            slots.release();
        }
    }

    private static class Download implements RunnableBuildOperation {
        private final ModuleComponentArtifactMetadata artifact;
        private final ExternalResourceArtifactResolver resolver;
        private final DefaultResourceAwareResolveResult result = new DefaultResourceAwareResolveResult();
        @Nullable
        private LocallyAvailableExternalResource resource;
        @Nullable
        private RuntimeException failure;

        private Download(ModuleComponentArtifactMetadata artifact, ExternalResourceArtifactResolver resolver) {
            this.artifact = artifact;
            this.resolver = resolver;
        }

        @Override
        public void run(BuildOperationContext context) {
            try {
                resource = resolver.resolveArtifact(artifact, result);
            } catch (RuntimeException e) {
                // A module download failure only becomes a resolution failure if the POM redirects to it.
                failure = e;
            }
        }

        @Nullable
        private LocallyAvailableExternalResource get(ResourceAwareResolveResult target) {
            result.applyTo(target);
            if (failure != null) {
                throw failure;
            }
            return resource;
        }

        @Override
        public BuildOperationDescriptor.Builder description() {
            return BuildOperationDescriptor.displayName("Download metadata candidate " + artifact.getId().getDisplayName());
        }
    }
}