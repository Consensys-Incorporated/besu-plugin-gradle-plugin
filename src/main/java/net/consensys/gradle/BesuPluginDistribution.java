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

import static net.consensys.gradle.CollectPluginOnlyRuntimeArtifactsTask.PLUGIN_ARTIFACTS_CATALOG_RELATIVE_PATH;
import static net.consensys.gradle.CollectPluginOnlyRuntimeArtifactsTask.PLUGIN_ONLY_ARTIFACTS_RELATIVE_PATH;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.distribution.DistributionContainer;
import org.gradle.api.distribution.plugins.DistributionPlugin;
import org.gradle.api.file.CopySpec;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.jvm.tasks.Jar;

public abstract class BesuPluginDistribution implements Plugin<Project> {

  @Override
  public void apply(final Project project) {
    project.getPluginManager().apply(BesuPluginLibrary.class);
    project.getPluginManager().apply(DistributionPlugin.class);

    BesuPluginLibraryExtension extension =
        project.getExtensions().getByType(BesuPluginLibraryExtension.class);
    TaskProvider<ResolveBesuProvidedDependenciesTask> resolveBesuProvidedDependencies =
        project
            .getTasks()
            .named(
                BesuPluginLibrary.RESOLVE_BESU_DEPS_TASK_NAME,
                ResolveBesuProvidedDependenciesTask.class);

    TaskProvider<CollectPluginOnlyRuntimeArtifactsTask> collectPluginOnlyRuntimeArtifacts =
        project
            .getTasks()
            .register(
                CollectPluginOnlyRuntimeArtifactsTask.TASK_NAME,
                CollectPluginOnlyRuntimeArtifactsTask.class,
                task -> {
                  Configuration runtimeClasspath =
                      project.getConfigurations().getByName("runtimeClasspath");
                  task.getRuntimeArtifacts().from(runtimeClasspath);
                  task.getResolvedArtifacts()
                      .set(runtimeClasspath.getIncoming().getArtifacts().getResolvedArtifacts());
                  task.getRootComponent()
                      .set(runtimeClasspath.getIncoming().getResolutionResult().getRootComponent());
                  task.getBesuProvidedDependenciesFile()
                      .set(
                          resolveBesuProvidedDependencies.flatMap(
                              ResolveBesuProvidedDependenciesTask
                                  ::getBesuProvidedDependenciesFile));
                  task.getBesuVersion().set(extension.getBesuVersion());
                  task.getArtifactsCatalogFile()
                      .set(
                          project
                              .getLayout()
                              .getBuildDirectory()
                              .file(PLUGIN_ARTIFACTS_CATALOG_RELATIVE_PATH));
                  task.getPluginOnlyArtifactsDirectory()
                      .set(
                          project
                              .getLayout()
                              .getBuildDirectory()
                              .dir(PLUGIN_ONLY_ARTIFACTS_RELATIVE_PATH));
                });
    TaskProvider<Jar> jar = project.getTasks().named(JavaPlugin.JAR_TASK_NAME, Jar.class);
    jar.configure(
        task ->
            task.from(
                collectPluginOnlyRuntimeArtifacts.flatMap(
                    CollectPluginOnlyRuntimeArtifactsTask::getArtifactsCatalogFile),
                copySpec -> copySpec.into("META-INF/")));

    project
        .getExtensions()
        .getByType(DistributionContainer.class)
        .named(DistributionPlugin.MAIN_DISTRIBUTION_NAME)
        .configure(
            dist -> {
              CopySpec childSpec = project.copySpec();
              childSpec.from(jar);
              childSpec.from(project.file("src/dist"));
              childSpec.from(
                  collectPluginOnlyRuntimeArtifacts.flatMap(
                      CollectPluginOnlyRuntimeArtifactsTask::getPluginOnlyArtifactsDirectory));

              dist.getContents().with(childSpec);
            });
  }
}
