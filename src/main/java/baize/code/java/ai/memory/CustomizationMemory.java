package baize.code.java.ai.memory;

import baize.code.java.entity.SessionLog;
import baize.code.java.executor.GlobalTreadPool;
import baize.code.java.service.SessionLogService;
import baize.code.java.utils.KeyUtils;
import baize.code.java.utils.TypeConversion;
import cn.hutool.json.JSONUtil;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class CustomizationMemory implements ChatMemory {


    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private SessionLogService sessionLogService;

    @Value("${memory.key}")
    private String memoryKey;
    @Value("${memory.expiration-duration}")
    private Integer timeout;
    @Value("${memory.redis-length}")
    private Integer memoryLength;


    @Override
    public void add(String conversationId, List<Message> messages) {
        GlobalTreadPool.executor.execute(()->{
            sessionLogService.addSessionLog(conversationId,messages);
        });
    }

    @Override
    public List<Message> get(String conversationId) {
        // 从redis中获取对话记忆
        List<String> range = stringRedisTemplate.opsForList().range(KeyUtils.redisKeyUtils(memoryKey, conversationId), -memoryLength, -1);
        List<Message> messages = null;
        // redis中又记录直接返回
        if(range != null && !range.isEmpty()){
            messages = new ArrayList<>(range.size());
            for (String item :range){
                SessionLog sessionLog = JSONUtil.toBean(item, SessionLog.class);
                Message message = TypeConversion.sessionToMessage(sessionLog.getType(), sessionLog.getContent());
                messages.add(message);
            }
        }
        // redis中没有，查询数据库
        List<SessionLog> sessionLogList = sessionLogService.getSessionLogById(Integer.valueOf(conversationId));
        // 放入redis中，线程池处理
        GlobalTreadPool.executor.execute(() ->{
            sessionLogService.addToRedis(Integer.valueOf(conversationId),sessionLogList);
        });
        messages = new ArrayList<>(sessionLogList.size());
        for (SessionLog sessionLog:sessionLogList){
            Message message = TypeConversion.sessionToMessage(sessionLog.getType(), sessionLog.getContent());
            messages.add(message);
        }
        return messages;
    }

    @Override
    public void clear(String conversationId) {

    }
}
