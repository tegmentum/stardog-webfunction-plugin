package ai.tegmentum.stardog.kibble.webfunctions.compose;

/**
 * Runtime exception surfaced by the compose orchestrator client when
 * the wasm returns a WIT {@code result<_, error>} that discriminates
 * onto its error case, or when a lower-level component invocation
 * traps. Wraps the guest-side error message plus an optional error
 * code from {@code sys:compose/types#error-code}.
 */
public class ComposeException extends RuntimeException {

    private final String errorCode;

    public ComposeException(final String message) {
        this(null, message, null);
    }

    public ComposeException(final String errorCode, final String message) {
        this(errorCode, message, null);
    }

    public ComposeException(final String errorCode, final String message, final Throwable cause) {
        super(errorCode == null ? message : errorCode + ": " + message, cause);
        this.errorCode = errorCode;
    }

    /**
     * WIT error-code discriminator ({@code plan-invalid-cbor},
     * {@code emit-missing-blob}, etc.) if the guest surfaced one;
     * otherwise {@code null}.
     */
    public String errorCode() {
        return errorCode;
    }
}
