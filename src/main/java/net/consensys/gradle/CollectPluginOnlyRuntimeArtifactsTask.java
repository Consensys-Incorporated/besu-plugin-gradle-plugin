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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;

import groovy.json.JsonBuilder;
import org.gradle.api.DefaultTask;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.result.DependencyResult;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/**
 * Collects the runtime artifacts of the plugin that are not already provided by Besu. They are
 * copied into {@link #getPluginOnlyArtifactsDirectory()}, to be included in the plugin
 * distribution, and described in the plugin artifacts catalog {@link #getArtifactsCatalogFile()}.
 */
@DisableCachingByDefault(because = "Copies runtime artifacts, not worth caching")
public abstract class CollectPluginOnlyRuntimeArtifactsTask extends DefaultTask {
  static final String TASK_NAME = "collectPluginOnlyRuntimeArtifacts";
  static final String PLUGIN_ARTIFACTS_CATALOG_RELATIVE_PATH =
      "reports/dependencies/plugin-artifacts-catalog.json";
  static final String PLUGIN_ONLY_ARTIFACTS_RELATIVE_PATH = "besu-plugin/plugin-only-artifacts";

  /** The files of the runtime classpath, used to track changes of the resolved artifacts. */
  @Classpath
  public abstract ConfigurableFileCollection getRuntimeArtifacts();

  /** The resolved artifacts of the runtime classpath. */
  @Internal
  public abstract SetProperty<ResolvedArtifactResult> getResolvedArtifacts();

  /** The root of the resolved dependency graph of the runtime classpath. */
  @Internal
  public abstract Property<ResolvedComponentResult> getRootComponent();

  /** The coordinates of the dependencies provided by Besu, one {@code group:name} per line. */
  @InputFile
  @PathSensitive(PathSensitivity.NONE)
  public abstract RegularFileProperty getBesuProvidedDependenciesFile();

  @Input
  public abstract Property<String> getBesuVersion();

  @OutputFile
  public abstract RegularFileProperty getArtifactsCatalogFile();

  @OutputDirectory
  public abstract DirectoryProperty getPluginOnlyArtifactsDirectory();

  @Inject
  protected abstract FileSystemOperations getFileSystemOperations();

  @TaskAction
  public void collectRuntimeArtifacts() {
    Set<String> besuProvidedDependencies = readBesuProvidedDependencies();
    Map<ComponentIdentifier, ModuleVersionIdentifier> moduleVersions = collectModuleVersions();

    // Preserve the resolution order, so the output is stable across builds
    Map<File, ModuleVersionIdentifier> pluginOnlyRuntimeArtifacts = new LinkedHashMap<>();
    getLogger().info("Collecting pluginOnlyRuntimeArtifacts");
    for (ResolvedArtifactResult artifact : getResolvedArtifacts().get()) {
      ModuleVersionIdentifier moduleVersion =
          moduleVersions.get(artifact.getId().getComponentIdentifier());
      if (moduleVersion == null) {
        throw new IllegalStateException(
            "Unable to find the module version of the runtime artifact " + artifact.getId());
      }
      getLogger().debug("Processing {}", moduleVersion);
      if (!providedByBesu(besuProvidedDependencies, moduleVersion)) {
        getLogger()
            .info(
                "Plugin only runtime dependency {}, artifact {}",
                moduleVersion,
                artifact.getFile());
        pluginOnlyRuntimeArtifacts.put(artifact.getFile(), moduleVersion);
      }
    }

    getLogger()
        .info("Collected pluginOnlyRuntimeClasspath artifacts {}", pluginOnlyRuntimeArtifacts);

    copyPluginOnlyRuntimeArtifacts(pluginOnlyRuntimeArtifacts.keySet());
    generateArtifactsCatalog(pluginOnlyRuntimeArtifacts);
  }

  private Set<String> readBesuProvidedDependencies() {
    File besuProvidedDependenciesFile = getBesuProvidedDependenciesFile().get().getAsFile();
    try {
      return new HashSet<>(
          Files.readAllLines(besuProvidedDependenciesFile.toPath(), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new RuntimeException(
          "Unable to read Besu provided dependencies from file " + besuProvidedDependenciesFile, e);
    }
  }

  private Map<ComponentIdentifier, ModuleVersionIdentifier> collectModuleVersions() {
    Map<ComponentIdentifier, ModuleVersionIdentifier> moduleVersions = new HashMap<>();
    List<ResolvedComponentResult> toVisit = new ArrayList<>(List.of(getRootComponent().get()));
    while (!toVisit.isEmpty()) {
      ResolvedComponentResult component = toVisit.removeLast();
      if (moduleVersions.putIfAbsent(component.getId(), component.getModuleVersion()) == null) {
        for (DependencyResult dependency : component.getDependencies()) {
          if (dependency instanceof ResolvedDependencyResult resolvedDependency) {
            toVisit.add(resolvedDependency.getSelected());
          }
        }
      }
    }
    return moduleVersions;
  }

  private boolean providedByBesu(
      Set<String> besuProvidedDependencies, ModuleVersionIdentifier moduleVersion) {
    String coordinate = moduleVersion.getGroup() + ":" + moduleVersion.getName();

    if (BesuOld2NewCoordinatesMapping.getOld2NewCoordinates().containsKey(coordinate)) {
      getLogger().info("Excluding old Besu dependency {}", moduleVersion);
      return true;
    }

    if (besuProvidedDependencies.contains(coordinate)) {
      getLogger()
          .info(
              "Excluding runtime dependency {} since it is already provided by Besu",
              moduleVersion);
      return true;
    }

    return false;
  }

  private void copyPluginOnlyRuntimeArtifacts(final Set<File> pluginOnlyRuntimeArtifacts) {
    File outputDirectory = getPluginOnlyArtifactsDirectory().get().getAsFile();
    getFileSystemOperations().delete(spec -> spec.delete(outputDirectory));
    outputDirectory.mkdirs();
    for (File artifact : pluginOnlyRuntimeArtifacts) {
      try {
        Files.copy(
            artifact.toPath(),
            outputDirectory.toPath().resolve(artifact.getName()),
            StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException e) {
        throw new RuntimeException(
            "Unable to copy plugin runtime artifact " + artifact + " to " + outputDirectory, e);
      }
    }
  }

  private void generateArtifactsCatalog(
      final Map<File, ModuleVersionIdentifier> pluginOnlyRuntimeArtifacts) {
    List<Map<String, String>> jsonDependencies =
        pluginOnlyRuntimeArtifacts.entrySet().stream()
            .map(
                e -> {
                  Map<String, String> dependency = new LinkedHashMap<>();
                  dependency.put("group", e.getValue().getGroup());
                  dependency.put("name", e.getValue().getName());
                  dependency.put("version", e.getValue().getVersion());
                  dependency.put("filename", e.getKey().getName());
                  return dependency;
                })
            .toList();

    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("besuVersion", getBesuVersion().get());
    doc.put("dependencies", jsonDependencies);

    String json = new JsonBuilder(doc).toPrettyString();
    getLogger().info("Generated artifacts catalog {}", json);
    File catalogFile = getArtifactsCatalogFile().get().getAsFile();
    try {
      Files.writeString(catalogFile.toPath(), json, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException(
          "Unable to write plugin artifacts catalog to file " + catalogFile, e);
    }
  }
}
