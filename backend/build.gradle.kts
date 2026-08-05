/*
 * Root build: shared conventions for every leaf module.
 *
 * Layering rules (docs/04-directory-structure.md) are enforced by the module
 * dependency declarations in each leaf build script and verified again by
 * ArchUnit tests in :bootstrap.
 */
plugins {
    id("org.springframework.boot") version "4.0.7" apply false
}

subprojects {
    // ":infrastructure" is only a container project, not a Java module.
    if (childProjects.isNotEmpty()) {
        return@subprojects
    }

    apply(plugin = "java-library")

    group = "dev.configflow"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.11.4"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
}
