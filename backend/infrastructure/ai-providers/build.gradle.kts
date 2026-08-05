/*
 * infrastructure:ai-providers: AI provider adapters (M0: NoopAiProvider only).
 * Depends on domain only (never on application, bootstrap or sibling infra modules).
 */
plugins {
    `java-library`
}

dependencies {
    implementation(project(":domain"))
    // Spring Boot 4가 쓰는 Jackson 3과 같은 좌표/버전. 런타임에 이미 올라오는 jar라 배포 크기는 그대로다.
    implementation("tools.jackson.core:jackson-databind:3.1.5")
}
