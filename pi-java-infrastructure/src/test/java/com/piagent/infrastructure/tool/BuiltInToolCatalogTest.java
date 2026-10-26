package com.piagent.infrastructure.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.application.plan.SessionPlanService;
import com.piagent.domain.governance.service.PathSandbox;
import com.piagent.domain.tool.model.ExecutionMode;
import com.piagent.domain.tool.model.ToolDescriptor;
import com.piagent.domain.tool.model.ToolRisk;
import java.util.List;
import org.junit.jupiter.api.Test;

class BuiltInToolCatalogTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void exposesReadAndWriteToolsInStableOrder() {
        List<ToolDescriptor> descriptors = BuiltInToolCatalog.descriptors();

        assertThat(descriptors).extracting(descriptor -> descriptor.name().value())
                .containsExactly("read", "ls", "grep", "glob", "todo", "write", "edit", "bash");
        assertThat(descriptors).allSatisfy(descriptor -> assertThat(descriptor.timeout())
                .isPositive());
    }

    @Test
    void readOnlyToolsAreLowRiskAndParallel() {
        assertThat(descriptors("read", "ls", "grep", "glob")).allSatisfy(descriptor -> {
            assertThat(descriptor.risk()).isEqualTo(ToolRisk.LOW);
            assertThat(descriptor.executionMode()).isEqualTo(ExecutionMode.PARALLEL);
        });
    }

    @Test
    void writeToolsAreHighRiskAndSequential() {
        assertThat(descriptors("write", "edit")).allSatisfy(descriptor -> {
            assertThat(descriptor.risk()).isEqualTo(ToolRisk.HIGH);
            assertThat(descriptor.executionMode()).isEqualTo(ExecutionMode.SEQUENTIAL);
        });
    }

    @Test
    void bashIsCriticalAndSequential() {
        ToolDescriptor bash = descriptor("bash");

        assertThat(bash.risk()).isEqualTo(ToolRisk.CRITICAL);
        assertThat(bash.executionMode()).isEqualTo(ExecutionMode.SEQUENTIAL);
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
        assertThat(required("write")).containsExactly("path", "content");
        assertThat(required("edit")).containsExactly("path", "oldText", "newText");
        assertThat(required("bash")).containsExactly("command");
        assertThat(required("todo")).containsExactly("todos");
    }

    @Test
    void everyDescriptorHasAHandler() {
        RealPathResolver resolver = new RealPathResolver(new PathSandbox());

        assertThat(BuiltInToolHandlers.create(resolver, mock(SessionPlanService.class)).keySet())
                .containsExactlyInAnyOrderElementsOf(BuiltInToolCatalog.descriptors().stream()
                        .map(ToolDescriptor::name)
                        .toList());
    }

    private List<ToolDescriptor> descriptors(String... names) {
        return BuiltInToolCatalog.descriptors().stream()
                .filter(descriptor -> List.of(names).contains(descriptor.name().value()))
                .toList();
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
