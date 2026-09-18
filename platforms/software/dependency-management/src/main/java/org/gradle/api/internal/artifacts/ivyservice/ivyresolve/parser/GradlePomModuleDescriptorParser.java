/*
 * Copyright 2011 the original author or authors.
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
package org.gradle.api.internal.artifacts.ivyservice.ivyresolve.parser;

import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.internal.artifacts.DefaultModuleIdentifier;
import org.gradle.api.internal.artifacts.ImmutableModuleIdentifierFactory;
import org.gradle.api.internal.artifacts.dependencies.DefaultImmutableVersionConstraint;
import org.gradle.api.internal.artifacts.dependencies.DefaultMutableVersionConstraint;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.parser.PomReader.PomDependencyData;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.parser.data.MavenDependencyKey;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.parser.data.PomDependencyMgt;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.ExactVersionSelector;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.MavenVersionSelectorScheme;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelector;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelectorScheme;
import org.gradle.api.internal.artifacts.repositories.metadata.MavenMutableModuleMetadataFactory;
import org.gradle.api.internal.component.ArtifactType;
import org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier;
import org.gradle.internal.component.external.model.DefaultModuleComponentSelector;
import org.gradle.internal.component.external.model.maven.MavenDependencyDescriptor;
import org.gradle.internal.component.external.model.maven.MutableMavenModuleResolveMetadata;
import org.gradle.internal.operations.BuildOperationContext;
import org.gradle.internal.operations.BuildOperationDescriptor;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.operations.RunnableBuildOperation;
import org.gradle.internal.resource.local.FileResourceRepository;
import org.gradle.internal.resource.local.LocallyAvailableExternalResource;
import org.gradle.internal.work.WorkerLeaseService;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;

/**
 * This based on a copy of org.apache.ivy.plugins.parser.m2.PomModuleDescriptorParser, but now heavily refactored.
 */
public final class GradlePomModuleDescriptorParser extends AbstractModuleDescriptorParser<MutableMavenModuleResolveMetadata> {
    private static final Logger LOGGER = LoggerFactory.getLogger(GradlePomModuleDescriptorParser.class);
    private static final String DEPENDENCY_IMPORT_SCOPE = "import";
    private static final int IMPORT_PREFETCH_BATCH_SIZE = 8;
    private final VersionSelectorScheme gradleVersionSelectorScheme;
    private final VersionSelectorScheme mavenVersionSelectorScheme;
    private final ImmutableModuleIdentifierFactory moduleIdentifierFactory;
    private final MavenMutableModuleMetadataFactory metadataFactory;
    @Nullable
    private final BuildOperationExecutor buildOperationExecutor;
    @Nullable
    private final WorkerLeaseService workerLeaseService;
    private final Semaphore importPrefetch = new Semaphore(1);

    public GradlePomModuleDescriptorParser(VersionSelectorScheme gradleVersionSelectorScheme,
                                           ImmutableModuleIdentifierFactory moduleIdentifierFactory,
                                           FileResourceRepository fileResourceRepository, MavenMutableModuleMetadataFactory metadataFactory) {
        this(gradleVersionSelectorScheme, moduleIdentifierFactory, fileResourceRepository, metadataFactory, null, null);
    }

    public GradlePomModuleDescriptorParser(VersionSelectorScheme gradleVersionSelectorScheme,
                                           ImmutableModuleIdentifierFactory moduleIdentifierFactory,
                                           FileResourceRepository fileResourceRepository, MavenMutableModuleMetadataFactory metadataFactory,
                                           @Nullable BuildOperationExecutor buildOperationExecutor, @Nullable WorkerLeaseService workerLeaseService) {
        super(fileResourceRepository);
        this.gradleVersionSelectorScheme = gradleVersionSelectorScheme;
        mavenVersionSelectorScheme = new MavenVersionSelectorScheme(gradleVersionSelectorScheme);
        this.moduleIdentifierFactory = moduleIdentifierFactory;
        this.metadataFactory = metadataFactory;
        this.buildOperationExecutor = buildOperationExecutor;
        this.workerLeaseService = workerLeaseService;
    }

