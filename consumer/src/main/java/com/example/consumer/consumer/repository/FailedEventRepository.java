package com.example.consumer.consumer.repository;


import com.example.consumer.consumer.domain.FailedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FailedEventRepository extends JpaRepository<FailedEvent, Long> {
}
