package com.nihar.nihar.entity;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Request{
    String apiKey ; 
    String ipAddress;
    String userId;
    Path path; 
}