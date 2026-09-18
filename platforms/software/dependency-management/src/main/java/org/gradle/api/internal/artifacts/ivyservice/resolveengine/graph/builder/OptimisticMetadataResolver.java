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
package org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.builder;

import org.gradle.api.artifacts.VersionConstraint;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.ExactVersionSelector;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelector;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelectorScheme;
import org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier;
import org.gradle.internal.component.external.model.ExternalModuleComponentGraphResolveState;
import org.gradle.internal.component.model.ComponentOverrideMetadata;
import org.gradle.internal.component.model.DefaultComponentOverrideMetadata;
import org.gradle.internal.component.model.DependencyMetadata;
import org.gradle.internal.component.model.GraphSelectionCandidates;
import org.gradle.internal.component.model.VariantGraphResolveState;
import org.gradle.internal.operations.BuildOperationContext;
import org.gradle.internal.operations.BuildOperationDescriptor;
import org.gradle.internal.operations.BuildOperationQueue;
import org.gradle.internal.operations.RunnableBuildOperation;
import org.gradle.internal.resolve.resolver.ComponentMetaDataResolver;
import org.gradle.internal.resolve.result.BuildableComponentResolveResult;
import org.gradle.internal.resolve.result.DefaultBuildableComponentResolveResult;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Experimental, resolution-scoped metadata lookahead. Workers only warm the normal repository caches;
 * they never select versions, attach edges, or resolve artifacts.
 */
final class OptimisticMetadataResolver implements ComponentMetaDataResolver {
    static final String ENABLED_PROPERTY = "org.gradle.internal.resolve.metadata.lookahead";
    private static final Logger LOGGER = LoggerFactory.getLogger(OptimisticMetadataResolver.class);

    private final ComponentMetaDataResolver delegate;
    private final VersionSelectorScheme versionSelectorScheme;
    private final int depth;
    private final int maxPending;
    private final int maxCandidates;
    // Guard queue lifecycle and admission together: workers must not submit after runAll's action returns.
    private final Set<ModuleComponentIdentifier> scheduled = new HashSet<>();
    private final Set<ComponentIdentifier> demanded = new HashSet<>();
    @Nullable
    private BuildOperationQueue<RunnableBuildOperation> queue;
    private int pending;
    private final AtomicInteger completed = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();

    OptimisticMetadataResolver(ComponentMetaDataResolver delegate, VersionSelectorScheme versionSelectorScheme, int depth, int maxPending, int maxCandidates) {
        this.delegate = delegate;
        this.versionSelectorScheme = versionSelectorScheme;
        this.depth = depth;
        this.maxPending = maxPending;
        this.maxCandidates = maxCandidates;
    }

    synchronized void start(BuildOperationQueue<RunnableBuildOperation> queue) {
        this.queue = queue;
    }

    synchronized void stop() {
        queue = null;
    }

    void prefetch(DependencyMetadata dependency) {
        prefetch(dependency, depth);
    }

    private void prefetch(DependencyMetadata dependency, int remainingDepth) {
        if (remainingDepth <= 0 || dependency.isConstraint() || dependency.isChanging() || !dependency.getArtifacts().isEmpty()
            || dependency instanceof LenientPlatformDependencyMetadata || !(dependency.getSelector() instanceof ModuleComponentSelector)) {
            return;
        }
        ModuleComponentSelector selector = (ModuleComponentSelector) dependency.getSelector();
        VersionConstraint constraint = selector.getVersionConstraint();
        String version = constraint.getStrictVersion();
        if (version.isEmpty()) {
            version = constraint.getRequiredVersion();
        }
        if (version.isEmpty()) {
            version = constraint.getPreferredVersion();
        }
        if (version.isEmpty()) {
            return;
        }
        VersionSelector parsed = versionSelectorScheme.parseSelector(version);
        if (!(parsed instanceof ExactVersionSelector)) {
            return;
        }
        ModuleComponentIdentifier id = DefaultModuleComponentIdentifier.newId(selector.getModuleIdentifier(), parsed.getSelector());
        synchronized (this) {
            if (queue == null || pending >= maxPending || scheduled.size() >= maxCandidates || scheduled.contains(id)) {
                return;
            }
            scheduled.add(id);
            pending++;
            queue.addUnconstrained(new PrefetchOperation(id, dependency.isTransitive() ? remainingDepth - 1 : 0));
        }
    }

