package com.lastseat.booking.service;

import com.lastseat.booking.domain.User;
import com.lastseat.booking.repository.UserRepository;
import com.lastseat.booking.web.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    public UserService(UserRepository users, JdbcTemplate jdbc) { this.users = users; this.jdbc = jdbc; }

    /**
     * Idempotent and race-free: two simultaneous sign-ups with the same e-mail both end up with
     * the same row thanks to INSERT ... ON CONFLICT DO NOTHING (no aborted transaction to recover from).
     */
    @Transactional
    public User getOrCreate(String name, String email) {
        String normalised = email.trim().toLowerCase();
        jdbc.update("INSERT INTO users(name, email) VALUES (?, ?) ON CONFLICT (email) DO NOTHING", name.trim(), normalised);
        return users.findByEmailIgnoreCase(normalised).orElseThrow();
    }

    @Transactional(readOnly = true)
    public User get(Long id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("User " + id + " not found"));
    }
}
