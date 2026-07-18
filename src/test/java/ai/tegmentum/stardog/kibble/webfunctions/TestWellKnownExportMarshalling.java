package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.wasmtime4j.wit.WitFloat32;
import ai.tegmentum.wasmtime4j.wit.WitFloat64;
import ai.tegmentum.wasmtime4j.wit.WitList;
import ai.tegmentum.wasmtime4j.wit.WitOption;
import ai.tegmentum.wasmtime4j.wit.WitRecord;
import ai.tegmentum.wasmtime4j.wit.WitString;
import ai.tegmentum.wasmtime4j.wit.WitType;
import ai.tegmentum.wasmtime4j.wit.WitValue;
import ai.tegmentum.wasmtime4j.wit.WitVariant;

import com.stardog.stark.IRI;
import com.stardog.stark.Value;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Smoke tests for the well-known-exports return-shape marshalling helpers
 * on {@link WitValueMarshaller}. Constructs synthetic WIT return values
 * that mirror what a guest exporting
 * {@code tegmentum:webfunction/search@0.1.0} or
 * {@code tegmentum:webfunction/embed@0.1.0} would emit, and asserts the
 * helpers decode them to the expected Java shapes.
 *
 * <p>These tests do not load a real wasm component — they exercise the
 * marshalling contract directly. An end-to-end test against a real
 * wf_fulltext.wasm / wf_sagegraph.wasm build is a follow-up when the
 * component-registry surface lands the wiring.
 */
public class TestWellKnownExportMarshalling {

    private final WitValueMarshaller marshaller = new WitValueMarshaller(null);

    /** Search-hit record type — records with identical shape need the same
     *  WitType instance to appear in the same WitList. */
    private static final WitType SEARCH_HIT_F64_TYPE;
    private static final WitType SEARCH_HIT_F32_TYPE;

    static {
        final WitType optionStringType = WitType.option(WitType.createString());
        final Map<String, WitType> f64Fields = new LinkedHashMap<>();
        f64Fields.put("subject", WitValueMarshaller.TERM_TYPE);
        f64Fields.put("score", WitType.createFloat64());
        f64Fields.put("snippet", optionStringType);
        SEARCH_HIT_F64_TYPE = WitType.record("search-hit", f64Fields);

        final Map<String, WitType> f32Fields = new LinkedHashMap<>();
        f32Fields.put("subject", WitValueMarshaller.TERM_TYPE);
        f32Fields.put("score", WitType.createFloat32());
        f32Fields.put("snippet", optionStringType);
        SEARCH_HIT_F32_TYPE = WitType.record("search-hit-f32", f32Fields);
    }

    @Test
    public void unmarshalSearchHitsDecodesRecordListWithF64Score() {
        // Build a list<search-hit> with two entries where score is f64 (WIT contract):
        //   { subject: iri("http://ex/a"), score: 0.9, snippet: Some("hello") }
        //   { subject: iri("http://ex/b"), score: 0.5, snippet: None }
        final WitList hits = WitList.builder(SEARCH_HIT_F64_TYPE)
                .add(searchHitRecordF64("http://ex/a", 0.9d, Optional.of("hello")))
                .add(searchHitRecordF64("http://ex/b", 0.5d, Optional.empty()))
                .build();

        final List<WitValueMarshaller.SearchHit> decoded = marshaller.unmarshalSearchHits(hits);
        assertThat(decoded).hasSize(2);

        assertThat(decoded.get(0).subject).isInstanceOf(IRI.class);
        assertThat(decoded.get(0).subject.toString()).isEqualTo("http://ex/a");
        assertThat(decoded.get(0).score).isEqualTo(0.9d);
        assertThat(decoded.get(0).snippet).contains("hello");

        assertThat(decoded.get(1).subject.toString()).isEqualTo("http://ex/b");
        assertThat(decoded.get(1).score).isEqualTo(0.5d);
        assertThat(decoded.get(1).snippet).isEmpty();
    }

    @Test
    public void unmarshalSearchHitsAcceptsF32Score() {
        // Task memo names score: f32 even though the current WIT is f64.
        // The decoder accepts either; test a single-element list here.
        final WitList hits = WitList.builder(SEARCH_HIT_F32_TYPE)
                .add(searchHitRecordF32("http://ex/c", 0.25f, Optional.of("snip")))
                .build();
        final List<WitValueMarshaller.SearchHit> decoded = marshaller.unmarshalSearchHits(hits);
        assertThat(decoded).hasSize(1);
        assertThat(decoded.get(0).score).isEqualTo((double) 0.25f);
    }

    @Test
    public void unmarshalF32VectorDecodesFloatList() {
        final WitList vec = WitList.builder(WitType.createFloat32())
                .add(WitFloat32.of(0.1f))
                .add(WitFloat32.of(0.2f))
                .add(WitFloat32.of(-0.5f))
                .build();
        final List<Float> decoded = marshaller.unmarshalF32Vector(vec);
        assertThat(decoded).containsExactly(0.1f, 0.2f, -0.5f);
    }

    @Test
    public void unmarshalF32VectorAcceptsF64Elements() {
        // Guests that emit list<f64> instead of list<f32> should still decode.
        final WitList vec = WitList.builder(WitType.createFloat64())
                .add(WitFloat64.of(0.25d))
                .add(WitFloat64.of(0.5d))
                .build();
        final List<Float> decoded = marshaller.unmarshalF32Vector(vec);
        assertThat(decoded).containsExactly(0.25f, 0.5f);
    }

    @Test
    public void unmarshalSearchHitsRejectsNonListValue() {
        assertThatThrownBy(() -> marshaller.unmarshalSearchHits(WitString.of("not a list")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a list");
    }

    @Test
    public void unmarshalF32VectorRejectsNonListValue() {
        assertThatThrownBy(() -> marshaller.unmarshalF32Vector(WitString.of("not a list")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a list");
    }

    // ---- test helpers ---------------------------------------------------------

    /** Build a synthetic search-hit record with an f64 score field. */
    private static WitRecord searchHitRecordF64(
            final String subjectIri,
            final double score,
            final Optional<String> snippet) {
        return searchHitRecord(subjectIri, WitFloat64.of(score), snippet);
    }

    /** Build a synthetic search-hit record with an f32 score field. */
    private static WitRecord searchHitRecordF32(
            final String subjectIri,
            final float score,
            final Optional<String> snippet) {
        return searchHitRecord(subjectIri, WitFloat32.of(score), snippet);
    }

    private static WitRecord searchHitRecord(
            final String subjectIri,
            final WitValue scoreValue,
            final Optional<String> snippet) {
        final WitVariant subject = WitVariant.of(
                WitValueMarshaller.TERM_TYPE,
                "named-node",
                mustString(subjectIri));

        final WitType optionStringType = WitType.option(WitType.createString());
        final WitValue snippetValue = snippet.isPresent()
                ? (WitValue) WitOption.some(optionStringType, mustString(snippet.get()))
                : (WitValue) WitOption.none(optionStringType);

        return WitRecord.builder()
                .field("subject", subject)
                .field("score", scoreValue)
                .field("snippet", snippetValue)
                .build();
    }

    private static WitString mustString(final String s) {
        try {
            return WitString.of(s);
        } catch (ai.tegmentum.wasmtime4j.exception.ValidationException e) {
            throw new IllegalStateException(e);
        }
    }
}
