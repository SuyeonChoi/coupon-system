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
> 카프카란?  
>   분산 이벤트 스트리밍 플랫폼. 소스에서 목적지까지 이벤트를 실시간으로 스트리밍
>   카프카는 프로듀서에서 컨슈머까지 데이터를 실시간으로 스트리밍할 수 있도록 도와주는 플랫폼

- 카프카 기본 구조
  - Topic : 큐
  - Producer: 토픽에 데이터를 삽입할 수 있음
  - Consumer: 토픽에 삽입된 데이터를 가져갈 수 있음

카프카를 활용하면 프로듀서를 통해 쿠폰을 생성할 유저 아이디를 토픽에 넣고, 컨슈머를 사용해 유저 아이디를 가져와 쿠폰을 생성할 수 있다
- [kafka producer 추가](https://github.com/SuyeonChoi/coupon-system/commit/5083f6ae1d1ddac72bd6d389bca1b8161ba5f21c)
- [kafka consumer 추가](https://github.com/SuyeonChoi/coupon-system/commit/441fb591ac65d7eb9030abce71618084ddf0aa8c)
- 카프카를 사용하면 API에서 직접 쿠폰을 생성할 때에 비해 처리량을 조절할 수 있다.
    - DB 부하 감소 가능. 다만 consumer처리까지 약간의 시간 발생
 
### 발급 가능한 쿠폰 개수가 인당 1개로 제한되는 경우

- 해결방법1 - DB unique key 사용
    - Coupon에 userId, couponType 두 컬럼으로 unique key를 걸어 1개만 생성되도록 DB에서 막는 방법
    - 보통 서비스에서 한 유저가 같은 타입의 쿠폰을 여러개 가질 수 있으므로 실용적이지 않음
    
    ```java
    @Entity
    public class Coupon {
    
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
    
        private Long userId;
        
        private Long couponType;
    }
    ```

- 해결방법2 - lock 범위를 잡고, 처음에 쿠폰 발급 여부를 가져와서 판단하기
  ![image](https://github.com/user-attachments/assets/84dc960c-0b44-48d6-9992-c4856d7106e2)

  - 위 그림과 다르게 consumer가 아닌 API에서 쿠폰 발급 한다고 해도 락 범위가 너무 넓어짐. 성능 이슈 우려
  ```java
    public void apply(Long userId) {
        // lock start
        // 쿠폰발급 여부
        // if(발급됐다면) return
        Long count = couponCountRepository.increment();
        if (count > 100) {
            return; // 발급하지 않음
        }
        couponCreateProducer.create(userId);
        // lock end
    }
  ```

- 해결방법3 - Redis의 set 자료구조 활용하기
  - 유저가 쿠폰 응모를 하면 set에 추가
  - set에 존재하지 않으면 발급 진행하고 이미 존재하는 유저라면 발급하지 않는다
  - [코드 전체 예시](https://github.com/SuyeonChoi/coupon-system/commit/e86f969f92eceb26d540a51246dda3990e12e356#diff-de2762f9b230d6340ef637d4a66214a6ff0aab2c9683d220f44e8d8b6cc99cf3)
  ```java
    @Repository
    public class AppliedUserRepository {
    
        private final RedisTemplate<String, String> redisTemplate;
    
        public AppliedUserRepository(RedisTemplate<String, String> redisTemplate) {
            this.redisTemplate = redisTemplate;
        }
    
        public Long add(Long userId) {
            return redisTemplate
                    .opsForSet()
                    .add("applied_user", userId.toString());
        }
    }

    // ApplyService
    public void apply(Long userId) {
        Long apply = appliedUserRepository.add(userId);Add commentMore actions

        if (apply != 1) { // 이미 발급 요청을 한 유저
            return;
        }
       // 기존 로직...
     }
  ```

## Consumer에서 오류가 나는 경우?

- 현재 시스템에서는 consumer에서 토픽에 있는 데이터를 가져간 후, 쿠폰 발급 과정에서 에러가 나면 쿠폰은 발급되지 않았는데 발급된 쿠폰 개수만 올라가는 문제 발생 가능
  - 100개보다 적은 쿠폰이 발급됨 


### 해결방법 - 백업 데이터와 로그 남기기
- 보다 안전한 시스템을 만들기 위해 consumer에서 에러가 발생했을 때 처리하는 방법 ([코드](https://github.com/SuyeonChoi/coupon-system/commit/5b897e0285a6b0ac9bcba2ec426afee58eecb191))

```java
// CouponCreatedConsumer
@KafkaListener(topics = "coupon_create", groupId = "group_1")
public void listener(Long userId) {
    couponRepository.save(new Coupon(userId));
    try {
        couponRepository.save(new Coupon(userId));
    } catch (Exception e) {
        logger.error("failed to create coupon::", + userId); // 로그 남기기
        failedEventRepository.save(new FailedEvent(userId)); // 백업 데이터
    }
}

// 쿠폰 발급에 실패한 데이터
@Entity
public class FailedEvent {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    public FailedEvent() {
    }

    public FailedEvent(Long userId) {
        this.userId = userId;
    }
}
```


## 정리
![image](https://github.com/user-attachments/assets/e0ca8066-cdce-4039-a12b-71ec7d0deb42)

- API에서 쿠폰 발급을 요청할 때 토픽에 데이터를 넣는다
- 컨슈머에서 토픽의 데이터를 가져와 쿠폰 발급을 진행
- 쿠폰을 발급하면서 에러가 발생하면 FailedEvent에 실패한 이벤트 저장
- 이후에 별도의 배치 프로그램에서 FailedEvent에 쌓인 데이터를 주기적으로 읽어 쿠폰을 발급해준다면 결과적으로 100개의 쿠폰 발급 가능
  
