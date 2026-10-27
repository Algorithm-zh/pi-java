package com.piagent.trigger.http.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.application.attachment.AttachmentContent;
import com.piagent.application.attachment.AttachmentMetadata;
import com.piagent.application.attachment.AttachmentReclaimResult;
import com.piagent.application.attachment.AttachmentStats;
import com.piagent.application.attachment.service.AttachmentApplicationService;
import com.piagent.application.attachment.service.AttachmentMaintenanceService;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.trigger.http.ApiExceptionHandler;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 附件控制器测试：上传响应、原始字节下载与领域错误映射。
 */
class AttachmentControllerTest {

    private static final byte[] PNG = {
        (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0
    };
    private static final AttachmentMetadata METADATA =
            new AttachmentMetadata("att-1", "shot.png", "image/png", PNG.length);

    private final ObjectMapper json = new ObjectMapper();
    private AttachmentApplicationService attachments;
    private AttachmentMaintenanceService maintenance;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        attachments = mock(AttachmentApplicationService.class);
        maintenance = mock(AttachmentMaintenanceService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AttachmentController(attachments, maintenance))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void uploadsMultipartImage() throws Exception {
        MockMultipartFile file =
                new MockMultipartFile("file", "shot.png", "image/png", PNG);
        when(attachments.upload("shot.png", PNG)).thenReturn(METADATA);

        MvcResult result = mvc.perform(multipart("/api/v1/attachments").file(file))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode body = json.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.path("id").asText()).isEqualTo("att-1");
        assertThat(body.path("name").asText()).isEqualTo("shot.png");
        assertThat(body.path("mediaType").asText()).isEqualTo("image/png");
        assertThat(body.path("size").asLong()).isEqualTo(PNG.length);
        assertThat(body.path("url").asText()).isEqualTo("/api/v1/attachments/att-1");
    }

    @Test
    void returnsInvalidAttachmentAsBadRequest() throws Exception {
        MockMultipartFile file =
                new MockMultipartFile("file", "notes.txt", "text/plain", "not an image".getBytes());
        when(attachments.upload(any(), any())).thenThrow(new DomainException(
                DomainErrorCode.ATTACHMENT_INVALID, "不支持的图片格式"));

        mvc.perform(multipart("/api/v1/attachments").file(file))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsQuotaExceededAsPayloadTooLarge() throws Exception {
        MockMultipartFile file =
                new MockMultipartFile("file", "shot.png", "image/png", PNG);
        when(attachments.upload(any(), any())).thenThrow(new DomainException(
                DomainErrorCode.ATTACHMENT_QUOTA_EXCEEDED, "附件存储已满"));

        mvc.perform(multipart("/api/v1/attachments").file(file))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void reportsStorageStatsAndReclaimsOrphans() throws Exception {
        when(attachments.stats()).thenReturn(new AttachmentStats(2, 4096, 8192));
        when(maintenance.reclaim()).thenReturn(new AttachmentReclaimResult(3, 1, 1024));

        MvcResult stats = mvc.perform(get("/api/v1/attachments/stats"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode statsBody = json.readTree(
                stats.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(statsBody.path("count").asInt()).isEqualTo(2);
        assertThat(statsBody.path("bytes").asLong()).isEqualTo(4096);
        assertThat(statsBody.path("quotaBytes").asLong()).isEqualTo(8192);

        MvcResult gc = mvc.perform(post("/api/v1/attachments/gc"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode gcBody = json.readTree(
                gc.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(gcBody.path("scanned").asInt()).isEqualTo(3);
        assertThat(gcBody.path("deleted").asInt()).isEqualTo(1);
        assertThat(gcBody.path("freedBytes").asLong()).isEqualTo(1024);
    }

    @Test
    void downloadsOriginalBytesWithCacheHeaders() throws Exception {
        when(attachments.load("att-1")).thenReturn(new AttachmentContent(METADATA, PNG));

        MvcResult result = mvc.perform(get("/api/v1/attachments/{id}", "att-1"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andReturn();

        assertThat(result.getResponse().getContentAsByteArray()).containsExactly(PNG);
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("private, max-age=86400");
        assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .contains("inline").contains("shot.png");
    }

    @Test
    void missingAttachmentReturnsNotFound() throws Exception {
        when(attachments.load("missing")).thenThrow(new DomainException(
                DomainErrorCode.ATTACHMENT_NOT_FOUND, "附件不存在"));

        mvc.perform(get("/api/v1/attachments/{id}", "missing"))
                .andExpect(status().isNotFound());
    }
}