    @Override
    protected String getTypeName() {
        return "POM";
    }

    @Override
    public String toString() {
        return "gradle pom parser";
    }

    @Override
    protected ParseResult<MutableMavenModuleResolveMetadata> doParseDescriptor(DescriptorParseContext parserSettings, LocallyAvailableExternalResource resource, boolean validate) throws IOException, ParseException, SAXException {
        PomReader pomReader = new PomReader(resource, moduleIdentifierFactory);
        GradlePomModuleDescriptorBuilder mdBuilder = new GradlePomModuleDescriptorBuilder(pomReader, gradleVersionSelectorScheme, mavenVersionSelectorScheme);

        doParsePom(parserSettings, mdBuilder, pomReader);

        List<MavenDependencyDescriptor> dependencies = mdBuilder.getDependencies();
        ModuleComponentIdentifier cid = mdBuilder.getComponentIdentifier();
        MutableMavenModuleResolveMetadata metadata = metadataFactory.create(cid, dependencies);
        metadata.setStatus(mdBuilder.getStatus());
        if (pomReader.getRelocation() != null) {
            metadata.setPackaging("pom");
            metadata.setRelocated(true);
        } else {
            metadata.setPackaging(pomReader.getPackaging());
            metadata.setRelocated(false);
        }
        return ParseResult.of(metadata, pomReader.hasGradleMetadataMarker());
    }

    private void doParsePom(DescriptorParseContext parserSettings, GradlePomModuleDescriptorBuilder mdBuilder, PomReader pomReader) throws IOException, SAXException {
        pomReader.resolveGAV();

        String groupId = pomReader.getGroupId();
        String artifactId = pomReader.getArtifactId();
        String version = pomReader.getVersion();

        if (pomReader.hasParent()) {
            //Is there any other parent properties?

            String parentGroupId = pomReader.getParentGroupId();
            String parentArtifactId = pomReader.getParentArtifactId();
            String parentVersion = pomReader.getParentVersion();

            if (!(Objects.equals(parentGroupId, groupId) && Objects.equals(parentArtifactId, artifactId) && Objects.equals(parentVersion, version))) {
                // Only attempt loading the parent if it has different coordinates
                ModuleComponentSelector parentId = DefaultModuleComponentSelector.newSelector(
                    DefaultModuleIdentifier.newId(parentGroupId, parentArtifactId),
                    new DefaultImmutableVersionConstraint(parentVersion));
                PomReader parentPomReader = parsePomForSelector(parserSettings, parentId, pomReader.getAllPomProperties());
                pomReader.setPomParent(parentPomReader);

                // Current POM can derive version/artifactId from parent. Resolve GAV and substitute values
                pomReader.resolveGAV();
                groupId = pomReader.getGroupId();
                artifactId = pomReader.getArtifactId();
                version = pomReader.getVersion();
            }
        }
        mdBuilder.setModuleRevId(groupId, artifactId, version);

        ModuleVersionIdentifier relocation = pomReader.getRelocation();
        if (relocation != null) {
            if (groupId != null && artifactId != null && artifactId.equals(relocation.getName()) && groupId.equals(relocation.getGroup())) {
                LOGGER.error("POM relocation to an other version number is not fully supported in Gradle : {} relocated to {}.",
                    mdBuilder.getComponentIdentifier(), relocation);
                LOGGER.warn("Please update your dependency to directly use the correct version '{}'.", relocation);
                LOGGER.warn("Resolution will only pick dependencies of the relocated element.  Artifacts and other metadata will be ignored.");
                PomReader relocatedModule = parsePomForId(parserSettings, DefaultModuleComponentIdentifier.newId(relocation), new HashMap<>());
                addDependencies(mdBuilder, relocatedModule);
            } else {
                LOGGER.info(mdBuilder.getComponentIdentifier()
                    + " is relocated to " + relocation
                    + ". Please update your dependencies.");
                LOGGER.debug("Relocated module will be considered as a dependency");
                ModuleComponentSelector selector = DefaultModuleComponentSelector.newSelector(
                    DefaultModuleIdentifier.newId(relocation.getGroup(), relocation.getName()), new DefaultMutableVersionConstraint(relocation.getVersion()));
                mdBuilder.addDependencyForRelocation(selector);
            }
        } else {
            overrideDependencyMgtsWithImported(parserSettings, pomReader);
            addDependencies(mdBuilder, pomReader);
        }
    }

