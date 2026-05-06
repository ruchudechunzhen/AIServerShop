package baize.code.java.ai.service.impl;

import baize.code.java.ai.service.AIService;
import baize.code.java.entity.Session;
import baize.code.java.mapper.SessionMapper;
import baize.code.java.utils.KeyUtils;
import baize.code.java.websocket.endpoint.UserServiceEndpoint;
import baize.code.java.websocket.message.ChatMessage;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.val;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class AIServiceImpl implements AIService {


    @Autowired
    private ChatClient chatClient;

    @Autowired
    private SessionMapper sessionMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Value("${session.key}")
    private String sessionKey;
    @Value("${session.expiration-duration}")
    private long sessionExpireTime;


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
    public void chat(Session chatSession, ChatMessage message, UserServiceEndpoint userServiceEndpoint) {

    }
}
