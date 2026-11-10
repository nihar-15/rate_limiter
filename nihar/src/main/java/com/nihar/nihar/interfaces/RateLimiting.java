package com.nihar.nihar.interfaces;

import com.nihar.nihar.entity.*;
public interface RateLimiting{
    boolean isAllowed(User u, Request r, Path p);
}