    private void addDependencies(GradlePomModuleDescriptorBuilder mdBuilder, PomReader pomReader) {
        for (PomDependencyMgt dependencyMgt : pomReader.getDependencyMgt().values()) {
            if (!isDependencyImportScoped(dependencyMgt)) {
                mdBuilder.addConstraint(dependencyMgt);
            }
        }

        for (PomDependencyData dependency : pomReader.getDependencies().values()) {
            mdBuilder.addDependency(dependency);
        }
    }

    /**
     * Overrides existing dependency management information with imported ones if existing.
     *
     * @param parseContext Parse context
     * @param pomReader POM reader
     */
    private void overrideDependencyMgtsWithImported(DescriptorParseContext parseContext, PomReader pomReader) throws IOException, SAXException {
        Map<MavenDependencyKey, PomDependencyMgt> importedDependencyMgts = parseImportedDependencyMgts(parseContext, pomReader.parseDependencyMgt());
        pomReader.addImportedDependencyMgts(importedDependencyMgts);
    }

    /**
     * Parses imported dependency management information.
     *
     * @param parseContext Parse context
     * @param currentDependencyMgts Current dependency management information
     * @return Imported dependency management information
     */
    private Map<MavenDependencyKey, PomDependencyMgt> parseImportedDependencyMgts(DescriptorParseContext parseContext, Collection<PomDependencyMgt> currentDependencyMgts) throws IOException, SAXException {
        Map<MavenDependencyKey, PomDependencyMgt> importedDependencyMgts = new LinkedHashMap<>();

        List<PomDependencyMgt> dependencies = new ArrayList<>(currentDependencyMgts);
        for (int i = 0; i < dependencies.size(); i++) {
            if (i % IMPORT_PREFETCH_BATCH_SIZE == 0) {
                prefetchImports(parseContext, dependencies.subList(i, Math.min(i + IMPORT_PREFETCH_BATCH_SIZE, dependencies.size())));
            }
            PomDependencyMgt currentDependencyMgt = dependencies.get(i);
            if (isDependencyImportScoped(currentDependencyMgt)) {
                ModuleComponentSelector importedId = DefaultModuleComponentSelector.newSelector(
                    DefaultModuleIdentifier.newId(currentDependencyMgt.getGroupId(), currentDependencyMgt.getArtifactId()),
                    new DefaultImmutableVersionConstraint(currentDependencyMgt.getVersion()));
                PomReader importedPom = parsePomForSelector(parseContext, importedId, new HashMap<>());
                for (Map.Entry<MavenDependencyKey, PomDependencyMgt> entry : importedPom.getDependencyMgt().entrySet()) {
                    if (!importedDependencyMgts.containsKey(entry.getKey())) {
                        importedDependencyMgts.put(entry.getKey(), entry.getValue());
                    }
                }
            }
        }
        return importedDependencyMgts;
    }

