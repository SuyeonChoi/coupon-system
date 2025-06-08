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

```markdown
expected: 100L
but was: 122L
```

### 더 많은 쿠폰이 발급된 이유?
- **Race Condition**이 발생 : 두 개 이상의 스레드가 공유 데이터에 접근하여 동시에 작업 하려고할 때 발생하는 문제

- 기대

  <img src="https://github.com/user-attachments/assets/1d462032-2aa5-43eb-870c-714536f020c6" width="600" height="500"/>


- 결과

  <img src="https://github.com/user-attachments/assets/4627efb3-ea75-4418-8937-372fc0b4132e" width="600" height="500"/>


## Redis를 사용해서 해결하기
> redis를 사용하기 전에..

race condition은 두 개 이상의 스레드에서 공유 데이터에 접근할 때 발생하는 문제이므로 싱글 스레드로 작업한다면 일어나지 않을 것이다. 하지만 쿠폰 발급 로직을 싱글 스레드로 처리한다면 먼저 요청한 사람의 쿠폰이 발급된 이후, 다른 사람의 쿠폰을 발급해야하므로 **성능이 좋지 않을 것**이다. 
- 성능 문제 상황 예시
  - 10:01 1번 사용자가 발급 요청
  - 10:02 1번 사용자의 쿠폰 발급 완료
  - 2,3,4번 사용자는 10:02 이후부터 쿠폰 발급 요청 가능
- 해결방법1 - 자바의 경우 synchronized 키워드 사용
  - 서버가 여러대인 경우 race condition 발생할 수 있으므로 적절하지 않음
- 해결방법2 - mysql, redis를 사용한 lock 구현
  - 쿠폰 개수에 대한 정합성을 유지하면 되는데 lock은 발급된 쿠폰 개수를 가져오는 것부터 쿠폰을 생성할 때까지 걸어야 한다. 결국 lock을 거는 구간이 길어져 **성능 불이익**
  - 저장하는 로직이 2초가 걸리는 경우, lock이 2초 이후 풀리게 되고, 사용자들은 그만큼 대기 해야함

이 프로젝트의 핵심은 쿠폰 개수이므로, **쿠폰 개수에 대한 정합성만 관리**하면 된다.

### redis를 사용한 해결방법
- redis **INCR 명령어** 활용한다. 이 명령어는 **key에 대한 value를 1씩 증가**시킨다
- redis는 싱글 스레드 기반으로 동작하여, race condition을 해결할 수 있으며, INCR 명령어는 성능이 굉장히 빠르다
  - Redis는 인메모리(in-memory) 데이터베이스로, 데이터를 RAM에 저장하여 디스크 기반 데이터베이스보다 훨씬 빠른 데이터 읽기/쓰기 속도를 제공
  - INCR 명령어는 원자적(atomic) 연산으로, 동시 접근 시에도 안전하게 값을 증가시키고, 시간 복잡도가 O(1)로 매우 효율적

따라서 이 명령어를 사용하여 발급된 쿠폰 개수를 제어하면, 성능도 빠르고 데이터 정합성도 지킬 수 있다

[예시코드](https://github.com/SuyeonChoi/coupon-system/commit/2f64632651e1331090c93aa57d81e9fe7f75e3d5):
```java
@Repository
public class CouponCountRepository {

    private final RedisTemplate<String, String> redisTemplate;

    public CouponCountRepository(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public Long increment() {
        return redisTemplate
                .opsForValue()
                .increment("coupon_count");
    }
}

// ApplyService
public void apply(Long userId) {
    Long count = couponCountRepository.increment();

    // 쿠폰 발급 전에 발급된 쿠폰 개수를 증가시키고, 100개보다 많은 경우 발급하지 않음
    if (count > 100) {
        return;
    }

    // 발급 가능한 경우 쿠폰 생성
    couponRepository.save(new Coupon(userId));
}
```

- 동작 예시
  - 10:00 스레드1이 coupon_count 증가시키는 로직 실행
  - 10:02 스레드1 종료
  - 10:02 이후 다른 스레드 실행
- 모든 스레드는 언제나 최신값을 가져가고, 쿠폰이 100개 이상 발급되는 경우가 없음

### 문제점
- 현재 구조는 레디스를 사용해 쿠폰의 발급 개수를 가져온 후에, DB에 저장한다. 따라서 **발급하는 쿠폰의 개수가 많아질수록 DB에 부하**를 주게 된다.
  - 만약 사용하는 DB가 쿠폰 전용 db가 아닌 다양한 곳에서 사용하고 있는 경우, 다른 서비스까지 장애 전파
  - 짧은 시간 내에, 많은 요청이 오는 경우 DB 서버의 리소스를 많이 사용하게 되므로 부하가 발생하며 이는 서비스 지연 또는 오류로 이어진다
  - 부하테스트를 실행하면 실제로 DB CPU가 치솟는 것을 확인할 수 있다
- 예시
  ```markdown
  1분에 100개의 insert가 가능한 mysql이라고 가정
  - 10:00 쿠폰 생성 1000개 요청 → 100분이 걸림
  - 10:01 주문 생성 요청
  - 10:02 회원가입 요청
  ```
  - 주문 생성과 회원 가입 요청은 쿠폰 생성이 완료된 100분 이후에 수행된다. 타임아웃이 없으면 느리게라도 처리되겠지만, 대부분의 서비스에는 timeout이 설정되어 있으므로 쿠폰도 일부는 생성되지 않을 수 있다.


## Kafka를 활용하여 문제 해결하기



