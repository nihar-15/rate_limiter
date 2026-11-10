package com.nihar.nihar.entity;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Rule {
    private String component;
    private int limitCount;
    private int windowInSeconds;
    private RateLimitType algorithmType;
}