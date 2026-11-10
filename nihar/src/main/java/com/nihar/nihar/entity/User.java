package com.nihar.nihar.entity;

import lombok.Getter;
import lombok.Setter;
import java.util.*;

@Getter
@Setter
public class User{
    private String userId; 
    private Set<String> activeSubscriptionPlans; 

   public String getUserId(){
        return  this.userId;
    }
}