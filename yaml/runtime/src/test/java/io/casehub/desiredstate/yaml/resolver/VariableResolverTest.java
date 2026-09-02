package io.casehub.desiredstate.yaml.resolver;

import io.casehub.yaml.core.resolver.UnresolvedVariableException;
import io.casehub.yaml.core.resolver.VariableResolver;
import io.casehub.yaml.core.resolver.VariableSource;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VariableResolverTest {

    private static VariableResolver resolver(Map<String, String> vars) {
        return new VariableResolver(
                Map.of("var", (VariableSource) vars::get),
                Set.of("match", "fault"));
    }

    @Test
    void resolvesFromInlineMap() {
        var resolver = resolver(Map.of("batch", "500"));
        assertThat(resolver.resolveString("${var.batch}", "test-node"))
                .isEqualTo("500");
    }

    @Test
    void passesNonVariableStringsThrough() {
        var resolver = resolver(Map.of());
        assertThat(resolver.resolve("plain-string"))
                .isEqualTo("plain-string");
    }

    @Test
    void resolvesNestedMapValues() {
        var resolver = resolver(Map.of("uri", "s3://data"));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("destination", "${var.uri}");
        input.put("count", 42);

        Map<String, Object> resolved = resolver.resolveMap(input, "node");

        assertThat(resolved).containsEntry("destination", "s3://data");
        assertThat(resolved).containsEntry("count", 42);
    }

    @Test
    void resolvesListValues() {
        var resolver = resolver(Map.of("field", "email"));
        List<Object> resolved = resolver.resolveList(
                List.of("name", "${var.field}"), "node");

        assertThat(resolved).containsExactly("name", "email");
    }

    @Test
    void throwsOnUnresolvedVariable() {
        var resolver = resolver(Map.of("batch_size", "100"));

        assertThatThrownBy(() -> resolver.resolveString("${bacth_size}", "csv-ingest"))
                .isInstanceOf(UnresolvedVariableException.class)
                .hasMessageContaining("${var.bacth_size}");

        assertThatThrownBy(() -> resolver.resolveString("${var.bacth_size}", "csv-ingest"))
                .isInstanceOf(UnresolvedVariableException.class)
                .hasMessageContaining("bacth_size")
                .hasMessageContaining("csv-ingest");
    }

    @Test
    void embeddedVariableInLargerString() {
        var resolver = resolver(Map.of("bucket", "prod"));
        assertThat(resolver.resolveString("s3://${var.bucket}/data", "node"))
                .isEqualTo("s3://prod/data");
    }

    @Test
    void nonStringValuesPassThrough() {
        var resolver = resolver(Map.of());
        assertThat(resolver.resolve(42)).isEqualTo(42);
        assertThat(resolver.resolve(true)).isEqualTo(true);
        assertThat(resolver.resolve(3.14)).isEqualTo(3.14);
    }

    @Test
    void multipleVariablesInOneString() {
        var resolver = resolver(Map.of("proto", "s3", "bucket", "data"));
        assertThat(resolver.resolveString("${var.proto}://${var.bucket}/path", "node"))
                .isEqualTo("s3://data/path");
    }

    @Test
    void resolveString_withVarPrefix_resolvesFromInlineVariables() {
        var resolver = resolver(Map.of("source_uri", "s3://data/test.csv"));
        assertThat(resolver.resolveString("${var.source_uri}", "test-node"))
                .isEqualTo("s3://data/test.csv");
    }

    @Test
    void resolveString_bareName_throwsWithGuidance() {
        var resolver = resolver(Map.of("source_uri", "s3://data/test.csv"));
        assertThatThrownBy(() -> resolver.resolveString("${source_uri}", "test-node"))
                .isInstanceOf(UnresolvedVariableException.class)
                .hasMessageContaining("${var.source_uri}");
    }

    @Test
    void resolveString_matchPrefix_deferredLeftAsIs() {
        var resolver = resolver(Map.of());
        assertThat(resolver.resolveString("${match.sink.id}", "test-node"))
                .isEqualTo("${match.sink.id}");
    }

    @Test
    void resolveString_faultPrefix_deferredLeftAsIs() {
        var resolver = resolver(Map.of());
        assertThat(resolver.resolveString("${fault.nodeId}", "test-node"))
                .isEqualTo("${fault.nodeId}");
    }

    @Test
    void resolveString_eachPrefix_throwsWithGuidance() {
        var resolver = resolver(Map.of());
        assertThatThrownBy(() -> resolver.resolveString("${each.region}", "test-node"))
                .isInstanceOf(UnresolvedVariableException.class)
                .hasMessageContaining("forEach");
    }

    // --- Deferred prefixes: resolves ${var.*}, passes through ${match.*} ---

    @Test
    void resolveString_resolvesVarPrefix_passesMatchThrough() {
        var resolver = resolver(Map.of("region", "us-east"));
        String result = resolver.resolveString(
                "monitor-${match.sink.id}-${var.region}", "test-rule");
        assertThat(result).isEqualTo("monitor-${match.sink.id}-us-east");
    }

    @Test
    void resolveString_noVarReferences_passesMatchThrough() {
        var resolver = resolver(Map.of());
        String result = resolver.resolveString(
                "${match.sink.id}", "test-rule");
        assertThat(result).isEqualTo("${match.sink.id}");
    }

    @Test
    void resolveString_onlyVar_resolvesCompletely() {
        var resolver = resolver(Map.of("name", "test"));
        String result = resolver.resolveString("${var.name}", "test-rule");
        assertThat(result).isEqualTo("test");
    }

    // --- Each context: resolves ${each.*} during forEach expansion ---

    @Test
    void withEachContext_resolvesEachPrefix() {
        var resolver = resolver(Map.of("batch", "1000"));
        var eachResolver = resolver.withEachContext(Map.of("region", "us-east"));
        String result = eachResolver.resolveString(
                "s3://${each.region}/${var.batch}", "test-node");
        assertThat(result).isEqualTo("s3://us-east/1000");
    }

    @Test
    void withEachContext_unknownEachVar_throws() {
        var resolver = resolver(Map.of());
        var eachResolver = resolver.withEachContext(Map.of("region", "us-east"));
        assertThatThrownBy(() -> eachResolver.resolveString(
                "${each.zone}", "test-node"))
                .isInstanceOf(UnresolvedVariableException.class)
                .hasMessageContaining("zone");
    }

    @Test
    void withoutEachContext_eachPrefix_throwsDeferred() {
        var resolver = resolver(Map.of());
        assertThatThrownBy(() -> resolver.resolveString(
                "${each.region}", "test-node"))
                .isInstanceOf(UnresolvedVariableException.class)
                .hasMessageContaining("forEach");
    }

    // --- Chained scope: module parameters override variables ---

    @Test
    void withChainedScope_parametersOverrideVariables() {
        var resolver = resolver(Map.of("email", "global@example.com"));
        var moduleResolver = resolver.withChainedScope("var",
                Map.of("email", "module@example.com")::get);
        assertThat(moduleResolver.resolveString("${var.email}", "test"))
                .isEqualTo("module@example.com");
    }

    @Test
    void withChainedScope_fallthroughToVariables() {
        var resolver = resolver(Map.of("batch_size", "1000"));
        var moduleResolver = resolver.withChainedScope("var",
                Map.of("watched_id", "sink-1")::get);
        assertThat(moduleResolver.resolveString("${var.batch_size}", "test"))
                .isEqualTo("1000");
    }

    @Test
    void resolveMap_resolvesVarAndDefersMatch() {
        var resolver = resolver(Map.of("region", "us-east"));
        Map<String, Object> input = Map.of(
                "target", "${match.sink.id}",
                "region", "${var.region}");
        Map<String, Object> result = resolver.resolveMap(input, "test-rule");
        assertThat(result.get("target")).isEqualTo("${match.sink.id}");
        assertThat(result.get("region")).isEqualTo("us-east");
    }

}
