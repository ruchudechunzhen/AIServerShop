package baize.code.java.utils;

import baize.code.java.entity.Session;
import baize.code.java.mapper.SessionMapper;
import baize.code.java.websocket.endpoint.CommercialTenantEndpoint;
import baize.code.java.websocket.endpoint.UserServiceEndpoint;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class SessionFind {
    @Value("${session.key}")
    private String key;
    @Value("${session.expiration-duration}")
    private Integer timeout;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private SessionMapper sessionMapper;

    public Session getSessionById (Integer sessionId){
        // 先查询redis
        String json = stringRedisTemplate.opsForValue().getAndExpire(KeyUtils.redisKeyUtils(key, sessionId.toString()), timeout, TimeUnit.MINUTES);
        Session session = null;
        if (json != null){
             session = JSONUtil.toBean(json, Session.class);
             return session;
        }
        // redis没有查询数据库
        session = sessionMapper.selectById(sessionId);
        if (session == null){
            throw new RuntimeException("未查询到执行会话"+sessionId);
        }
        // 将查询信息存储到redis中
        stringRedisTemplate.opsForValue().set(KeyUtils.redisKeyUtils(key,sessionId.toString()),JSONUtil.toJsonStr(session),timeout,TimeUnit.MINUTES );
        return session;
    }

    public UserServiceEndpoint findUserEndPoint(Integer sessionId) {
        Session session = getSessionById(sessionId); //这里要先找Session
        // 通过session中的userId查找对应的用户的endPoint
        return UserServiceEndpoint.findEndPoint(session.getUserId());
    }

    public CommercialTenantEndpoint findCommercialTenantEndPoint(Integer sessionId) {
        Session session = getSessionById(sessionId);
        // 通过session中的ctId查找对应的商户endPoint
        return CommercialTenantEndpoint.findEndPoint(session.getCtId());
    }
}
