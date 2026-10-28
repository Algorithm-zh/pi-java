package com.piagent.infrastructure.tool.index;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SourceSymbolsTest {

    @Test
    void extractsJavaTypesConstructorsAndMethods() {
        List<SourceSymbol> symbols = SourceSymbols.extract("""
                package demo;

                public final class AttachmentService {
                    private final AttachmentStore store;

                    public AttachmentService(AttachmentStore store) {
                        this.store = store;
                    }

                    @Override
                    public Attachment upload(String name) {
                        return store.save(name);
                    }
                }
                """, "AttachmentService.java");
        assertThat(names(symbols))
                .contains("AttachmentService", "upload")
                .doesNotContain("store", "save");
        SourceSymbol upload = symbols.stream()
                .filter(symbol -> symbol.name().equals("upload"))
                .findFirst()
                .orElseThrow();
        assertThat(upload.line()).isEqualTo(11);
        assertThat(upload.text()).startsWith("public Attachment upload(String name)");
    }

    @Test
    void ignoresControlFlowStatements() {
        List<SourceSymbol> symbols = SourceSymbols.extract("""
                if (ready) {
                    return value;
                }
                for (int i = 0; i < 3; i++) {
                    run(i);
                }
                System.out.println("done");
                """, "Sample.java");

        assertThat(symbols).isEmpty();
    }

    @Test
    void extractsPythonDefinitions() {
        List<SourceSymbol> symbols = SourceSymbols.extract("""
                class Uploader:
                    async def upload(self, file):
                        return file
                """, "uploader.py");

        assertThat(names(symbols)).containsExactly("Uploader", "upload");
    }

    @Test
    void extractsJavaScriptFunctionsAndArrows() {
        List<SourceSymbol> symbols = SourceSymbols.extract("""
                export async function loadChat() {
                }
                const uploadAttachments = async (files) => {
                };
                """, "chat.js");

        assertThat(names(symbols)).containsExactly("loadChat", "uploadAttachments");
    }

    @Test
    void extractsGoRustAndSqlDeclarations() {
        List<SourceSymbol> symbols = SourceSymbols.extract("""
                func (s *Store) Save(name string) error {
                }
                pub async fn upload(file: File) {
                }
                CREATE TABLE attachments (id BIGINT);
                """, "schema.sql");

        assertThat(names(symbols)).containsExactly("Save", "upload", "attachments");
    }

    @Test
    void extractsMarkdownHeadings() {
        List<SourceSymbol> symbols = SourceSymbols.extract("""
                # 项目说明

                ## 快速开始

                Some body text.
                """, "README.md");

        assertThat(names(symbols)).containsExactly("项目说明", "快速开始");
    }

    @Test
    void ignoresHashCommentsOutsideMarkdown() {
        List<SourceSymbol> symbols = SourceSymbols.extract("""
                # attachment storage quota
                key: value
                """, "application.yml");

        assertThat(symbols).isEmpty();
    }

    @Test
    void limitsSymbolsPerFile() {
        StringBuilder content = new StringBuilder();
        for (int index = 0; index < 80; index++) {
            content.append("public void method").append(index).append("() {\n}\n");
        }

        assertThat(SourceSymbols.extract(content.toString(), "Methods.java")).hasSize(60);
    }

    @Test
    void returnsEmptyForBlankContent() {
        assertThat(SourceSymbols.extract("", "notes.txt")).isEmpty();
        assertThat(SourceSymbols.extract(null, "notes.txt")).isEmpty();
    }

    private List<String> names(List<SourceSymbol> symbols) {
        return symbols.stream().map(SourceSymbol::name).toList();
    }
}
