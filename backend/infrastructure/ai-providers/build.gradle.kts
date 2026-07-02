/*
 * infrastructure:ai-providers: AI provider adapters (M0: NoopAiProvider only).
 * Depends on domain only (never on application, bootstrap or sibling infra modules).
 */
plugins {
    `java-library`
}

dependencies {
    implementation(project(":domain"))
}
