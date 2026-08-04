pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Auto-provisions the JDK required by the Java toolchain (JDK 21) even when
    // the machine only has an older JDK installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "configflow-backend"

include(
    "domain",
    "application",
    "infrastructure:vcs-git",
    "infrastructure:vcs-svn",
    "infrastructure:persistence",
    "infrastructure:credential",
    "infrastructure:ai-providers",
    "bootstrap",
)
