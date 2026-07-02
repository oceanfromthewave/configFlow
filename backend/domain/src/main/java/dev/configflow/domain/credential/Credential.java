package dev.configflow.domain.credential;

import java.util.Objects;

/**
 * A credential for a remote host. The secret only ever lives in the OS credential
 * store; SQLite keeps a reference key ({@code credential_ref} table).
 *
 * @param host     e.g. {@code github.com}, {@code svn.company.com}
 * @param protocol {@code https}, {@code ssh} or {@code svn}
 * @param username account name, may be {@code null} for token-only auth
 * @param secret   password/token/passphrase (mutable array so it can be wiped)
 */
public record Credential(String host, String protocol, String username, char[] secret) {

    public Credential {
        Objects.requireNonNull(host, "host must not be null");
        Objects.requireNonNull(protocol, "protocol must not be null");
        Objects.requireNonNull(secret, "secret must not be null");
    }

    /** Never leaks the secret into logs. */
    @Override
    public String toString() {
        return "Credential[host=" + host + ", protocol=" + protocol
                + ", username=" + username + ", secret=***]";
    }
}
