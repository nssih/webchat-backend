package com.chat.project.chat.util;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
public class UidGenerator {

    private static final String PREFIX = "wc";
    private final AtomicLong sequence = new AtomicLong(System.currentTimeMillis() % 900000 + 100001L);

    public String generate() {
        return PREFIX + sequence.getAndIncrement();
    }
}