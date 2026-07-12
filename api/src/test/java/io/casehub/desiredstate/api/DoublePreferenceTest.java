package io.casehub.desiredstate.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class DoublePreferenceTest {

    @Test
    void shouldStoreValue() {
        DoublePreference pref = new DoublePreference(0.75);
        assertThat(pref.value()).isEqualTo(0.75);
    }

    @Test
    void shouldParseFromString() {
        DoublePreference pref = DoublePreference.parse("0.42");
        assertThat(pref.value()).isEqualTo(0.42);
    }

    @Test
    void shouldRejectNullParse() {
        assertThatThrownBy(() -> DoublePreference.parse(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldHaveValueSemantics() {
        assertThat(new DoublePreference(0.5)).isEqualTo(new DoublePreference(0.5));
        assertThat(new DoublePreference(0.5).hashCode()).isEqualTo(new DoublePreference(0.5).hashCode());
    }
}