    @Override
    public void resolve(ComponentIdentifier identifier, ComponentOverrideMetadata overrideMetadata, BuildableComponentResolveResult result) {
        synchronized (this) {
            demanded.add(identifier);
        }
        // Use the normal repository chain and its single-flight cache, rather than applying speculative results here.
        delegate.resolve(identifier, overrideMetadata, result);
        lookAhead(result, depth);
    }

    @Override
    public boolean isFetchingMetadataCheap(ComponentIdentifier identifier) {
        return delegate.isFetchingMetadataCheap(identifier);
    }

    private void lookAhead(BuildableComponentResolveResult result, int remainingDepth) {
        synchronized (this) {
            if (queue == null || remainingDepth <= 0) {
                return;
            }
        }
        try {
            if (result.getFailure() != null || !(result.getState() instanceof ExternalModuleComponentGraphResolveState)) {
                return;
            }
            GraphSelectionCandidates candidates = result.getState().getCandidatesForGraphVariantSelection();
            List<? extends VariantGraphResolveState> variants = candidates.getVariantsForAttributeMatching();
            for (VariantGraphResolveState variant : variants) {
                prefetchDependencies(variant, remainingDepth);
            }
            if (variants.isEmpty()) {
                VariantGraphResolveState legacy = candidates.getLegacyVariant();
                if (legacy != null) {
                    prefetchDependencies(legacy, remainingDepth);
                }
            }
        } catch (RuntimeException e) {
            // Inspecting an unselected variant can fail. Only normal resolution may report this failure.
            LOGGER.debug("Could not inspect speculative dependency metadata", e);
        }
    }

    private void prefetchDependencies(VariantGraphResolveState variant, int remainingDepth) {
        for (DependencyMetadata dependency : variant.getDependencies()) {
            prefetch(dependency, remainingDepth);
        }
    }

    synchronized void logStatistics() {
        long used = scheduled.stream().filter(demanded::contains).count();
        LOGGER.info("Metadata lookahead: scheduled={}, completed={}, failed={}, demanded={} (depth={}, maxPending={}, maxCandidates={})",
            scheduled.size(), completed.get(), failed.get(), used, depth, maxPending, maxCandidates);
    }

    private class PrefetchOperation implements RunnableBuildOperation {
        private final ModuleComponentIdentifier id;
        private final int remainingDepth;

        PrefetchOperation(ModuleComponentIdentifier id, int remainingDepth) {
            this.id = id;
            this.remainingDepth = remainingDepth;
        }

        @Override
        public void run(BuildOperationContext context) {
            DefaultBuildableComponentResolveResult result = new DefaultBuildableComponentResolveResult();
            try {
                synchronized (OptimisticMetadataResolver.this) {
                    if (queue == null) {
                        return;
                    }
                }
                delegate.resolve(id, DefaultComponentOverrideMetadata.EMPTY, result);
                if (result.getFailure() == null) {
                    completed.incrementAndGet();
                } else {
                    failed.incrementAndGet();
                }
            } catch (RuntimeException e) {
                failed.incrementAndGet();
                LOGGER.debug("Speculative metadata resolution failed for {}", id, e);
                return;
            } finally {
                synchronized (OptimisticMetadataResolver.this) {
                    pending--;
                }
            }
            // Release admission before expanding, so even a one-request window can look ahead transitively.
            lookAhead(result, remainingDepth);
        }

        @Override
        public BuildOperationDescriptor.Builder description() {
            return BuildOperationDescriptor.displayName("Prefetch metadata " + id);
        }
    }
}