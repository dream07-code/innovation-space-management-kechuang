package io.github.dream07code.innovationspace.security;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public class TokenService {
    private final SecureRandom random = new SecureRandom();
    public record GeneratedToken(String raw, boolean refreshAllowed) { @Override public String toString(){return "GeneratedToken[redacted]";} }
    public record GeneratedTokenPair(GeneratedToken access, GeneratedToken refresh) { @Override public String toString(){return "GeneratedTokenPair[redacted]";} }
    public GeneratedToken issuePasswordChange(long userId,long authRevision){return new GeneratedToken(create(),false);}
    public GeneratedTokenPair issueNormal(long userId,long authRevision){return new GeneratedTokenPair(new GeneratedToken(create(),true),new GeneratedToken(create(),true));}
    public String digest(String rawToken){
        try{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    public String validateSyntax(String raw){if(raw==null||!raw.matches("[A-Za-z0-9_-]{43}"))throw new IllegalArgumentException("Invalid token");return raw;}
    private String create(){byte[] bytes=new byte[32];random.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
}
