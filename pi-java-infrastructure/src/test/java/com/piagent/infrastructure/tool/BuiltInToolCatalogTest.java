package com.piagent.infrastructure.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.domain.tool.model.ExecutionMode;
import com.piagent.domain.tool.model.ToolDescriptor;
import com.piagent.domain.tool.model.ToolRisk;
import java.util.List;
import org.junit.jupiter.api.Test;

class BuiltInToolCatalogTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void exposesFourReadOnlyTools() {
        List<ToolDescriptor> descriptors = BuiltInToolCatalog.descriptors();

        assertThat(descriptors).extracting(descriptor -> descriptor.name().value())
                .containsExactly("read", "ls", "grep", "glob");
        assertThat(descriptors).allSatisfy(descriptor -> {
            assertThat(descriptor.risk()).isEqualTo(ToolRisk.LOW);
            assertThat(descriptor.executionMode()).isEqualTo(ExecutionMode.PARALLEL);
            assertThat(descriptor.timeout()).isPositive();
        });
    }

    @Test
    void schemasAreValidJsonObjects() throws Exception {
        for (ToolDescriptor descriptor : BuiltInToolCatalog.descriptors()) {
            JsonNode schema = mapper.readTree(descriptor.inputSchemaJson());
            assertThat(schema.path("type").asText()).isEqualTo("object");
            assertThat(schema.path("properties").isObject()).isTrue();
            assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        }
    }

    @Test
    void requiredArgumentsMatchHandlers() throws Exception {
        assertThat(required("read")).containsExactly("path");
        assertThat(required("grep")).containsExactly("pattern");
        assertThat(required("glob")).containsExactly("pattern");
        assertThat(required("ls")).isEmpty();
    }

    private List<String> required(String tool) throws Exception {
        JsonNode schema = mapper.readTree(descriptor(tool).inputSchemaJson());
        JsonNode required = schema.path("required");
        if (required.isMissingNode()) {
            return List.of();
        }
        return mapper.convertValue(required, mapper.getTypeFactory()
                .constructCollectionType(List.class, String.class));
    }

    private ToolDescriptor descriptor(String name) {
        return BuiltInToolCatalog.descriptors().stream()
                .filter(item -> item.name().value().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
