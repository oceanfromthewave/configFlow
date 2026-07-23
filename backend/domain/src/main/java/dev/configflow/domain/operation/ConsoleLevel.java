package dev.configflow.domain.operation;

/**
 * Kind of console line produced by a running operation (docs/07 §3).
 */
public enum ConsoleLevel {

    /** The command that was executed. */
    CMD("cmd"),
    /** Ordinary output. */
    OUT("out"),
    /** Error output. */
    ERR("err");

    private final String wireName;

    ConsoleLevel(String wireName) {
        this.wireName = wireName;
    }

    /** Lowercase form used on the SSE stream. */
    public String wireName() {
        return wireName;
    }
}
