package com.salesforce.bazel.sdk.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.idea.blaze.base.model.primitives.Label;

public class BazelLabelTest {

    @Test
    void isConcrete_positive_test() {
        assertTrue(
            new BazelLabel("//src/main/java/com/google/devtools/build/lib/runtime/mobileinstall:mobileinstall")
                    .isConcrete());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "//src/main/java/com/google/devtools/build/lib/runtime/mobileinstall:mobileinstall",
            "@external//foo:bar" })
    void toPrimitive_and_back(String label) {
        var primitiveLabel = Label.create(label);
        var bazelLabel = new BazelLabel(primitiveLabel);

        // sanity check the test inouts
        assertEquals(label, primitiveLabel.toString());
        assertEquals(label, bazelLabel.toString());

        // expect toPrimitive to round-trip to produce the same label
        var recreatedLabel = new BazelLabel(bazelLabel.toPrimitive());
        assertEquals(bazelLabel, recreatedLabel);
        assertEquals(label, recreatedLabel.toString());
    }
}
