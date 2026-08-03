/*
 * infrastructure:vcs-git: JGit adapter implementing the domain VCS ports.
 * Depends on domain only (never on application, bootstrap or sibling infra modules).
 */
plugins {
    `java-library`
}

dependencies {
    implementation(project(":domain"))
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.7.1.202607240634-r")
}
