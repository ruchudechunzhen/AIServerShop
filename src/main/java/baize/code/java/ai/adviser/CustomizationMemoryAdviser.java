package baize.code.java.ai.adviser;

import baize.code.java.ai.memory.CustomizationMemory;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class CustomizationMemoryAdviser implements BaseChatMemoryAdvisor {

    @Autowired
    private CustomizationMemory customizationMemory;

    // 模板，整合记忆
    private final PromptTemplate systemPromptTemplate = new PromptTemplate("{instructions}\n\nUse the conversation memory from the MEMORY section to provide accurate answers.\n\n---------------------\nMEMORY:\n{memory}\n---------------------\n\n");


    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        //1 获取聊天信息
        Map<String, Object> context = chatClientRequest.context();
        String conversationId = getConversationId(context, "default");
        if("default".equals(conversationId)){
            throw new RuntimeException("会话ID不能为空");
        }
        //2 根据聊天信息中的sessionId查询聊天记录
        //3从redis中查询记录
        //4redis中没有，从mysql中查询记录
        //5将查询结果放入redis中
        List<Message> messages = customizationMemory.get(conversationId);

        UserMessage userMessage111 = chatClientRequest.prompt().getUserMessage();
        System.out.println("userMessage1111111111"+userMessage111);
        //6将查询到的记忆整合到prompt中
        // 把message中的信息去取出来，拼接成完整的一段话
        String memory = messages.stream().filter(
                m -> m.getMessageType() == MessageType.USER || m.getMessageType() == MessageType.ASSISTANT
        ).map(m -> {
            String identity = String.valueOf(m.getMessageType());
            return identity + ":" + m.getText();
        }).collect(Collectors.joining(System.lineSeparator()));
        // 获取系统提示词
        SystemMessage systemMessage = chatClientRequest.prompt().getSystemMessage();
        // 将系统提示词和记忆整合
        String newSystemText = this.systemPromptTemplate.render(Map.of("instructions", systemMessage, "memory", memory));
        //复制一个新的聊天，并将提示词添加到新的聊天中
        ChatClientRequest processChatClientRequest = chatClientRequest.mutate()
                .prompt(chatClientRequest.prompt().augmentSystemMessage(newSystemText))
                .build();
        // 获取用户的本次对话，保存数据库
        UserMessage userMessage = chatClientRequest.prompt().getUserMessage();
        System.out.println("userMessage2222222"+userMessage);
        this.customizationMemory.add(conversationId, userMessage);
        //7返回整合后的的prompt
        return processChatClientRequest;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        // 将AI返回的内容进行存储
        String conversationId = getConversationId(chatClientResponse.context(), "default");
        //从响应中提取AI的回复
        if("default".equals(conversationId)){
            throw new RuntimeException("会话ID不能为空");
        }
        //获取AI返回的消息
        ChatResponse chatResponse = chatClientResponse.chatResponse();
        if(chatResponse==null){
            throw new RuntimeException("AI返回的消息为空");
        }
        StringBuilder content = new StringBuilder();
        for (Generation result :chatResponse.getResults()){
            content.append(result.getOutput().getText());
        }
        AssistantMessage assistantMessage = new AssistantMessage(content.toString());
        //将AI返回的消息存储在本地
        customizationMemory.add(conversationId,assistantMessage);
        return chatClientResponse;
    }

    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest, StreamAdvisorChain streamAdvisorChain) {
        // 先执行before方法
        ChatClientRequest precessedRequest = this.before(chatClientRequest, streamAdvisorChain);
        // 继续下游的流式调用
        Flux<ChatClientResponse> chatClientResponseFlux = streamAdvisorChain.nextStream(precessedRequest);
        return new ChatClientMessageAggregator().aggregateChatClientResponse(
                chatClientResponseFlux,
                aggreatedResponse->{
                    this.after(aggreatedResponse,streamAdvisorChain);
                }
        );
    }
}
