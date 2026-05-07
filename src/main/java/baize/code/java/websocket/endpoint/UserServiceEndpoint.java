package baize.code.java.websocket.endpoint;

import baize.code.java.ai.service.AIService;
import baize.code.java.config.ChatMessageCoder;
import baize.code.java.mapper.SessionLogMapper;
import baize.code.java.service.SessionService;
import baize.code.java.utils.SessionFind;
import baize.code.java.websocket.message.ChatMessage;
import baize.code.java.entity.SessionLog;
import jakarta.websocket.*;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import lombok.val;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ServerEndpoint(value = "/user/chat/{userId}", decoders = ChatMessageCoder.class, encoders = ChatMessageCoder.class)
public class UserServiceEndpoint implements WebSocketEndpoint {
    private static final ConcurrentHashMap<Integer, UserServiceEndpoint> userEndpointPool = new ConcurrentHashMap<>();

    private Session session;
    private Integer userId;

    private static SessionService sessionService;
    private static SessionLogMapper sessionLogMapper;
    private static SessionFind sessionFind;
    private static AIService aiService;

    @Autowired
    public void setDependencies(SessionService sessionService,SessionLogMapper sessionLogMapper,SessionFind sessionFind,AIService aiService){
        UserServiceEndpoint.sessionService = sessionService;
        UserServiceEndpoint.sessionLogMapper = sessionLogMapper;
        UserServiceEndpoint.sessionFind = sessionFind;
        UserServiceEndpoint.aiService = aiService;
    }


    @OnOpen
    public void onOpen(Session session, @PathParam("userId") Integer userId) {
        this.session = session;
        this.userId = userId;
        userEndpointPool.put(userId, this);
    }

    @OnClose
    public void onClose() {
        if (userId != null) {
            userEndpointPool.remove(userId);
        }
    }

    // TODO:005
    @OnMessage
    public void onMessage(ChatMessage message, Session session) throws EncodeException, IOException, IllegalAccessException {
        message.setType(getEndpointType());
        // 向商户发送信息
        baize.code.java.entity.Session chatSession = sessionService.find(message, userId, this);
        switch (chatSession.getConversationStatus()){
            case AI -> {
                // 判断当前用户是否要转人工
                aiService.turnToManualJudgement(chatSession,message);

                // 转人工服务
                if(chatSession.getConversationStatus() == baize.code.java.entity.Session.ConversationStatus.HUMAN){
                    // 提示消息
                    ChatMessage tipMsg = new ChatMessage();
                    tipMsg.setType( SessionLog.Type.SYSTEM);
                    tipMsg.setSessionId(chatSession.getId());
                    tipMsg.setCtId(chatSession.getCtId());
                    tipMsg.setGoodsId(chatSession.getGoodsId());
                    tipMsg.setMessage("已为您转接人工客服，请等待客服回复");
                    sendMessage(tipMsg);
                    // 保存数据库
                    sessionLogMapper.insert(SessionLog.builder()
                                    .type(tipMsg.getType())
                                    .sessionId(tipMsg.getSessionId())
                                    .content(tipMsg.getMessage())
                                    .build());
                    //找人工端点
                    CommercialTenantEndpoint ctEndPoint = sessionFind.findCommercialTenantEndPoint(chatSession.getId());
                    if(ctEndPoint != null){
                        ctEndPoint.sendMessage(message);
                    }
                }else {
                    // AI客服
                    aiService.chat(chatSession,message,this);
                }
            }
            case HUMAN -> {
                sessionLogMapper.insert(SessionLog.builder()
                        .type(message.getType())
                        .sessionId(message.getSessionId())
                        .content(message.getMessage())
                        .build()
                );
                CommercialTenantEndpoint ctEndPoint = sessionFind.findCommercialTenantEndPoint(chatSession.getId());
                if (ctEndPoint != null){
                    ctEndPoint.sendMessage(message);
                }
            }
        }
    }

    @OnError
    public void onError(Session session, Throwable error) {
        try {
            error.printStackTrace();
            session.getBasicRemote().sendObject(ChatMessage.builder()
                    .state(ChatMessage.State.ERROR)
                    .message(error.getMessage())
                    .build());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 封装发送消息的方法
     */
    public void sendMessage(ChatMessage chatMessage) throws EncodeException, IOException {
        this.session.getBasicRemote().sendObject(chatMessage);
    }

    @Override
    public SessionLog.Type getEndpointType() {
        return SessionLog.Type.USER;
    }

    public static UserServiceEndpoint findEndPoint(Integer userId) {
        return userEndpointPool.get(userId);
    }

}
