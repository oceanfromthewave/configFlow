/*
 * application: use-case layer. Pure Java, depends on domain ports only.
 */
plugins {
    `java-library`
}

dependencies {
    api(project(":domain"))
}
