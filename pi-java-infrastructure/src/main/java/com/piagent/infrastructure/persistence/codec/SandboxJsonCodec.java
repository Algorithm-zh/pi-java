package com.piagent.infrastructure.persistence.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.piagent.domain.session.model.SandboxSnapshot;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话沙箱快照 JSON 编解码。
 *
 * <p>只保存可写根与拒绝规则；真实路径解析与符号链接检查属于工具执行阶段，
 * 不进入会话快照。
 */
public final class SandboxJsonCodec {

    private final ObjectMapper mapper;

    /**
     * 创建编解码器。
     *
     * @param mapper Jackson 映射器
     */
    public SandboxJsonCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 编码沙箱快照。
     *
     * @param snapshot 快照
     * @return JSON
     */
    public String encode(SandboxSnapshot snapshot) {
        ObjectNode node = mapper.createObjectNode();
        ArrayNode roots = node.putArray("writableRoots");
        snapshot.writableRoots().forEach(root -> roots.add(root.toString()));
        ArrayNode globs = node.putArray("denyGlobs");
        snapshot.denyGlobs().forEach(globs::add);
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception error) {
            throw new IllegalStateException("沙箱快照序列化失败", error);
        }
    }

    /**
     * 解码沙箱快照。
     *
     * @param json JSON
     * @return 快照
     */
    public SandboxSnapshot decode(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            List<Path> roots = new ArrayList<>();
            for (JsonNode root : node.path("writableRoots")) {
                roots.add(Path.of(root.asText()));
            }
            List<String> globs = new ArrayList<>();
            for (JsonNode glob : node.path("denyGlobs")) {
                globs.add(glob.asText());
            }
            return new SandboxSnapshot(roots, globs);
        } catch (DomainException error) {
            throw error;
        } catch (Exception error) {
            throw new DomainException(DomainErrorCode.INVALID_ARGUMENT, "sandbox_json 解析失败", error);
        }
    }
}
