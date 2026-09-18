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
package org.gradle.api.internal.artifacts.query;

import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.artifacts.query.ArtifactResolutionQuery;
import org.gradle.api.artifacts.result.ArtifactResolutionResult;
import org.gradle.api.artifacts.result.ArtifactResult;
import org.gradle.api.artifacts.result.ComponentArtifactsResult;
import org.gradle.api.artifacts.result.ComponentResult;
import org.gradle.api.component.Artifact;
import org.gradle.api.component.Component;
import org.gradle.api.internal.artifacts.DefaultModuleIdentifier;
import org.gradle.api.internal.artifacts.RepositoriesSupplier;
import org.gradle.api.internal.artifacts.configurations.ResolutionStrategyFactory;
import org.gradle.api.internal.artifacts.configurations.ResolutionStrategyInternal;
import org.gradle.api.internal.artifacts.dsl.ComponentMetadataHandlerInternal;
import org.gradle.api.internal.artifacts.dsl.ComponentMetadataRulesSupplier;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ComponentResolvers;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ErrorHandlingArtifactResolver;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ExternalModuleComponentResolverFactory;
import org.gradle.api.internal.artifacts.repositories.ContentFilteringRepository;
import org.gradle.api.internal.artifacts.repositories.ResolutionAwareRepository;
import org.gradle.api.internal.artifacts.result.DefaultArtifactResolutionResult;
import org.gradle.api.internal.artifacts.result.DefaultComponentArtifactsResult;
import org.gradle.api.internal.artifacts.result.DefaultResolvedArtifactResult;
import org.gradle.api.internal.artifacts.result.DefaultUnresolvedArtifactResult;
import org.gradle.api.internal.artifacts.result.DefaultUnresolvedComponentResult;
import org.gradle.api.internal.attributes.ImmutableAttributes;
import org.gradle.api.internal.attributes.immutable.ImmutableAttributesSchema;
import org.gradle.api.internal.component.ArtifactType;
import org.gradle.api.internal.component.ComponentTypeRegistry;
import org.gradle.internal.Describables;
import org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier;
import org.gradle.internal.component.external.model.ImmutableCapabilities;
import org.gradle.internal.component.model.ComponentArtifactMetadata;
import org.gradle.internal.component.model.ComponentArtifactResolveMetadata;
import org.gradle.internal.component.model.DefaultComponentOverrideMetadata;
import org.gradle.internal.operations.BuildOperationContext;
import org.gradle.internal.operations.BuildOperationDescriptor;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.operations.RunnableBuildOperation;
import org.gradle.internal.resolve.resolver.ArtifactResolver;
import org.gradle.internal.resolve.resolver.ComponentMetaDataResolver;
import org.gradle.internal.resolve.result.BuildableArtifactResolveResult;
import org.gradle.internal.resolve.result.BuildableArtifactSetResolveResult;
import org.gradle.internal.resolve.result.BuildableComponentResolveResult;
import org.gradle.internal.resolve.result.DefaultBuildableArtifactResolveResult;
import org.gradle.internal.resolve.result.DefaultBuildableArtifactSetResolveResult;
import org.gradle.internal.resolve.result.DefaultBuildableComponentResolveResult;
import org.gradle.internal.work.WorkerLeaseService;
import org.gradle.util.internal.CollectionUtils;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class DefaultArtifactResolutionQuery implements ArtifactResolutionQuery {

    private static final int MAX_PARALLEL_COMPONENTS = 8;
    private static final String PARALLEL_QUERY_PROPERTY = "org.gradle.internal.resolve.artifacts.parallelQuery";

    private final ResolutionStrategyFactory resolutionStrategyFactory;
    private final RepositoriesSupplier repositoriesSupplier;
    private final ExternalModuleComponentResolverFactory externalResolverFactory;
    private final ComponentMetadataRulesSupplier componentMetadataRulesSupplier;
    private final ComponentMetadataHandlerInternal componentMetadataHandler;
    private final ComponentTypeRegistry componentTypeRegistry;
    private final BuildOperationExecutor buildOperationExecutor;
    private final WorkerLeaseService workerLeaseService;

    private final Set<ComponentIdentifier> componentIds = new LinkedHashSet<>();
    private Class<? extends Component> componentType;
    private final Set<Class<? extends Artifact>> artifactTypes = new LinkedHashSet<>();

    public DefaultArtifactResolutionQuery(
        ResolutionStrategyFactory resolutionStrategyFactory,
        RepositoriesSupplier repositoriesSupplier,
        ExternalModuleComponentResolverFactory externalResolverFactory,
        ComponentMetadataRulesSupplier componentMetadataRulesSupplier,
        ComponentMetadataHandlerInternal componentMetadataHandler,
        ComponentTypeRegistry componentTypeRegistry,
        BuildOperationExecutor buildOperationExecutor,
        WorkerLeaseService workerLeaseService
    ) {
        this.resolutionStrategyFactory = resolutionStrategyFactory;
        this.repositoriesSupplier = repositoriesSupplier;
        this.externalResolverFactory = externalResolverFactory;
        this.componentMetadataRulesSupplier = componentMetadataRulesSupplier;
        this.componentMetadataHandler = componentMetadataHandler;
        this.componentTypeRegistry = componentTypeRegistry;
        this.buildOperationExecutor = buildOperationExecutor;
        this.workerLeaseService = workerLeaseService;
    }

    @Override
    public ArtifactResolutionQuery forComponents(Iterable<? extends ComponentIdentifier> componentIds) {
        CollectionUtils.addAll(this.componentIds, componentIds);
        return this;
    }

    @Override
    public ArtifactResolutionQuery forComponents(ComponentIdentifier... componentIds) {
        CollectionUtils.addAll(this.componentIds, componentIds);
        return this;
    }

    @Override
    public ArtifactResolutionQuery forModule(@NonNull String group, @NonNull String name, @NonNull String version) {
        componentIds.add(DefaultModuleComponentIdentifier.newId(DefaultModuleIdentifier.newId(group, name), version));
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ArtifactResolutionQuery withArtifacts(Class<? extends Component> componentType, Class<? extends Artifact>... artifactTypes) {
        return withArtifacts(componentType, Arrays.asList(artifactTypes));
    }

    @Override
    public ArtifactResolutionQuery withArtifacts(Class<? extends Component> componentType, Collection<Class<? extends Artifact>> artifactTypes) {
        if (this.componentType != null) {
            throw new IllegalStateException("Cannot specify component type multiple times.");
        }
        this.componentType = componentType;
        this.artifactTypes.addAll(artifactTypes);
        return this;
    }

    @Override
    public ArtifactResolutionResult execute() {
        if (componentType == null) {
            throw new IllegalStateException("Must specify component type and artifacts to query.");
        }

        List<? extends ResolutionAwareRepository> repositories = repositoriesSupplier.get();
        List<ResolutionAwareRepository> filteredRepositories = repositories.stream()
            .filter(repository -> {
                if (repository instanceof ContentFilteringRepository) {
                    ContentFilteringRepository cfr = (ContentFilteringRepository) repository;
                    // If the repository requires certain request attributes or requires certain configurations,
                    // it should not be used for ARQs.
                    return cfr.getRequiredAttributes() == null && cfr.getIncludedConfigurations() == null;
                }
                return true;
            })
            .collect(Collectors.toList());

        // We use a resolution strategy here in order to use the same defaults for dependency verification,
        // caching, etc. that a normal dependency resolution would use.
        ResolutionStrategyInternal resolutionStrategy = resolutionStrategyFactory.create();

        ComponentResolvers componentResolvers = externalResolverFactory.createResolvers(
            filteredRepositories,
            componentMetadataRulesSupplier.getRules(),
            componentMetadataHandler.getVariantDerivationStrategy(),
            resolutionStrategy.getComponentSelection(),
            resolutionStrategy.isDependencyVerificationEnabled(),
            resolutionStrategy.getCachePolicy().asImmutable(),
            ImmutableAttributesSchema.EMPTY
        );

        ComponentMetaDataResolver componentMetaDataResolver = componentResolvers.getComponentResolver();
        ArtifactResolver artifactResolver = new ErrorHandlingArtifactResolver(componentResolvers.getArtifactResolver());
        return createResult(componentMetaDataResolver, artifactResolver);
    }

    private ArtifactResolutionResult createResult(ComponentMetaDataResolver componentMetaDataResolver, ArtifactResolver artifactResolver) {
        if (componentIds.size() > 1 && !artifactTypes.isEmpty() && Boolean.parseBoolean(System.getProperty(PARALLEL_QUERY_PROPERTY, "true"))) {
            return createParallelResult(componentMetaDataResolver, artifactResolver);
        }
        Set<ComponentResult> componentResults = new LinkedHashSet<>();

        for (ComponentIdentifier componentId : componentIds) {
            try {
                ComponentIdentifier validId = validateComponentIdentifier(componentId);
                componentResults.add(buildComponentResult(validId, componentMetaDataResolver, artifactResolver));
            } catch (Exception t) {
                componentResults.add(new DefaultUnresolvedComponentResult(componentId, t));
            }
        }

        return new DefaultArtifactResolutionResult(componentResults);
    }

    private ArtifactResolutionResult createParallelResult(ComponentMetaDataResolver componentMetaDataResolver, ArtifactResolver artifactResolver) {
        Set<ComponentResult> componentResults = new LinkedHashSet<>();
        Iterator<ComponentIdentifier> remaining = componentIds.iterator();
        while (remaining.hasNext()) {
            List<ArtifactRetrieval> retrievals = new ArrayList<>(MAX_PARALLEL_COMPONENTS);
            List<Supplier<ComponentResult>> results = new ArrayList<>(MAX_PARALLEL_COMPONENTS);
            // Metadata rules and the component type registry belong to the calling thread, not the download workers.
            for (int i = 0; i < MAX_PARALLEL_COMPONENTS && remaining.hasNext(); i++) {
                ComponentIdentifier componentId = remaining.next();
                try {
                    ComponentArtifactResolveMetadata component = prepareComponent(validateComponentIdentifier(componentId), componentMetaDataResolver);
                    Map<Class<? extends Artifact>, ArtifactType> types = new LinkedHashMap<>();
                    for (Class<? extends Artifact> type : artifactTypes) {
                        types.put(type, componentTypeRegistry.getComponentRegistration(componentType).getArtifactType(type));
                    }
                    ArtifactRetrieval retrieval = new ArtifactRetrieval(componentId, component, types, artifactResolver);
                    retrievals.add(retrieval);
                    results.add(retrieval::getResult);
                } catch (Exception e) {
                    results.add(() -> new DefaultUnresolvedComponentResult(componentId, e));
                }
            }
            if (retrievals.size() == 1) {
                retrievals.get(0).retrieve();
            } else if (!retrievals.isEmpty()) {
                workerLeaseService.runAsWorkerThread(() -> buildOperationExecutor.runAll(queue -> {
                    for (ArtifactRetrieval retrieval : retrievals) {
                        queue.addUnconstrained(retrieval);
                    }
                }));
            }
            // runAll joins the bounded batch before verification or preparation of the next batch.
            for (Supplier<ComponentResult> result : results) {
                componentResults.add(result.get());
            }
        }
        return new DefaultArtifactResolutionResult(componentResults);
    }

    private ComponentIdentifier validateComponentIdentifier(ComponentIdentifier componentId) {
        if (componentId instanceof ModuleComponentIdentifier) {
            return componentId;
        }
        if (componentId instanceof ProjectComponentIdentifier) {
            throw new IllegalArgumentException(String.format("Cannot query artifacts for a project component (%s).", componentId.getDisplayName()));
        }

        throw new IllegalArgumentException(String.format("Cannot resolve the artifacts for component %s with unsupported type %s.", componentId.getDisplayName(), componentId.getClass().getName()));
    }

    private ComponentArtifactsResult buildComponentResult(ComponentIdentifier componentId, ComponentMetaDataResolver componentMetaDataResolver, ArtifactResolver artifactResolver) {
        ComponentArtifactResolveMetadata component = prepareComponent(componentId, componentMetaDataResolver);
        DefaultComponentArtifactsResult componentResult = new DefaultComponentArtifactsResult(component.getId());
        for (Class<? extends Artifact> type : artifactTypes) {
            ArtifactType artifactType = componentTypeRegistry.getComponentRegistration(componentType).getArtifactType(type);
            addArtifacts(artifact -> componentResult.addArtifact(verifyArtifact(artifact)), type, artifactType, component, artifactResolver);
        }
        return componentResult;
    }

    private ComponentArtifactResolveMetadata prepareComponent(ComponentIdentifier componentId, ComponentMetaDataResolver componentMetaDataResolver) {
        BuildableComponentResolveResult moduleResolveResult = new DefaultBuildableComponentResolveResult();
        componentMetaDataResolver.resolve(componentId, DefaultComponentOverrideMetadata.EMPTY, moduleResolveResult);
        return moduleResolveResult.getState().prepareForArtifactResolution().getArtifactMetadata();
    }

    private <T extends Artifact> void addArtifacts(
        Consumer<ArtifactResult> artifacts,
        Class<T> type,
        ArtifactType artifactType,
        ComponentArtifactResolveMetadata component,
        ArtifactResolver artifactResolver
    ) {
        BuildableArtifactSetResolveResult artifactSetResolveResult = new DefaultBuildableArtifactSetResolveResult();
        artifactResolver.resolveArtifactsWithType(component, artifactType, artifactSetResolveResult);

        for (ComponentArtifactMetadata artifactMetaData : artifactSetResolveResult.getResult()) {
            BuildableArtifactResolveResult resolveResult = new DefaultBuildableArtifactResolveResult();
            artifactResolver.resolveArtifact(component, artifactMetaData, resolveResult);
            try {
                artifacts.accept(new DefaultResolvedArtifactResult(artifactMetaData.getId(), ImmutableAttributes.EMPTY, ImmutableCapabilities.EMPTY, Describables.of(component.getId().getDisplayName()), type, resolveResult.getResult().getFile()));
            } catch (Exception e) {
                artifacts.accept(new DefaultUnresolvedArtifactResult(artifactMetaData.getId(), type, e));
            }
        }
    }

    private ArtifactResult verifyArtifact(ArtifactResult artifact) {
        if (artifact instanceof DefaultResolvedArtifactResult) {
            DefaultResolvedArtifactResult resolved = (DefaultResolvedArtifactResult) artifact;
            try {
                return externalResolverFactory.verifiedArtifact(resolved);
            } catch (Exception e) {
                return new DefaultUnresolvedArtifactResult(resolved.getId(), resolved.getType(), e);
            }
        }
        return artifact;
    }

    private class ArtifactRetrieval implements RunnableBuildOperation {
        private final ComponentIdentifier componentId;
        private final ComponentArtifactResolveMetadata component;
        private final Map<Class<? extends Artifact>, ArtifactType> types;
        private final ArtifactResolver artifactResolver;
        private final List<ArtifactResult> artifacts = new ArrayList<>();
        @Nullable
        private Exception failure;

        private ArtifactRetrieval(ComponentIdentifier componentId, ComponentArtifactResolveMetadata component, Map<Class<? extends Artifact>, ArtifactType> types, ArtifactResolver artifactResolver) {
            this.componentId = componentId;
            this.component = component;
            this.types = types;
            this.artifactResolver = artifactResolver;
        }

        @Override
        public void run(BuildOperationContext context) {
            retrieve();
        }

        private void retrieve() {
            try {
                for (Map.Entry<Class<? extends Artifact>, ArtifactType> type : types.entrySet()) {
                    addArtifacts(artifacts::add, type.getKey(), type.getValue(), component, artifactResolver);
                }
            } catch (Exception e) {
                failure = e;
            }
        }

        private ComponentResult getResult() {
            DefaultComponentArtifactsResult result = new DefaultComponentArtifactsResult(component.getId());
            for (ArtifactResult artifact : artifacts) {
                result.addArtifact(verifyArtifact(artifact));
            }
            return failure == null ? result : new DefaultUnresolvedComponentResult(componentId, failure);
        }

        @Override
        public BuildOperationDescriptor.Builder description() {
            return BuildOperationDescriptor.displayName("Resolve artifacts for " + componentId.getDisplayName());
        }
    }

}
