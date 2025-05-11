package com.example.api.service;

import com.example.api.domain.Coupon;
import com.example.api.producer.CouponCreateProducer;
import com.example.api.repository.AppliedUserRepository;
import com.example.api.repository.CouponCountRepository;
import com.example.api.repository.CouponRepository;
import org.springframework.stereotype.Service;

@Service
public class ApplyService {

    private final CouponRepository couponRepository;
    private final CouponCountRepository couponCountRepository;
    private final CouponCreateProducer couponCreateProducer;
    private final AppliedUserRepository appliedUserRepository;

    public ApplyService(
            CouponRepository couponRepository,
            CouponCountRepository couponCountRepository,
            CouponCreateProducer couponCreateProducer,
            AppliedUserRepository appliedUserRepository
    ) {
        this.couponRepository = couponRepository;
        this.couponCountRepository = couponCountRepository;
        this.couponCreateProducer = couponCreateProducer;
        this.appliedUserRepository = appliedUserRepository;
    }

    public void apply(Long userId) {
        Long apply = appliedUserRepository.add(userId);

        if (apply != 1) { // 이미 발급 요청을 한 유저
            return;
        }

        Long count = couponCountRepository.increment();
        // 쿠폰 발급 전에 발급된 쿠폰 개수를 증가시키고, 100개보다 많은 경우 발급하지 않음

        if (count > 100) {
            return; // 발급하지 않음
        }

        // 발급 가능한 경우 쿠폰 생성
        couponCreateProducer.create(userId);
    }
}


