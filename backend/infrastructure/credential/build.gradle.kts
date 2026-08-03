/*
 * infrastructure:credential: OS credential store adapters.
 *   - InMemoryCredentialStore: process-memory placeholder / non-Windows fallback.
 *   - WindowsCredentialStore:  Windows Credential Manager via JNA (advapi32).
 * Depends on domain only (never on application, bootstrap or sibling infra modules).
 *   - SshdSShKeyFactory:       Ed25519 key generation via Apache MINA sshd.
 */
plugins {
    `java-library`
}

dependencies {
    implementation(project(":domain"))
    // JNA core only: StdCallLibrary, Structure, Memory, WString, LastErrorException.
    // We hand-roll the advapi32 Cred* binding (jna-platform ships neither the
    // functions nor the CREDENTIAL struct), so jna-platform is not needed.
    implementation("net.java.dev.jna:jna:5.14.0")
    // Ed25519 generation + OpenSSH serialisation. sshd reaches EdDSA only through
    // net.i2p or BouncyCastle (its JDK path is not wired in), and BouncyCastle is the
    // maintained of the two.
    implementation("org.apache.sshd:sshd-osgi:2.15.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.81")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.81")
}
