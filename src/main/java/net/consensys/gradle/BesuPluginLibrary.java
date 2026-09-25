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
import java.io.FileReader;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.xml.parsers.ParserConfigurationException;

import groovy.json.JsonSlurper;
import groovy.xml.DOMBuilder;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.DependencySet;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.plugins.JavaLibraryPlugin;
import org.gradle.api.provider.Provider;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
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
  private static final Set<String> ANNOTATION_PROCESSOR_DEPENDENCIES =
      Set.of("com.google.auto.service:auto-service");

  @Override
  public void apply(final Project project) {
    project.getPluginManager().apply(JavaLibraryPlugin.class);

    // Create the extension
    BesuPluginLibraryExtension extension =
        project.getExtensions().create("besuPlugin", BesuPluginLibraryExtension.class);

    // Set default value for besuRepo
    extension.getBesuRepo().convention("https://hyperledger.jfrog.io/hyperledger/besu-maven/");
    extension.getBesuVersion().convention(project.getProviders().gradleProperty("besuVersion"));
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

    // Configure after project evaluation to allow extension configuration.
    // Only repositories and resolution strategies are set here — none of these
    // trigger dependency resolution. The expensive BOM/catalog parsing is deferred
    // to withDependencies callbacks (execution phase).
    project.afterEvaluate(
        p -> {
          String besuRepo = extension.getBesuRepo().get();
          if (!project.hasProperty("besuRepo")) {
            project.getExtensions().getExtraProperties().set("besuRepo", besuRepo);
          }

          configureRepositories(project, besuRepo);
          addPlatformConstraints(project, besuVersionProvider);
          excludeOldCoordinatesBesuDependencies(project);
          rewriteOldCoordinatesBesuDependencies(project, besuVersionProvider);

          // Lazy dependency injection: parse BOM + catalog only when a configuration
          // actually resolves (execution phase), not during afterEvaluate.
          AtomicBoolean initialized = new AtomicBoolean(false);
          Object resolutionLock = new Object();
          AtomicReference<List<Dependency>> mergedDepsRef = new AtomicReference<>(List.of());
          AtomicReference<Map<String, String>> managedVersionsByCoordinatesRef =
              new AtomicReference<>(Map.of());
          Runnable ensureResolved =
              () -> {
                if (initialized.get()) {
                  return;
                }
                synchronized (resolutionLock) {
                  if (initialized.get()) {
                    return;
                  }
                  String besuVersion = requireBesuVersion(besuVersionProvider);
                  List<Dependency> bomDeps = resolveBomDependencies(project, besuVersion);
                  List<BesuProvidedDependency> catalogDeps =
                      resolveCatalogDependencies(project, besuVersion);

                  List<Dependency> mergedDeps = mergeDependencies(bomDeps, catalogDeps);
                  Map<String, String> managedVersionsByCoordinates = new HashMap<>();
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
                      managedVersionsByCoordinates.put(
                          dep.getGroup() + ":" + dep.getName(), managedVersion);
                    }
                  }
                  mergedDepsRef.set(List.copyOf(mergedDeps));
                  managedVersionsByCoordinatesRef.set(Map.copyOf(managedVersionsByCoordinates));
                  initialized.set(true);
                }
              };

          for (String configName :
              List.of("compileOnly", "testImplementation", "testCompileOnly")) {
            project
                .getConfigurations()
                .getByName(configName)
                .withDependencies(
                    (DependencySet deps) -> {
                      ensureResolved.run();
                      for (Dependency dep : mergedDepsRef.get()) {
                        deps.add(dep);
                      }
                    });
          }

          project
              .getConfigurations()
              .configureEach(
                  cfg ->
                      cfg.getResolutionStrategy()
                          .eachDependency(
                              details -> {
                                ensureResolved.run();
                                String key =
                                    details.getRequested().getGroup()
                                        + ":"
                                        + details.getRequested().getName();
                                String managedVersion =
                                    managedVersionsByCoordinatesRef.get().get(key);
                                boolean isBesuCoordinate =
                                    "org.hyperledger.besu".equals(details.getRequested().getGroup())
                                        || "org.hyperledger.besu.internal"
                                            .equals(details.getRequested().getGroup());
                                boolean hasRequestedVersion =
                                    details.getRequested().getVersion() != null
                                        && !details.getRequested().getVersion().isBlank();
                                if (managedVersion != null
                                    && !managedVersion.isBlank()
                                    && (!hasRequestedVersion || isBesuCoordinate)) {
                                  details.useVersion(managedVersion);
                                }
                              }));

          project
              .getConfigurations()
              .getByName("annotationProcessor")
              .withDependencies(
                  (DependencySet deps) -> {
                    ensureResolved.run();
                    for (Dependency dep : mergedDepsRef.get()) {
                      if (ANNOTATION_PROCESSOR_DEPENDENCIES.contains(
                          dep.getGroup() + ":" + dep.getName())) {
                        deps.add(dep);
                      }
                    }
                  });
        });
  }

  private String requireBesuVersion(final Provider<String> besuVersionProvider) {
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

  private List<Dependency> resolveBomDependencies(final Project project, final String besuVersion) {
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
      throw new RuntimeException(e);
    }
  }

  private List<BesuProvidedDependency> resolveCatalogDependencies(
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

  private void configureRepositories(final Project project, final String besuRepo) {
    project
        .getRepositories()
        .maven(
            mavenArtifactRepository -> {
              mavenArtifactRepository.setUrl(URI.create(besuRepo));
              mavenArtifactRepository.mavenContent(
                  mavenRepositoryContentDescriptor ->
                      mavenRepositoryContentDescriptor.includeGroupAndSubgroups(
                          "org.hyperledger.besu"));
            });
    project
        .getRepositories()
        .maven(
            mavenArtifactRepository -> {
              mavenArtifactRepository.setUrl(
                  URI.create("https://hyperledger.jfrog.io/hyperledger/besu-maven/"));
              mavenArtifactRepository.mavenContent(
                  mavenRepositoryContentDescriptor ->
                      mavenRepositoryContentDescriptor.includeGroupAndSubgroups(
                          "org.hyperledger.besu"));
            });
    project
        .getRepositories()
        .maven(
            mavenArtifactRepository -> {
              mavenArtifactRepository.setUrl(
                  URI.create("https://artifacts.consensys.net/public/maven/maven/"));
              mavenArtifactRepository.mavenContent(
                  mavenRepositoryContentDescriptor ->
                      mavenRepositoryContentDescriptor.includeGroupAndSubgroups("tech.pegasys"));
            });
    project
        .getRepositories()
        .maven(
            mavenArtifactRepository -> {
              mavenArtifactRepository.setUrl(
                  URI.create("https://splunk.jfrog.io/splunk/ext-releases-local/"));
              mavenArtifactRepository.mavenContent(
                  mavenRepositoryContentDescriptor ->
                      mavenRepositoryContentDescriptor.includeGroupAndSubgroups("com.splunk"));
            });

    project.getRepositories().mavenCentral();
    project.getRepositories().mavenLocal();
  }

  private List<Dependency> mergeDependencies(
      final List<Dependency> bomDependencies,
      final List<BesuProvidedDependency> besuProvidedDependencies) {
    List<Dependency> mergedDependencies = new ArrayList<>(bomDependencies);
    for (BesuProvidedDependency providedDependency : besuProvidedDependencies) {
      if (bomDependencies.stream()
          .noneMatch(
              bomDependency ->
                  bomDependency.getGroup().equals(providedDependency.dependency().getGroup())
                      && bomDependency
                          .getName()
                          .equals(providedDependency.dependency().getName()))) {
        mergedDependencies.add(providedDependency.dependency());
      }
    }

    return mergedDependencies;
  }

  private List<BesuProvidedDependency> parseBesuDependencyCatalog(
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

  private List<Dependency> parseBesuBOM(final Project project, final File besuBom)
      throws ParserConfigurationException, IOException, SAXException {
    List<Dependency> bomDependencies = new ArrayList<>();
    Node dependencyManagementNode =
        DOMBuilder.parse(new FileReader(besuBom))
            .getDocumentElement()
            .getElementsByTagName("dependencyManagement")
            .item(0);

    Element dependenciesElement = getElement(dependencyManagementNode, "dependencies");

    List<Element> dependencyElements =
        getElements(dependenciesElement.getElementsByTagName("dependency"), "dependency");

    for (Element depElement : dependencyElements) {
      var typeElement = depElement.getElementsByTagName("type");
      boolean isBom =
          typeElement.getLength() > 0
              && depElement.getElementsByTagName("type").item(0).getTextContent().equals("pom");
      if (!isBom) {
        var groupId = depElement.getElementsByTagName("groupId").item(0).getTextContent();
        var artifactId = depElement.getElementsByTagName("artifactId").item(0).getTextContent();
        var version = depElement.getElementsByTagName("version").item(0).getTextContent();
        var classifierElement = depElement.getElementsByTagName("classifier");

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
                        + (classifierElement.getLength() > 0
                            ? ":" + classifierElement.item(0).getTextContent()
                            : "")));
      }
    }
    return bomDependencies;
  }

  private void excludeOldCoordinatesBesuDependencies(final Project project) {
    project
        .getConfigurations()
        .all(
            configuration -> {
              configuration.resolutionStrategy(
                  strategy -> {
                    strategy
                        .getComponentSelection()
                        .all(
                            selection -> {
                              ModuleComponentIdentifier requested = selection.getCandidate();
                              var groupId = requested.getGroup();
                              var moduleId = requested.getModule();

                              // Exclude Besu old coordinates
                              if (isOldCoordinate(groupId, moduleId)) {
                                selection.reject(
                                    "Excluded Besu old coordinate: " + groupId + ":" + moduleId);
                              }
                            });
                  });
            });
  }

  private void rewriteOldCoordinatesBesuDependencies(
      final Project project, final Provider<String> besuVersionProvider) {
    project
        .getConfigurations()
        .all(
            configuration -> {
              configuration.resolutionStrategy(
                  strategy -> {
                    strategy
                        .getDependencySubstitution()
                        .all(
                            substitution -> {
                              var requested = substitution.getRequested();
                              if (requested instanceof ModuleComponentSelector mcs) {
                                var coord = mcs.getGroup() + ":" + mcs.getModule();
                                var newCoord =
                                    BesuOld2NewCoordinatesMapping.getOld2NewCoordinates()
                                        .get(coord);

                                if (newCoord != null) {
                                  substitution.useTarget(
                                      newCoord + ":" + requireBesuVersion(besuVersionProvider),
                                      "Migrated to new Besu coordinates");
                                }
                              }
                            });
                  });
            });
  }

  private boolean isOldCoordinate(String group, String module) {
    return BesuOld2NewCoordinatesMapping.getOld2NewCoordinates().containsKey(group + ":" + module);
  }

  private Element getElement(Node node, String name) {
    for (int i = 0; i < node.getChildNodes().getLength(); i++) {
      if (node.getChildNodes().item(i).getNodeName().equals(name)) {
        return (Element) node.getChildNodes().item(i);
      }
    }
    throw new RuntimeException(
        "Element %s not found in node %s".formatted(name, node.getNodeName()));
  }

  private List<Element> getElements(NodeList nodeList, String name) {
    List<Element> elements = new ArrayList<>(nodeList.getLength());
    for (int i = 0; i < nodeList.getLength(); i++) {
      if (nodeList.item(i).getNodeName().equals(name)) {
        elements.add((Element) nodeList.item(i));
      }
    }
    return elements;
  }

  record BesuProvidedDependency(Dependency dependency, String filename) {}
}
