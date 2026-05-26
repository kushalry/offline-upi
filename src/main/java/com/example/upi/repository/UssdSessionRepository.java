package com.example.upi.repository;

import com.example.upi.model.UssdSession;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UssdSessionRepository extends JpaRepository<UssdSession, String> {
}