    private void prefetchImports(DescriptorParseContext parseContext, List<PomDependencyMgt> dependencies) {
        if (buildOperationExecutor == null || workerLeaseService == null || !Boolean.parseBoolean(System.getProperty("org.gradle.internal.resolve.metadata.parallelBom", "true")) || !importPrefetch.tryAcquire()) {
            return;
        }
        try {
            List<ModuleComponentIdentifier> candidates = new ArrayList<>();
            for (PomDependencyMgt dependency : dependencies) {
                try {
                    String version = dependency.getVersion();
                    if (isDependencyImportScoped(dependency) && "pom".equals(dependency.getType())
                        && isResolvedCoordinate(dependency.getGroupId()) && isResolvedCoordinate(dependency.getArtifactId())
                        && isResolvedCoordinate(version) && !version.endsWith("-SNAPSHOT")) {
                        VersionSelector selector = mavenVersionSelectorScheme.parseSelector(version);
                        if (selector instanceof ExactVersionSelector && version.equals(selector.getSelector())) {
                            candidates.add(DefaultModuleComponentIdentifier.newId(DefaultModuleIdentifier.newId(dependency.getGroupId(), dependency.getArtifactId()), version));
                        }
                    }
                } catch (RuntimeException ignored) {
                    // Invalid coordinates must fail in the original sequential parse order, not during speculation.
                }
            }
            if (candidates.size() > 1) {
                // Parsing may run on an unconstrained thread, but creating a queue requires a worker lease.
                // The queue releases this lease while waiting for the unconstrained downloads.
                workerLeaseService.runAsWorkerThread(() -> buildOperationExecutor.runAll(queue -> {
                    for (ModuleComponentIdentifier candidate : candidates) {
                        queue.addUnconstrained(new RunnableBuildOperation() {
                            @Override
                            public void run(BuildOperationContext context) {
                                try {
                                    parseContext.prefetchPom(candidate);
                                } catch (RuntimeException ignored) {
                                    // Only the authoritative repository-chain lookup may report resolution failures.
                                }
                            }

                            @Override
                            public BuildOperationDescriptor.Builder description() {
                                return BuildOperationDescriptor.displayName("Prefetch imported BOM " + candidate.getDisplayName());
                            }
                        });
                    }
                }));
            }
        } finally {
            importPrefetch.release();
        }
    }

    private static boolean isResolvedCoordinate(@Nullable String value) {
        return value != null && !value.isEmpty() && !value.contains("${");
    }

    /**
     * Checks if dependency has scope "import".
     *
     * @param dependencyMgt Dependency management element
     * @return Flag
     */
    private boolean isDependencyImportScoped(PomDependencyMgt dependencyMgt) {
        return DEPENDENCY_IMPORT_SCOPE.equals(dependencyMgt.getScope());
    }

    private PomReader parsePomForId(DescriptorParseContext parseContext, ModuleComponentIdentifier identifier, Map<String, String> childProperties) throws IOException, SAXException {
        return parsePomResource(parseContext, parseContext.getMetaDataArtifact(identifier, ArtifactType.MAVEN_POM), childProperties);
    }

    private PomReader parsePomForSelector(DescriptorParseContext parseContext, ModuleComponentSelector selector, Map<String, String> childProperties) throws IOException, SAXException {
        VersionSelector acceptor = mavenVersionSelectorScheme.parseSelector(selector.getVersion());
        LocallyAvailableExternalResource localResource = parseContext.getMetaDataArtifact(selector, acceptor, ArtifactType.MAVEN_POM);
        return parsePomResource(parseContext, localResource, childProperties);
    }

    private PomReader parsePomResource(DescriptorParseContext parseContext, LocallyAvailableExternalResource localResource, Map<String, String> childProperties) throws SAXException, IOException {
        PomReader pomReader = new PomReader(localResource, moduleIdentifierFactory, childProperties);
        GradlePomModuleDescriptorBuilder mdBuilder = new GradlePomModuleDescriptorBuilder(pomReader, gradleVersionSelectorScheme, mavenVersionSelectorScheme);
        doParsePom(parseContext, mdBuilder, pomReader);
        return pomReader;
    }
}
