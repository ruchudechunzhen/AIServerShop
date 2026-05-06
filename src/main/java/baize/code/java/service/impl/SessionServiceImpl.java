package baize.code.java.service.impl;

import baize.code.java.entity.Role;
import baize.code.java.service.RoleService;
import baize.code.java.utils.KeyUtils;
import baize.code.java.utils.SessionFind;
import baize.code.java.websocket.endpoint.UserServiceEndpoint;
import baize.code.java.websocket.message.ChatMessage;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import baize.code.java.code.ResultCode;
import baize.code.java.common.Result;
import baize.code.java.entity.Session;
import baize.code.java.mapper.SessionMapper;
import baize.code.java.service.SessionService;
import jakarta.annotation.Resource;
import jakarta.websocket.EncodeException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class SessionServiceImpl extends ServiceImpl<SessionMapper, Session> implements SessionService {
    @Value("${session.key}")
    private String key;
    @Value("${session.expiration-duration}")
    private Integer timeout;

    @Resource
    private SessionFind sessionFind;

    @Resource
    private SessionMapper sessionMapper;
    
    @Resource
    private RoleService roleService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Override
    public Result<List<Session>> userGetLastSessionList(Integer userId) {
        return Result.success(
                ResultCode.GET_SUCCESS,
                lambdaQuery()
                        .eq(Session::getUserId, userId)
                        .orderByDesc(Session::getTimestamp)
                        .list()
        );
    }

    @Override
    public Result<List<Session>> ctGetLastSessionList(Integer ctId) {
        return Result.success(
                ResultCode.GET_SUCCESS,
                lambdaQuery()
                        .eq(Session::getCtId, ctId)
                        .orderByDesc(Session::getTimestamp)
                        .list()
        );
    }

    @Override
    public Session find(ChatMessage message, Integer userId, UserServiceEndpoint userServiceEndpoint) throws EncodeException, IOException {
        //查询数据库中是否有会话信息
        if(!ObjectUtil.isEmpty(message.getSessionId())){
            return sessionFind.getSessionById(message.getSessionId());
        }
        // 第一次建立会话，手动创建一个会话
        if(ObjectUtil.isEmpty(message.getCtId()) || ObjectUtil.isEmpty(message.getGoodsId()) ){
            throw new RuntimeException("参数错误");
        }
        Session session = Session.builder()
                .userId(userId)
                .ctId(message.getCtId())
                .goodsId(message.getGoodsId())
                .build();
        // 查询商户是否设置了机器人
        Role role = roleService.getRoleByCtId(message.getCtId());
        // 如果没有设置机器人，直接走人工客服
        if(role == null){
            session.setConversationStatus(Session.ConversationStatus.HUMAN);
        }else {
            session.setConversationStatus(Session.ConversationStatus.AI);
        }
        // 保存数据库
        save(session);

        //保存redis
        stringRedisTemplate.opsForValue().set(KeyUtils.redisKeyUtils(key,session.getId()), JSONUtil.toJsonStr(session),timeout, TimeUnit.MINUTES);
        //将部分信息返回给前端
        userServiceEndpoint.sendMessage(ChatMessage.builder()
                        .sessionId(session.getId())
                        .state(ChatMessage.State.SURE)
                        .build());

        message.setSessionId(session.getId());
        return session;
    }
}