package baize.code.java.ai.service.impl;

import baize.code.java.ai.adviser.CustomizationMemoryAdviser;
import baize.code.java.ai.adviser.RAGAdvisor;
import baize.code.java.ai.service.AIService;
import baize.code.java.entity.Role;
import baize.code.java.entity.Session;
import baize.code.java.entity.SessionLog;
import baize.code.java.mapper.SessionMapper;
import baize.code.java.service.RoleService;
import baize.code.java.utils.KeyUtils;
import baize.code.java.websocket.endpoint.UserServiceEndpoint;
import baize.code.java.websocket.message.ChatMessage;
import cn.hutool.json.JSONUtil;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import jakarta.websocket.EncodeException;
import lombok.RequiredArgsConstructor;
import lombok.val;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.preretrieval.query.transformation.TranslationQueryTransformer;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.template.st.StTemplateRenderer;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import static baize.code.java.code.DocumentCode.GOODS_ID;

@Service
@RequiredArgsConstructor
public class AIServiceImpl implements AIService {


    @Autowired
    private ChatClient chatClient;

    @Autowired
    private SessionMapper sessionMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RoleService roleService;

    @Autowired
    private RAGAdvisor ragAdvisor;

    @Autowired
    private CustomizationMemoryAdviser customizationMemoryAdviser;

    @Value("classpath:template/customer-service-role.st")
    private Resource customerServiceRoleResource;

    @Value("${session.key}")
    private String sessionKey;
    @Value("${session.expiration-duration}")
    private long sessionExpireTime;

    @jakarta.annotation.Resource
    private VectorStore vectorStore;

    @Value("classpath:template/relevant-information-cannot-be-retrieved.st")
    private Resource relevantInformationCannotBeRetrievedResource;

    @Autowired
    private DashScopeChatModel dashScopeChatModel;

    @Value("${retrieval.threshold}")
    private Double retrievalThreshold;

    @Value("${retrieval.number}")
    private Integer retrievalNum;

    @Value("classpath:template/convert-to-manual-judgment-prompts.st")
    private Resource contextResource;//转人工的提示词资源

    // 判断是否需要转人工
    @Override
    public void turnToManualJudgement(Session chatSession, ChatMessage message) {

        // 添加人工判断逻辑
        String  result = chatClient.prompt()
                .system(contextResource)
                .user(message.getMessage())
                .call()
                .content();
        // 将ai的结果转为boolean结果
        boolean needTransfer = false;
        if (result != null){
            needTransfer = Boolean.parseBoolean(result);
        }
        // ai判定需要转人工
        if (needTransfer){
            // 转人工
            chatSession.setConversationStatus(Session.ConversationStatus.HUMAN);
            // 更新数据库
            sessionMapper.updateById(chatSession);
            // 将状态更新到redis
            redisTemplate.opsForValue().set(KeyUtils.redisKeyUtils(sessionKey,chatSession.getId()), JSONUtil.toJsonStr(chatSession),sessionExpireTime, TimeUnit.MINUTES);
        }

    }

    @Override
    public void chat(Session chatSession, ChatMessage message, UserServiceEndpoint userServiceEndpoint) throws IllegalAccessException, EncodeException, IOException {

        // 查询商家设置的ai
        Role role = roleService.getRoleByCtId(chatSession.getCtId());
        // 对提示词模板进行关键字的插入
        PromptTemplate promptTemplate = PromptTemplate.builder()
                .renderer(StTemplateRenderer.builder()
                        .startDelimiterToken('<')
                        .endDelimiterToken('>')
                        .build()).resource(customerServiceRoleResource)
                .build();
        HashMap<String, Object> pos = new HashMap<>();
        //通过反射将role将需要填入的字段进行填入
        Class<? extends Role> clazz = role.getClass();
        for (Field field :clazz.getDeclaredFields()){
            if(field.getType() == String.class){
                // 如果是字符串就进行取值，替换占位符
                field.setAccessible(true);
                pos.put(field.getName(),field.get(role));
            }
        }

        Flux<ChatResponse> chatResponseFlux = chatClient.prompt().system(
                        // 替换
                        promptTemplate.render(pos))
                .user(message.getMessage())
                .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, chatSession.getId()))
//                .advisors(customizationMemoryAdviser)
                //.advisors(customizationMemoryAdviser,ragAdvisor.createRAGAdvisor(chatSession.getGoodsId().toString()))
                .advisors(customizationMemoryAdviser,
                        ragAdvisor.createAugmentationAdvisor(chatSession.getGoodsId().toString()),
                        ragAdvisor.createRerankAdvisor())
                .stream().chatResponse();

        chatResponseFlux.toIterable().forEach(chatResponseItem -> {
            //System.out.println(chatResponse1.getResult().getOutput().getText());
            //将AI回复的消息发送给客户
            try {
                userServiceEndpoint.sendMessage(ChatMessage.builder()
                        .sessionId(chatSession.getId())
                        .type(SessionLog.Type.ASSISTANT)
                        .message(chatResponseItem.getResult().getOutput().getText())
                        .build());
            } catch (EncodeException e) {
                throw new RuntimeException(e);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        userServiceEndpoint.sendMessage(ChatMessage.builder().state(ChatMessage.State.END).build());
    }
}
