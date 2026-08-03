/*
 * infrastructure:credential: OS credential store adapters.
 *   - InMemoryCredentialStore: process-memory placeholder / non-Windows fallback.
 *   - WindowsCredentialStore:  Windows Credential Manager via JNA (advapi32).
 * Depends on domain only (never on application, bootstrap or sibling infra modules).
 */
plugins {
    `java-library`
}

dependencies {
    implementation(project(":domain"))
    // JNA core only: StdCallLibrary, Structure, Memory, WString, LastErrorException.
    // We hand-roll the advapi32 Cred* binding (jna-platform ships neither the
    // functions nor the CREDENTIAL struct), so jna-platform is not needed.
    implementation("net.java.dev.jna:jna:5.19.1")
}
