
package com.nihar.nihar.entity;

import lombok.Getter;
import lombok.Setter;

import java.util.*;

@Getter
@Setter
public class UserStatus {

    private String userId;

    private String component;

    // Fixed Window
    private int requestCount;
    private long windowStartTimestamp;

    // Sliding Window
    private List<Long> requestTimestamps;

    // Token Bucket
    private double availableTokens;
    private long lastRefillTimestamp;

    public UserStatus() {
        this.requestTimestamps = new ArrayList<>();
    }
}
