package dev.configflow.infrastructure.credential;

import com.sun.jna.LastErrorException;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;

/**
 * Minimal JNA binding to the Windows Credential Manager functions in {@code advapi32.dll}.
 *
 * <p>jna-platform ships neither these functions nor the {@code CREDENTIAL} struct, so we
 * declare them ourselves — the same approach Microsoft's own vsts-authentication-library
 * takes. Only the pieces {@link WindowsCredentialStore} actually uses are modelled; the
 * rest of the Win32 credential surface is left out on purpose.</p>
 *
 * <h2>Conventions that must stay exactly as written</h2>
 * <ul>
 *   <li><b>Wide strings.</b> The methods are the {@code ...W} entry points and every string
 *       field/param is a {@link WString} (UTF-16), so no ANSI/Unicode type-mapper is needed.</li>
 *   <li><b>Struct layout.</b> {@code @FieldOrder} and the field types mirror {@code CREDENTIALW}
 *       byte-for-byte, including {@code FILETIME} spelled out as its two {@code DWORD} halves.</li>
 *   <li><b>Last error.</b> Each fallible method declares {@code throws LastErrorException}; JNA
 *       then captures {@code GetLastError()} right after the call and throws it, which is how the
 *       adapter distinguishes {@link #ERROR_NOT_FOUND} from a real failure.</li>
 * </ul>
 */
interface CredAdvapi32 extends StdCallLibrary {

    CredAdvapi32 INSTANCE = Native.load("Advapi32", CredAdvapi32.class);

    /** Generic credential (application-defined blob), as opposed to a domain password. */
    int CRED_TYPE_GENERIC = 1;

    /** Persist for this user across logon sessions on this machine. */
    int CRED_PERSIST_LOCAL_MACHINE = 2;

    /**
     * Largest {@code CredentialBlob} Windows accepts ({@code 5 * 512} bytes). A bigger
     * secret makes {@code CredWriteW} fail with {@code ERROR_INVALID_PARAMETER}, which is
     * a caller error, so the adapter rejects it up front rather than letting it read as a
     * system failure.
     */
    int CRED_MAX_CREDENTIAL_BLOB_SIZE = 5 * 512;

    /** {@code GetLastError()} value when a target name has no stored credential. */
    int ERROR_NOT_FOUND = 1168;

    /**
     * {@code CredWriteW}: creates or overwrites the credential described by {@code credential}.
     * Windows copies the blob into its own store, so the caller's secret array need not outlive
     * this call.
     */
    boolean CredWriteW(CREDENTIAL credential, int flags) throws LastErrorException;

    /**
     * {@code CredReadW}: reads the credential for {@code targetName} into {@code pcredential}.
     * On success {@code pcredential.credential} points at a system-allocated {@code CREDENTIALW}
     * that must later be handed to {@link #CredFree(Pointer)}.
     */
    boolean CredReadW(WString targetName, int type, int flags, PCREDENTIAL pcredential)
            throws LastErrorException;

    /** {@code CredDeleteW}: removes the credential for {@code targetName}. */
    boolean CredDeleteW(WString targetName, int type, int flags) throws LastErrorException;

    /** {@code CredFree}: releases a buffer returned by {@link #CredReadW}. Never fails for us. */
    void CredFree(Pointer credential);

    /**
     * {@code CREDENTIALW}. Field order and types mirror the Win32 struct exactly; only
     * {@code TargetName}, {@code Comment}, {@code CredentialBlob(Size)}, {@code UserName},
     * {@code Type} and {@code Persist} carry meaning for us — the rest stay zero/null.
     */
    @Structure.FieldOrder({
            "Flags", "Type", "TargetName", "Comment",
            "LastWrittenLow", "LastWrittenHigh",
            "CredentialBlobSize", "CredentialBlob", "Persist", "AttributeCount",
            "Attributes", "TargetAlias", "UserName"
    })
    class CREDENTIAL extends Structure {
        public int Flags;
        public int Type;
        public WString TargetName;
        public WString Comment;
        // FILETIME LastWritten, spelled out so no nested struct (and no jna-platform) is needed.
        public int LastWrittenLow;
        public int LastWrittenHigh;
        public int CredentialBlobSize;
        public Pointer CredentialBlob;
        public int Persist;
        public int AttributeCount;
        public Pointer Attributes; // CREDENTIAL_ATTRIBUTEW* — unused, kept null with count 0
        public WString TargetAlias;
        public WString UserName;

        public CREDENTIAL() {
            super();
        }

        /** Maps a system-allocated {@code CREDENTIALW} (from {@link #CredReadW}) for reading. */
        public CREDENTIAL(Pointer memory) {
            super(memory);
            read();
        }
    }

    /**
     * Out-parameter for {@link #CredReadW}: a {@code PCREDENTIALW} (pointer to a
     * {@code CREDENTIALW}). JNA passes this struct by reference, so the function fills in
     * {@link #credential}.
     */
    @Structure.FieldOrder({"credential"})
    class PCREDENTIAL extends Structure {
        public Pointer credential;
    }
}
