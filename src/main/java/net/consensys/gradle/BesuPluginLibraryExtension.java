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

import org.gradle.api.provider.Property;

public abstract class BesuPluginLibraryExtension {

  /** The version of Besu to use for plugin development. */
  public abstract Property<String> getBesuVersion();

  /**
   * The Maven repository URL where Besu artifacts are located, defaults to the {@code besuRepo}
   * project property, or the Hyperledger Besu repository.
   */
  public abstract Property<String> getBesuRepo();

  /**
   * Whether the plugin adds the repositories needed to fetch the Besu dependencies, defaults to
   * {@code true}. Disable it when repositories are declared in the settings, for example with
   * {@code RepositoriesMode.FAIL_ON_PROJECT_REPOS}.
   */
  public abstract Property<Boolean> getConfigureRepositories();
}
