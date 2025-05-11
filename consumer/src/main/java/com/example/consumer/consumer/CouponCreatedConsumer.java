package com.example.consumer.consumer;

import org.hibernate.annotations.Comment;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// 토픽에 전송된 데이터를 가져오는 consumer
@Component
public class CouponCreatedConsumer {

    @KafkaListener(topics = "coupon_create", groupId = "group_1")
    public void listener(Long userId) {
        System.out.println(userId);
    }
}
