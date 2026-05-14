package baize.code.java.ai.adviser;

import baize.code.java.code.DocumentCode;
import com.alibaba.cloud.ai.advisor.RetrievalRerankAdvisor;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.rerank.DashScopeRerankModel;
import jakarta.annotation.Resource;
import lombok.val;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.TranslationQueryTransformer;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import static baize.code.java.code.DocumentCode.GOODS_ID;

@Component
public class RAGAdvisor {

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private DashScopeChatModel dashScopeChatModel;

    @Autowired
    private DashScopeRerankModel rerankModel;

    @Value("${retrieval.threshold}")
    private Double retrievalThreshold;

    @Value("${retrieval.number}")
    private Integer retrievalNum;

    @Value("classpath:template/relevant-information-cannot-be-retrieved.st")
    private org.springframework.core.io.Resource relevantInformationCannotBeRetrievedResource;

    private static final PromptTemplate DEFAULT_PROMPT_TEMPLATE = new PromptTemplate("""
			{query}

			Context information is below, surrounded by ---------------------
			---------------------
			{question_answer_context}
			---------------------
			Given the context and provided history information and not prior knowledge,
			reply to the user comment. If the answer is not in the context, inform
			the user that you can't answer the question.
			""");


    public RetrievalAugmentationAdvisor createAugmentationAdvisor(String goodsId) {

        // 构造Milvus的元数据过滤器
        Filter.Expression filter = new Filter.Expression(
                Filter.ExpressionType.EQ,
                new Filter.Key(GOODS_ID.toString()),
                new Filter.Value(goodsId)
        );

        // 构建VectorStoreDocumentRetriever
        VectorStoreDocumentRetriever  documentRetriever  = VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .similarityThreshold(retrievalThreshold) // 设置关联度阈值
                .topK(retrievalNum)  // 设置返回条数
                .filterExpression(filter) // 设置过滤器
                .build();

        // 配置查询增强器
        ContextualQueryAugmenter contextualQueryAugmenter  = ContextualQueryAugmenter.builder()
                .allowEmptyContext(false) //如果向量数据库为空 则运行将控制传递给大模型
                .emptyContextPromptTemplate(
                        PromptTemplate.builder()
                                .resource(relevantInformationCannotBeRetrievedResource)
                                .build()
                )
                .build();

        // 配置查询重写
        RewriteQueryTransformer rewriteQueryTransformer = RewriteQueryTransformer.builder()
                .chatClientBuilder(ChatClient.builder(dashScopeChatModel))
                .targetSearchSystem("你是一个词汇清理的专家，主要工作是将用户的模糊问题提取出专业的词汇，以提高向量检索的精度，注意不要有任何多余的解释 比如：推荐一款适合游戏的键盘 重写为 机械键盘 青轴 104键 背光有线 电竞游戏专用 全键无冲 黑色")
                .build();
        // 配置目标语言
        TranslationQueryTransformer translationQueryTransformer = TranslationQueryTransformer.builder()
                .chatClientBuilder(ChatClient.builder(dashScopeChatModel))
                .targetLanguage("chinese") //目标语言
                .build();
        // 构建advisor
        return RetrievalAugmentationAdvisor.builder()
                .order(1)
                .documentRetriever(documentRetriever)
                .queryAugmenter(contextualQueryAugmenter)
                .queryTransformers(translationQueryTransformer,rewriteQueryTransformer)
                .build();
    }


    // 构建重排序advisor
    public RetrievalRerankAdvisor createRerankAdvisor(){

        RetrievalRerankAdvisor retrievalRerankAdvisor = new RetrievalRerankAdvisor(vectorStore, rerankModel,
                SearchRequest.builder()
                        .topK(retrievalNum)
                        .similarityThreshold(retrievalThreshold)
                        .build(),
               DEFAULT_PROMPT_TEMPLATE,  // 用默认模板
                0.1,
                Ordered.LOWEST_PRECEDENCE);
        return retrievalRerankAdvisor;
    }
}
