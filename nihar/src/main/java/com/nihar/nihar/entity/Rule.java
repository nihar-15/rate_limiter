
@Component 
@Setter
@Getter
class Rule{
    String component ; // represents the component the rate limiting will be applied for example code run , code submit , login from dif devices 
    int limitCount; 
    String defaultPolicy; 
}