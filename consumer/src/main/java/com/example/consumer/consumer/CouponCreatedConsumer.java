package com.example.consumer.consumer;

import com.example.consumer.consumer.domain.Coupon;
import com.example.consumer.consumer.domain.FailedEvent;
import com.example.consumer.consumer.repository.CouponRepository;
import com.example.consumer.consumer.repository.FailedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// 토픽에 전송된 데이터를 가져오는 consumer
@Component
public class CouponCreatedConsumer {

    private final CouponRepository couponRepository;
    private final FailedEventRepository failedEventRepository;
    private final Logger logger = LoggerFactory.getLogger(CouponCreatedConsumer.class);

    public CouponCreatedConsumer(CouponRepository couponRepository, FailedEventRepository failedEventRepository) {
        this.couponRepository = couponRepository;
        this.failedEventRepository = failedEventRepository;
    }

    @KafkaListener(topics = "coupon_create", groupId = "group_1")
    public void listener(Long userId) {
        try {
            couponRepository.save(new Coupon(userId));
        } catch (Exception e) {
            logger.error("failed to create coupon::", + userId);
            failedEventRepository.save(new FailedEvent(userId));
        }
    }
}
