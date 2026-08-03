/*
 * infrastructure:vcs-git: JGit adapter implementing the domain VCS ports.
 * Depends on domain only (never on application, bootstrap or sibling infra modules).
 */
plugins {
    `java-library`
}

dependencies {
    implementation(project(":domain"))
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.3.0.202506031305-r")
    // JGit ships no SSH transport of its own since 6.0; without this artifact on the
    // classpath every git@host:... remote fails before it reaches the network.
    implementation("org.eclipse.jgit:org.eclipse.jgit.ssh.apache:7.3.0.202506031305-r")
    // Apache MINA sshd reaches Ed25519 only through net.i2p or BouncyCastle — the JDK's
    // own EdDSA is not wired in — and BouncyCastle is the maintained of the two.
    implementation("org.bouncycastle:bcprov-jdk18on:1.84")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.84")
}
