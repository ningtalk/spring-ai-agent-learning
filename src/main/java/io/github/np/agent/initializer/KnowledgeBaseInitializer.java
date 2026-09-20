package io.github.np.agent.initializer;

import io.github.np.agent.loader.KnowledgeBaseLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Order(1)  // 保证在应用就绪后执行
public class KnowledgeBaseInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseInitializer.class);

    private final KnowledgeBaseLoader loader;
    private final TokenTextSplitter splitter;
    private final VectorStore vectorStore;

    public KnowledgeBaseInitializer(KnowledgeBaseLoader loader,
                                    TokenTextSplitter splitter,
                                    VectorStore vectorStore) {
        this.loader = loader;
        this.splitter = splitter;
        this.vectorStore = vectorStore;
    }

    @Override
    public void run(String... args) {
        log.info("【知识库初始化】开始加载文档...");

        List<Document> documents = loader.loadMarkdownDocuments();
        log.info("【知识库初始化】加载原始文档数: {}", documents.size());

        if (documents.isEmpty()) {
            log.warn("【知识库初始化】未加载到任何文档，请检查 classpath:document/*.md 是否存在");
            return;
        }

        List<Document> chunks = splitter.apply(documents);
        log.info("【知识库初始化】分块后片段数: {}", chunks.size());

        vectorStore.add(chunks);
        log.info("【知识库初始化】写入向量库完成");
    }
}
