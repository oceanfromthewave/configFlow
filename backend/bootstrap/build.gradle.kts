/*
 * bootstrap: Spring Boot assembly + REST/SSE API.
 * The only module that may depend on Spring and on every other module.
 */
plugins {
    java
    id("org.springframework.boot")
}

dependencies {
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))

    implementation(project(":domain"))
    implementation(project(":application"))
    implementation(project(":infrastructure:vcs-git"))
    implementation(project(":infrastructure:vcs-svn"))
    implementation(project(":infrastructure:persistence"))
    implementation(project(":infrastructure:credential"))
    implementation(project(":infrastructure:ai-providers"))

    implementation("org.springframework.boot:spring-boot-starter-web")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.2")
}
