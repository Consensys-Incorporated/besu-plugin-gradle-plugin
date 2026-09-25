/*
 * Copyright Consensys Software Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package net.consensys.gradle;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import groovy.json.JsonSlurper;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.plugins.JavaLibraryPlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSetContainer;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

public abstract class BesuPluginLibrary implements Plugin<Project> {
  static final String RESOLVE_BESU_DEPS_TASK_NAME = "resolveBesuProvidedDependencies";
  static final String BESU_PROVIDED_DEPENDENCIES_RELATIVE_PATH =
      "reports/dependencies/besu-provided-dependencies.txt";
  static final String BESU_MAIN_JAR_CONFIGURATION_NAME = "besuMainJar";
  static final String BESU_BOM_DEPENDENCY_COORDINATES = "org.hyperledger.besu:bom";
  static final String BESU_MAIN_DEPENDENCY_COORDINATES = "org.hyperledger.besu.internal:besu-app";
  static final String BESU_ARTIFACTS_CATALOG_RESOURCE_NAME =
      "/META-INF/besu-artifacts-catalog.json";
  static final String DEFAULT_BESU_REPO = "https://hyperledger.jfrog.io/hyperledger/besu-maven/";
  private static final Set<String> ANNOTATION_PROCESSOR_DEPENDENCIES =
      Set.of("com.google.auto.service:auto-service");

  @Override
  public void apply(final Project project) {
    project.getPluginManager().apply(JavaLibraryPlugin.class);

    // Create the extension
    BesuPluginLibraryExtension extension =
        project.getExtensions().create("besuPlugin", BesuPluginLibraryExtension.class);
    extension
        .getBesuRepo()
        .convention(project.getProviders().gradleProperty("besuRepo").orElse(DEFAULT_BESU_REPO));
    extension.getBesuVersion().convention(project.getProviders().gradleProperty("besuVersion"));
    extension.getConfigureRepositories().convention(true);
    Provider<String> besuVersionProvider = extension.getBesuVersion();

    // The Besu main jar embeds the Besu artifacts catalog
    Configuration besuMainJarConfiguration =
        project
            .getConfigurations()
            .create(
                BESU_MAIN_JAR_CONFIGURATION_NAME,
                cfg -> {
                  cfg.setCanBeConsumed(false);
                  cfg.setCanBeResolved(true);
                  cfg.getDependencies()
                      .addLater(
                          besuVersionProvider.map(
                              besuVersion ->
                                  project
                                      .getDependencies()
                                      .create(
                                          BESU_MAIN_DEPENDENCY_COORDINATES
                                              + ":"
                                              + besuVersion
                                              + "@jar")));
                });

    project
        .getTasks()
        .register(
            RESOLVE_BESU_DEPS_TASK_NAME,
            ResolveBesuProvidedDependenciesTask.class,
            task -> {
              task.setGroup("Build");
              task.setDescription(
                  "Resolves the coordinates of the dependencies provided by Besu, from the Besu artifacts catalog.");
              task.getBesuVersion().set(besuVersionProvider);
              task.getBesuMainJar().from(besuMainJarConfiguration);
              task.getBesuProvidedDependenciesFile()
                  .set(
                      project
                          .getLayout()
                          .getBuildDirectory()
                          .file(BESU_PROVIDED_DEPENDENCIES_RELATIVE_PATH));
            });

    // Repository URLs are not lazy, so wait for the extension to be configured
    project.afterEvaluate(
        p -> {
          if (extension.getConfigureRepositories().get()) {
            configureRepositories(project, extension.getBesuRepo().get());
          }
        });

    // The callbacks below are lazy: the BOM and the catalog are only parsed when a configuration
    // is actually resolved.
    ManagedDependencies managedDependencies = new ManagedDependencies(project, besuVersionProvider);

    addPlatformConstraints(project, besuVersionProvider);

    for (String configName : List.of("compileOnly", "testImplementation", "testCompileOnly")) {
      project
          .getConfigurations()
          .getByName(configName)
          .withDependencies(deps -> deps.addAll(managedDependencies.dependencies()));
    }

    project
        .getConfigurations()
        .getByName("annotationProcessor")
        .withDependencies(
            deps -> {
              for (Dependency dep : managedDependencies.dependencies()) {
                if (ANNOTATION_PROCESSOR_DEPENDENCIES.contains(
                    dep.getGroup() + ":" + dep.getName())) {
                  deps.add(dep);
                }
              }
            });

    // Only apply the resolution rules to the classpaths of the source sets, leaving untouched
    // the configurations of other tools (e.g. code formatters or linters)
    project
        .getExtensions()
        .getByType(SourceSetContainer.class)
        .configureEach(
            sourceSet -> {
              for (String configName :
                  List.of(
                      sourceSet.getCompileClasspathConfigurationName(),
                      sourceSet.getRuntimeClasspathConfigurationName(),
                      sourceSet.getAnnotationProcessorConfigurationName())) {
                project
                    .getConfigurations()
                    .named(configName)
                    .configure(
                        cfg ->
                            configureResolutionRules(
                                cfg, managedDependencies, besuVersionProvider));
              }
            });
  }

  private static String requireBesuVersion(final Provider<String> besuVersionProvider) {
    if (!besuVersionProvider.isPresent()) {
      throw new IllegalStateException(
          "besuVersion must be set either in besuPlugin extension or as a project property");
    }
    return besuVersionProvider.get();
  }

  private void addPlatformConstraints(
      final Project project, final Provider<String> besuVersionProvider) {
    for (String configName :
        List.of(
            "annotationProcessor",
            "api",
            "implementation",
            "testImplementation",
            "compileOnly",
            "testCompileOnly",
            "runtimeOnly",
            "testRuntimeOnly")) {
      project
          .getConfigurations()
          .getByName(configName)
          .withDependencies(
              deps ->
                  deps.add(
                      project
                          .getDependencies()
                          .enforcedPlatform(
                              BESU_BOM_DEPENDENCY_COORDINATES
                                  + ":"
                                  + requireBesuVersion(besuVersionProvider))));
    }
  }

  private void configureResolutionRules(
      final Configuration configuration,
      final ManagedDependencies managedDependencies,
      final Provider<String> besuVersionProvider) {
    configuration.resolutionStrategy(
        strategy -> {
          // Force the versions managed by Besu
          strategy.eachDependency(
              details -> {
                String group = details.getRequested().getGroup();
                String managedVersion =
                    managedDependencies
                        .managedVersions()
                        .get(group + ":" + details.getRequested().getName());
                boolean isBesuCoordinate =
                    "org.hyperledger.besu".equals(group)
                        || "org.hyperledger.besu.internal".equals(group);
                boolean hasRequestedVersion =
                    details.getRequested().getVersion() != null
                        && !details.getRequested().getVersion().isBlank();
                if (managedVersion != null && (!hasRequestedVersion || isBesuCoordinate)) {
                  details.useVersion(managedVersion);
                }
              });

          // Rewrite Besu old coordinates to the new ones
          strategy
              .getDependencySubstitution()
              .all(
                  substitution -> {
                    if (substitution.getRequested() instanceof ModuleComponentSelector mcs) {
                      var newCoord =
                          BesuOld2NewCoordinatesMapping.getOld2NewCoordinates()
                              .get(mcs.getGroup() + ":" + mcs.getModule());
                      if (newCoord != null) {
                        substitution.useTarget(
                            newCoord + ":" + requireBesuVersion(besuVersionProvider),
                            "Migrated to new Besu coordinates");
                      }
                    }
                  });

          // Exclude Besu old coordinates
          strategy
              .getComponentSelection()
              .all(
                  selection -> {
                    ModuleComponentIdentifier candidate = selection.getCandidate();
                    if (BesuOld2NewCoordinatesMapping.getOld2NewCoordinates()
                        .containsKey(candidate.getGroup() + ":" + candidate.getModule())) {
                      selection.reject(
                          "Excluded Besu old coordinate: "
                              + candidate.getGroup()
                              + ":"
                              + candidate.getModule());
                    }
                  });
        });
  }

  private void configureRepositories(final Project project, final String besuRepo) {
    addMavenRepository(project, besuRepo, "org.hyperledger.besu");
    if (!normalizeUrl(besuRepo).equals(normalizeUrl(DEFAULT_BESU_REPO))) {
      addMavenRepository(project, DEFAULT_BESU_REPO, "org.hyperledger.besu");
    }
    addMavenRepository(
        project, "https://artifacts.consensys.net/public/maven/maven/", "tech.pegasys");
    addMavenRepository(project, "https://splunk.jfrog.io/splunk/ext-releases-local/", "com.splunk");
    project.getRepositories().mavenCentral();
  }

  private static void addMavenRepository(
      final Project project, final String url, final String group) {
    project
        .getRepositories()
        .maven(
            repository -> {
              repository.setUrl(URI.create(url));
              repository.mavenContent(content -> content.includeGroupAndSubgroups(group));
            });
  }

  private static String normalizeUrl(final String url) {
    return url.endsWith("/") ? url : url + "/";
  }

  private static List<Dependency> resolveBomDependencies(
      final Project project, final String besuVersion) {
    Configuration bomConfiguration =
        project
            .getConfigurations()
            .detachedConfiguration(
                project
                    .getDependencies()
                    .create(BESU_BOM_DEPENDENCY_COORDINATES + ":" + besuVersion + "@pom"));
    bomConfiguration.setCanBeResolved(true);
    File besuBom = bomConfiguration.getSingleFile();
    try {
      return parseBesuBOM(project, besuBom);
    } catch (ParserConfigurationException | IOException | SAXException e) {
      throw new RuntimeException("Unable to parse the Besu BOM " + besuBom, e);
    }
  }

  private static List<BesuProvidedDependency> resolveCatalogDependencies(
      final Project project, final String besuVersion) {
    Configuration besuDependencyCatalogConfiguration =
        project
            .getConfigurations()
            .detachedConfiguration(
                project
                    .getDependencies()
                    .create(BESU_MAIN_DEPENDENCY_COORDINATES + ":" + besuVersion + "@jar"));
    besuDependencyCatalogConfiguration.setCanBeResolved(true);
    File besuMainJar = besuDependencyCatalogConfiguration.getSingleFile();
    return parseBesuDependencyCatalog(project, readBesuArtifactsCatalog(besuMainJar));
  }

  /** Reads the entries of the Besu artifacts catalog embedded in the Besu main jar. */
  @SuppressWarnings("unchecked")
  static List<Map<String, String>> readBesuArtifactsCatalog(final File besuMainJar) {
    try (FileSystem zipFs = FileSystems.newFileSystem(besuMainJar.toPath())) {
      String besuDependencyCatalog =
          Files.readString(zipFs.getPath(BESU_ARTIFACTS_CATALOG_RESOURCE_NAME));
      return (List<Map<String, String>>) new JsonSlurper().parseText(besuDependencyCatalog);
    } catch (IOException e) {
      throw new RuntimeException(
          "Unable to read the Besu artifacts catalog from " + besuMainJar, e);
    }
  }

  private static List<Dependency> mergeDependencies(
      final List<Dependency> bomDependencies,
      final List<BesuProvidedDependency> besuProvidedDependencies) {
    List<Dependency> mergedDependencies = new ArrayList<>(bomDependencies);
    Set<String> bomCoordinates = new HashSet<>();
    for (Dependency bomDependency : bomDependencies) {
      bomCoordinates.add(bomDependency.getGroup() + ":" + bomDependency.getName());
    }
    for (BesuProvidedDependency providedDependency : besuProvidedDependencies) {
      Dependency dependency = providedDependency.dependency();
      if (!bomCoordinates.contains(dependency.getGroup() + ":" + dependency.getName())) {
        mergedDependencies.add(dependency);
      }
    }

    return mergedDependencies;
  }

  private static List<BesuProvidedDependency> parseBesuDependencyCatalog(
      final Project project, final List<Map<String, String>> besuDependencyCatalog) {
    List<BesuProvidedDependency> besuProvidedDependencies = new ArrayList<>();

    for (Map<String, String> dependency : besuDependencyCatalog) {
      besuProvidedDependencies.add(
          new BesuProvidedDependency(
              project
                  .getDependencies()
                  .create(
                      dependency.get("group")
                          + ":"
                          + dependency.get("name")
                          + ":"
                          + dependency.get("version")
                          + "!!"
                          + (dependency.containsKey("classifier")
                              ? ":" + dependency.get("classifier")
                              : "")),
              dependency.get("filename")));
    }
    return besuProvidedDependencies;
  }

  private static List<Dependency> parseBesuBOM(final Project project, final File besuBom)
      throws ParserConfigurationException, IOException, SAXException {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    Element projectElement = factory.newDocumentBuilder().parse(besuBom).getDocumentElement();

    Element dependenciesElement =
        getRequiredChild(getRequiredChild(projectElement, "dependencyManagement"), "dependencies");

    List<Dependency> bomDependencies = new ArrayList<>();
    for (Element depElement : getChildren(dependenciesElement, "dependency")) {
      if ("pom".equals(getChildText(depElement, "type"))) {
        // imported BOM
        continue;
      }
      String groupId = getRequiredChildText(depElement, "groupId");
      String artifactId = getRequiredChildText(depElement, "artifactId");
      String version = getRequiredChildText(depElement, "version");
      if (version.contains("${")) {
        throw new IllegalStateException(
            "Unsupported property placeholder in version of %s:%s in the Besu BOM: %s"
                .formatted(groupId, artifactId, version));
      }
      String classifier = getChildText(depElement, "classifier");

      bomDependencies.add(
          project
              .getDependencies()
              .create(
                  groupId
                      + ":"
                      + artifactId
                      + ":"
                      + version
                      + "!!"
                      + (classifier != null ? ":" + classifier : "")));
    }
    return bomDependencies;
  }

  private static List<Element> getChildren(final Element parent, final String name) {
    List<Element> children = new ArrayList<>();
    for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (child instanceof Element element && element.getTagName().equals(name)) {
        children.add(element);
      }
    }
    return children;
  }

  private static Element getRequiredChild(final Element parent, final String name) {
    List<Element> children = getChildren(parent, name);
    if (children.isEmpty()) {
      throw new IllegalStateException(
          "Element %s not found in element %s".formatted(name, parent.getTagName()));
    }
    return children.getFirst();
  }

  private static String getChildText(final Element parent, final String name) {
    List<Element> children = getChildren(parent, name);
    return children.isEmpty() ? null : children.getFirst().getTextContent().trim();
  }

  private static String getRequiredChildText(final Element parent, final String name) {
    String text = getChildText(parent, name);
    if (text == null || text.isEmpty()) {
      throw new IllegalStateException(
          "Element %s not found in element %s".formatted(name, parent.getTagName()));
    }
    return text;
  }

  /** The dependencies managed by Besu, lazily resolved from the Besu BOM and catalog. */
  private static final class ManagedDependencies {
    private final Project project;
    private final Provider<String> besuVersionProvider;
    private volatile List<Dependency> dependencies;
    private volatile Map<String, String> managedVersions;

    ManagedDependencies(final Project project, final Provider<String> besuVersionProvider) {
      this.project = project;
      this.besuVersionProvider = besuVersionProvider;
    }

    List<Dependency> dependencies() {
      ensureResolved();
      return dependencies;
    }

    Map<String, String> managedVersions() {
      ensureResolved();
      return managedVersions;
    }

    private void ensureResolved() {
      if (managedVersions != null) {
        return;
      }
      synchronized (this) {
        if (managedVersions != null) {
          return;
        }
        String besuVersion = requireBesuVersion(besuVersionProvider);
        List<Dependency> mergedDeps =
            mergeDependencies(
                resolveBomDependencies(project, besuVersion),
                resolveCatalogDependencies(project, besuVersion));

        Map<String, String> versionsByCoordinates = new HashMap<>();
        for (Dependency dep : mergedDeps) {
          String managedVersion = dep.getVersion();
          if (dep instanceof ExternalModuleDependency extDep) {
            String requiredVersion = extDep.getVersionConstraint().getRequiredVersion();
            if (requiredVersion != null && !requiredVersion.isBlank()) {
              managedVersion = requiredVersion;
            }
          }
          if (dep.getGroup() != null
              && dep.getName() != null
              && managedVersion != null
              && !managedVersion.isBlank()) {
            versionsByCoordinates.put(dep.getGroup() + ":" + dep.getName(), managedVersion);
          }
        }
        dependencies = List.copyOf(mergedDeps);
        // written last, since it is the initialization flag
        managedVersions = Map.copyOf(versionsByCoordinates);
      }
    }
  }

  record BesuProvidedDependency(Dependency dependency, String filename) {}
}
