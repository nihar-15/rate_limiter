package com.nihar.nihar.entity;

import lombok.Getter;
import lombok.Setter;

// This class is responsible for identifying the component where rate limit is needed 

/*
Example Usuage 
URL pattern /api/code/run
Method POST
Component CODE_RUN

*/

@Getter
@Setter
public class Path {
    private String urlPattern;
    private String httpMethod;
    private String component;
}
