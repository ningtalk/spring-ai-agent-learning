package io.github.np.agent.loader;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Component
public class KnowledgeBaseLoader {

    private final ResourcePatternResolver resourcePatternResolver;

    public KnowledgeBaseLoader(ResourcePatternResolver resourcePatternResolver) {
        this.resourcePatternResolver = resourcePatternResolver;
    }

    public List<Document> loadMarkdownDocuments() {
        List<Document> allDocuments = new ArrayList<>();
        try {
            // 加载 document 目录下的所有 .md 文件
            Resource[] resources = resourcePatternResolver.getResources("classpath:document/*.md");
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                // 创建 Markdown 读取器配置
                MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                        .withHorizontalRuleCreateDocument(true) // 水平分割线创建新文档
                        .withIncludeCodeBlock(false)           // 不将代码块作为独立文档
                        .withIncludeBlockquote(false)          // 不将引用块作为独立文档
                        .withAdditionalMetadata("filename", filename) // 添加文件名作为元数据
                        .build();
                // 读取 Markdown 文件
                MarkdownDocumentReader reader = new MarkdownDocumentReader(resource, config);
                allDocuments.addAll(reader.get());
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load markdown documents", e);
        }
        return allDocuments;
    }
}
