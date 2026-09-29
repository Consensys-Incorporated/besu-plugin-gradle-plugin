# Gradle plugin for Besu plugin development

## Besu Plugin DevUX

This Gradle plugin improves the Besu Plugin DevUX, making it simple to bootstrap a new plugin project, so that a developer 
should only focus on the plugin development and not think about the plumbing too much, this is achieved by avoid writing 
boilerplate code for project setup, automatic management of dependencies and advices about potential issues.

We also need to remember that plugins are meant to work in the Besu context, they are not standalone applications, 
so there are constraints and rules they must adhere to.

Ideal workflow:

* Devs should only declare which version of Besu they want to use for the plugin, then automatically all the dependencies are available without the need to manually declare them.

* Devs only needs to declare extra dependencies that are not already provided by Besu (the build process should be smart enough to detect and warn the devs in case they make an error and creates a conflict).

* When it is time to distribute the plugin, devs just use standard distribution tasks, and the build process takes care of everything, packaging the plugin artifacts, plus the custom dependencies not already provided by Besu, without the need to manually customize the output artifacts.


## Dependency conflict

Dependency conflicts can happen between Besu and a plugin or between two or more plugins, when different versions of the same dependency
are referenced, the issue could only be spotted at runtime, and only with a specific mix of Besu and plugins.
A way to mitigate this risk is to have plugins to use the Besu BOM to enforce the version of the dependencies, but that works for single plugin deployment,
when multiple plugins are involved they could bring in different versions of an extra dependency that is not covered by the BOM.

Also, the BOM evolves with Besu versions, so if a plugin is built against a previous version of Besu, it is possible that 
it brings previous versions of the 3rd party library as well.

To solve this problem, different things are needed, at build time and at runtime.

At build time we need to get rid of the uber jars, and create a distribution of the plugin that only contains the plugin jar, 
plus 3rd party jars that are not already provided by Besu, this addresses the single plugin deployment scenario.

At runtime Besu should be able to identify what different plugins are using as dependencies and warn or fail in case it detects conflicts,
this covers the multi plugins deployment or plugin built against a different version of Besu. 
This runtime check will be built inside Besu itself (<https://github.com/hyperledger/besu/issues/8551>).

### Artifact catalog

With the Gradle plugin we address the DevUX, but there are other issues to solve, that are related to the runtime phase,
when multiple plugins can conflict between themselves and with different versions of Besu.

Since at runtime we do not have all the information related to the dependencies, that were available during the build time,
a solution is to add them to the distribution of Besu and plugins.

Basically the Besu artifacts catalog contains the information about the Maven coordinates of each jar that is present in the Besu distribution
(jars in the lib folder), the same for the plugins, and the catalog is included in the distribution so it can be used at runtime
(it is embedded in the main jar of Besu and plugins).

The catalog has a double use, during the build process to complement information not present in the BOM, and at runtime,
when it can be read during the startup phase of Besu, when it is time to load plugins, when all the catalogs can be fetched
and processed to identify possible conflicts, and according to some configuration options, warn the user or fail the startup.

Besu catalog <https://github.com/hyperledger/besu/pull/8987>

## Current status

The plugin takes care of:

- Setting all the Maven repositories needed to fetch the Besu dependencies.

- Prepopulate the compile classpath with all the dependencies provided by Besu, using the BOM and an artifacts catalog (more on this later).

- Create a distribution of the plugin that only contains the plugin artifacts plus any jar that is not already provided by Besu.

What is still missing are the checks to notify the devs about possible errors they did when declaring extra dependencies.

## Usage

Set the version of Besu to use in `build.gradle` using the `besuPlugin` extension:
```groovy
besuPlugin {
    besuVersion = '26.9.0'
}
```

Then in the `build.gradle` of define that you want to use the plugin

```groovy
plugins {
    id 'net.consensys.besu-plugin-distribution' version '0.3.0'
}
```

This is enough to start coding your plugin, check an example of a simple [Hello Ethereum Plugin](examples/hello-ethereum/src/main/java/net/consensys/gradle/besuplugin/examples/hello/HelloEthereumPlugin.java)

To package the plugin for distribution just run `./gradlew distZip` that will produce an archive (`example.zip`) with all the necessary dependencies,
in this case only the single jar of the plugin is present, and to deploy it, go to the `plugins` folder in your Besu installation
(create the `plugins` folder if not already present) and unzip it with `unzip -j /path/to/example.zip`, then start Besu and you should
see the greetings in the output.

For real world plugins, you can organize the code in multiple modules, where some are just libraries that will then
be used by other modules that expose the plugins. 
Then since the libraries are not meant to be distributed as plugins, you need to use:

```groovy
plugins {
    id 'net.consensys.besu-plugin-library' version '0.3.0'
}
```

An example of a multi-module plugin is present [here](examples/multi-module/). 