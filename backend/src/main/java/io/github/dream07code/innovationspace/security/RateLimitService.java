package io.github.dream07code.innovationspace.security;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import io.github.dream07code.innovationspace.common.api.ErrorCode;
import java.time.Clock;
import java.util.HashMap;
import org.springframework.stereotype.Service;
@Service
public class RateLimitService {
    private record Bucket(long window,int count){}
    private final HashMap<String,Bucket> buckets=new HashMap<>();private final Clock clock;
    public RateLimitService(Clock clock){this.clock=clock;}
    public synchronized void check(String key){
        long window=clock.instant().getEpochSecond()/60;
        buckets.entrySet().removeIf(e->e.getValue().window()!=window);
        var previous=buckets.get(key);int count=previous==null?0:previous.count();
        if(count>=10 || (previous==null&&buckets.size()>=10000))throw new ApiException(429,ErrorCode.RATE_LIMITED);
        buckets.put(key,new Bucket(window,count+1));
    }
}
