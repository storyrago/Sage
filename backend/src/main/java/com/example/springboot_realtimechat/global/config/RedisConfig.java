package com.example.springboot_realtimechat.global.config;

import com.example.springboot_realtimechat.global.redis.PresenceRedisSubscriber;
import com.example.springboot_realtimechat.global.redis.RedisSubscriber;
import com.example.springboot_realtimechat.global.redis.TypingRedisSubscriber;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class RedisConfig {
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory redisConnectionFactory){
        RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(redisConnectionFactory);
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        redisTemplate.setValueSerializer(new GenericJacksonJsonRedisSerializer(new JsonMapper()));
        return redisTemplate;
    }

    @Bean
    public RedisMessageListenerContainer messageListenerContainer(RedisConnectionFactory redisConnectionFactory,
                                                                  RedisSubscriber redisSubscriber,
                                                                  PresenceRedisSubscriber presenceRedisSubscriber,
                                                                  TypingRedisSubscriber typingRedisSubscriber,
                                                                  ChannelTopic channelTopic,
                                                                  ChannelTopic presenceTopic,
                                                                  ChannelTopic typingTopic){
        RedisMessageListenerContainer redisMessageListenerContainer = new RedisMessageListenerContainer();
        redisMessageListenerContainer.setConnectionFactory(redisConnectionFactory);
        redisMessageListenerContainer.addMessageListener(redisSubscriber, channelTopic);
        redisMessageListenerContainer.addMessageListener(presenceRedisSubscriber, presenceTopic);
        redisMessageListenerContainer.addMessageListener(typingRedisSubscriber, typingTopic);
        return redisMessageListenerContainer;
    }

    @Bean
    public ChannelTopic channelTopic(){
        return new ChannelTopic("chatroom");
    }

    @Bean
    public ChannelTopic presenceTopic(){
        return new ChannelTopic("presence");
    }

    @Bean
    public ChannelTopic typingTopic(){
        return new ChannelTopic("typing");
    }
}
