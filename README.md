# 선착순 이벤트 시스템

> 인프런 강의([실습으로 배우는 선착순 이벤트 시스템](https://www.inflearn.com/course/%EC%84%A0%EC%B0%A9%EC%88%9C-%EC%9D%B4%EB%B2%A4%ED%8A%B8-%EC%8B%9C%EC%8A%A4%ED%85%9C-%EC%8B%A4%EC%8A%B5)) 듣고 정리한 내용

## 요구사항 정의

```markdown
선착순 100명에게 할인쿠폰을 제공하는 이벤트를 진행하고자 한다.

이 이벤트는 아래와 같은 조건을 만족하여야 한다.
- 선착순 100명에게만 지급되어야한다.
- 101개 이상이 지급되면 안된다.
- 순간적으로 몰리는 트래픽을 버틸 수 있어야합니다.
```

### 문제

만약 [기본 동작](https://github.com/SuyeonChoi/coupon-system/commit/453c9c10104ecbc421a132179c46f3ea762b834e)에서 요청이 한개가 아닌 여러개가 들어온다면?

```java
// ApplyService

public void apply(Long userId) {
    long count = couponRepository.count();

    if (count > 100) {
        return; // 발급하지 않음
    }

    // 발급 가능한 경우 쿠폰 생성
    couponRepository.save(new Coupon(userId));
}
```

[테스트](https://github.com/SuyeonChoi/coupon-system/commit/4caae14d6045e49dca068824cd27549bd2962b3b)가 실패한다

```java
@Test
public void 여러명응모() throws InterruptedException {
    int threadCount = 1000; // 요청 수

    // 동시에 여러개 요청을 보내기 위해 멀티 스레드 사용
    ExecutorService executorService = Executors.newFixedThreadPool(32);

    // 모든 요청이 끝날때까지 기다리기
    CountDownLatch latch = new CountDownLatch(threadCount);

    for (int i = 0; i < threadCount; i++) {
        long userId = i;
        executorService.submit(() -> {
            applyService.apply(userId);
        });
    }

    latch.await();

    // 모든 요청 완료 시 생성된 쿠폰 개수 확인
    long count = couponRepository.count();
    assertThat(count).isEqualTo(100);
}
```

테스트 결과

```java
expected: 100L
but was: 122L
```

### 더 많은 쿠폰이 발급된 이유?
- **Race Condition**이 발생 : 두 개 이상의 스레드가 공유 데이터에 접근하여 동시에 작업 하려고할 때 발생하는 문제

- 기대
![image](https://github.com/user-attachments/assets/1d462032-2aa5-43eb-870c-714536f020c6)



- 결과
![image](https://github.com/user-attachments/assets/eb8b83d5-2b18-4849-a30a-3bac434c4576)



## Redis를 사용해서 해결하기





