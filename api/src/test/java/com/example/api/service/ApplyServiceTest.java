package com.example.api.service;

import com.example.api.repository.CouponRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

@SpringBootTest
class ApplyServiceTest {

    @Autowired
    private ApplyService applyService;

    @Autowired
    private CouponRepository couponRepository;

    @Test
    public void 한번만응모() {
        applyService.apply(1L);

        long count = couponRepository.count();

        assertThat(count).isEqualTo(1);
    }

    @Test
    public void 여러명응모() throws InterruptedException {
        int threadCount = 1000; // 요청 수

        // 동시에 여러개 요청을 보내기 위해 멀티 스레드 사용
        ExecutorService executorService = Executors.newFixedThreadPool(32);

        // 모든 요청이 끝날때까지 기다림
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            long userId = i;
            executorService.submit(() -> {
                try {
                    applyService.apply(userId);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();

        // kafka producer에서 데이터 전송이 완료된 시점에서 쿠폰 개수를 조회
        // consumer에서는 이 시점에 모든 쿠폰을 생성하지 않았기 때문에 실패하는 것을 ThreadSleep을 통해 일시적으로 방지
        Thread.sleep(1000);

        // 모든 요청 완료 시 생성된 쿠폰 개수 확인
        long count = couponRepository.count();
        assertThat(count).isEqualTo(100);
    }
}
