/*
 * infrastructure:vcs-svn: SVNKit adapter implementing the domain VCS ports.
 * Depends on domain only (never on application, bootstrap or sibling infra modules).
 */
plugins {
    `java-library`
}

dependencies {
    implementation(project(":domain"))
    implementation("org.tmatesoft.svnkit:svnkit:1.10.11")
}
