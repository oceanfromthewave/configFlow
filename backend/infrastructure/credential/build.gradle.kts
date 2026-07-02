/*
 * infrastructure:credential: OS credential store adapter (M0: in-memory placeholder).
 * Depends on domain only (never on application, bootstrap or sibling infra modules).
 */
plugins {
    `java-library`
}

dependencies {
    implementation(project(":domain"))
}
