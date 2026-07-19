package ai.tegmentum.stardog.kibble.webfunctions;

/**
 * Thrown by {@link WasmCustomSectionReader} when the input bytes are not a
 * valid WebAssembly binary — missing the {@code \0asm} magic prefix,
 * truncated header, or a section length that runs off the end of the
 * buffer.
 *
 * <p>Distinct from a section-absent condition (which the reader signals
 * via {@link java.util.Optional#empty()} on
 * {@link WasmCustomSectionReader#extractSection}). A malformed-wasm
 * failure means the plugin cannot trust the bytes at all — it is a
 * bootstrap / integrity signal, not a "section missing" signal.
 *
 * <p>Runtime exception so the extractor stays composable with the
 * capability-ask extraction path in
 * {@link StardogWasmInstance}; callers that want to distinguish
 * malformed-wasm from section-absent can catch this specifically.
 */
public final class MalformedWasmException extends RuntimeException {

    public MalformedWasmException(final String message) {
        super(message);
    }

    public MalformedWasmException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
