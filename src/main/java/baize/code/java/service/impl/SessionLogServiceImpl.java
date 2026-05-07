package baize.code.java.service.impl;

import baize.code.java.service.SessionLogService;
import baize.code.java.utils.KeyUtils;
import baize.code.java.utils.TypeConversion;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import baize.code.java.code.ResultCode;
import baize.code.java.common.Result;
import baize.code.java.entity.Session;
import baize.code.java.entity.SessionLog;
import baize.code.java.mapper.SessionLogMapper;
import baize.code.java.mapper.SessionMapper;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class SessionLogServiceImpl extends ServiceImpl<SessionLogMapper, SessionLog> implements SessionLogService {

    @Resource
    private final StringRedisTemplate stringRedisTemplate;
    private final SessionMapper sessionMapper;
    @Value("${memory.redis-length}")
    private Integer memoryLength;
    @Value("${memory.expiration-duration}")
    private Integer timeout;
    @Value("${memory.key}")
    private String memoryKey;


    @Override
    public Result<?> readCtMessage(Integer sessionId, Integer userId) {
        // 检查是否有该会话
        Session session = sessionMapper.selectOne(new LambdaQueryWrapper<>(Session.class)
                .eq(Session::getId, sessionId)
                .eq(Session::getUserId, userId)
        );
        if (session == null) {
            return Result.error(ResultCode.NOT_FOUND);
        }
        // 修改这个session的关于商户或者AI发送的消息
        lambdaUpdate().set(SessionLog::getReadStatus, SessionLog.ReadStatus.READ)
                .eq(SessionLog::getSessionId, sessionId)
                .eq(SessionLog::getType, SessionLog.Type.ASSISTANT)
                .or()
                .eq(SessionLog::getType, SessionLog.Type.COMMERCIAL_TENANT)
                .update();
        return Result.success(ResultCode.UPDATE_SUCCESS);
    }

    @Override
    public Result<?> readUserMessage(Integer sessionId, Integer ctId) {
        // 检查是否有该会话
        Session session = sessionMapper.selectOne(new LambdaQueryWrapper<>(Session.class)
                .eq(Session::getId, sessionId)
                .eq(Session::getCtId, ctId)
        );
        if (session == null) {
            return Result.error(ResultCode.NOT_FOUND);
        }
        // 修改这个session的关于商户或者AI发送的消息
        lambdaUpdate().set(SessionLog::getReadStatus, SessionLog.ReadStatus.READ)
                .eq(SessionLog::getSessionId, sessionId)
                .eq(SessionLog::getType, SessionLog.Type.USER)
                .update();
        return Result.success(ResultCode.UPDATE_SUCCESS);
    }

    @Override
    public Result<List<SessionLog>> getWindowMessage(Integer sessionId) {
        return Result.success(
                ResultCode.GET_SUCCESS,
                lambdaQuery().eq(SessionLog::getSessionId, sessionId)
                        .orderByAsc(SessionLog::getTimestamp)
                        .list()
        );
    }

    @Override
    public Result<Integer> userGetUnreadMessageCount(Integer sessionId) {
        return Result.success(
                ResultCode.GET_SUCCESS,
                lambdaQuery().eq(SessionLog::getSessionId, sessionId)
                        .eq(SessionLog::getType, SessionLog.Type.COMMERCIAL_TENANT)
                        .or()
                        .eq(SessionLog::getType, SessionLog.Type.ASSISTANT)
                        .count().intValue()
        );
    }

    @Override
    public Result<Integer> ctGetUnreadMessageCount(Integer sessionId) {
        return Result.success(
                ResultCode.GET_SUCCESS,
                lambdaQuery().eq(SessionLog::getSessionId, sessionId)
                        .eq(SessionLog::getType, SessionLog.Type.USER)
                        .count().intValue()
        );
    }

    @Override
    public List<SessionLog> getSessionLogById(Integer sessionId) {
        return lambdaQuery().eq(SessionLog::getSessionId,sessionId)
                .orderByDesc(SessionLog::getTimestamp)
                .page(new Page<>(1,memoryLength))
                .getRecords();
    }

    @Override
    public void addToRedis(Integer conversationId, List<SessionLog> sessionLogList) {
        if(sessionLogList==null || sessionLogList.isEmpty()){
            return;
        }
        String redisKey = KeyUtils.redisKeyUtils(memoryKey, conversationId);
        stringRedisTemplate.opsForList().rightPushAll(
                redisKey,
                sessionLogList.stream().map(JSONUtil::toJsonStr).toList()
        );
        // 重置key的过期时间
        stringRedisTemplate.expire(redisKey,timeout, TimeUnit.MINUTES);
        //检查redis中的长度，超过长度就删除
        if (Optional.ofNullable(stringRedisTemplate.opsForList().size(redisKey)).orElse(0L) > memoryLength){
            stringRedisTemplate.opsForList().trim(redisKey,-memoryLength,-1);
        }
    }

    @Override
    public void addSessionLog(String conversationId, List<Message> messages) {
        List<SessionLog> sessionLogList = messages.stream().map(message -> {
            SessionLog.Type sessionType = TypeConversion.messageToSessionType(message.getMessageType());
            return SessionLog.builder()
                    .sessionId(Integer.valueOf(conversationId))
                    .content(message.getText())
                    .type(sessionType)
                    .build();
        }).toList();
        saveBatch(sessionLogList);
        addToRedis(Integer.valueOf(conversationId),sessionLogList);
    }
}