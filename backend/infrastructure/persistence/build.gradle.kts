/*
 * infrastructure:persistence: SQLite adapters (plain JDBC) + Flyway migrations.
 * Depends on domain only (never on application, bootstrap or sibling infra modules).
 */
plugins {
    `java-library`
}

dependencies {
    implementation(project(":domain"))
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    implementation("org.flywaydb:flyway-core:13.9.0")
}
