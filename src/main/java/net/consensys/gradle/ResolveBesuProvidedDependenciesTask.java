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
import java.util.Map;
import java.util.TreeSet;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Extracts the coordinates ({@code group:name}) of the artifacts provided by Besu from the Besu
 * artifacts catalog, and writes them, one per line, to {@link #getBesuProvidedDependenciesFile()}.
 */
@CacheableTask
public abstract class ResolveBesuProvidedDependenciesTask extends DefaultTask {

  @Input
  public abstract Property<String> getBesuVersion();

  /** The Besu main jar, that embeds the Besu artifacts catalog. */
  @InputFiles
  @PathSensitive(PathSensitivity.NONE)
  public abstract ConfigurableFileCollection getBesuMainJar();

  @OutputFile
  public abstract RegularFileProperty getBesuProvidedDependenciesFile();

  @TaskAction
  public void resolve() {
    final File besuMainJar = getBesuMainJar().getSingleFile();
    final var coordinates = new TreeSet<String>();
    for (Map<String, String> entry : BesuPluginLibrary.readBesuArtifactsCatalog(besuMainJar)) {
      coordinates.add(entry.get("group") + ":" + entry.get("name"));
    }

    final File outputFile = getBesuProvidedDependenciesFile().get().getAsFile();
    try {
      Files.write(outputFile.toPath(), coordinates, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException(
          "Unable to write Besu provided dependencies to file " + outputFile, e);
    }
  }
}
