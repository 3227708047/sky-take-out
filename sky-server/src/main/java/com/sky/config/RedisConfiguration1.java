package com.sky.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

//@Configuration
//@Slf4j
public class RedisConfiguration1 {
//    @Autowired
    private RedisConnectionFactory factory;

//    public RedisTemplate redisTemplate(RedisConnectionFactory redisConnectionFactory){
//        log.info("开始创建Redis模版对象...");
//        RedisTemplate redisTemplate = new RedisTemplate();
//        //设置redis的连接工厂对象
////        redisTemplate.setConnectionFactory(redisConnectionFactory);
//        redisTemplate.setConnectionFactory(factory);
//        //设置redis key的序列化器
////        redisTemplate.setKeySerializer(new StringRedisSerializer());
//        GenericJackson2JsonRedisSerializer genericJackson2JsonRedisSerializer = new GenericJackson2JsonRedisSerializer();
//        //String 数据key序列化
//        redisTemplate.setKeySerializer(new StringRedisSerializer());
//        //String 数据value序列化
//        redisTemplate.setValueSerializer(genericJackson2JsonRedisSerializer);
//        //hash数据key序列化
//        redisTemplate.setHashKeySerializer(new StringRedisSerializer());
//        //hash数据value序列化
//        redisTemplate.setHashValueSerializer(genericJackson2JsonRedisSerializer);
//
//        redisTemplate.afterPropertiesSet();
//        return redisTemplate;
//    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        StringRedisSerializer stringRedisSerializer = new StringRedisSerializer();
        Jackson2JsonRedisSerializer<Object> jacksonSerializer = new Jackson2JsonRedisSerializer<>(Object.class);

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        objectMapper.enableDefaultTyping(ObjectMapper.DefaultTyping.NON_FINAL);
        jacksonSerializer.setObjectMapper(objectMapper);

        template.setKeySerializer(stringRedisSerializer);
        template.setValueSerializer(jacksonSerializer);
        template.setHashKeySerializer(stringRedisSerializer);
        template.setHashValueSerializer(jacksonSerializer);

        template.afterPropertiesSet();
        return template;
    }
}


