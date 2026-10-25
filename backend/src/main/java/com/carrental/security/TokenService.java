package com.carrental.security;
import com.carrental.modules.user.entity.Account;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.crypto.spec.SecretKeySpec;
import java.time.*;
import java.nio.charset.StandardCharsets;
@Service
public class TokenService {
 private final JwtEncoder encoder;private final JwtDecoder decoder;private final long minutes;
 public TokenService(@Value("${app.jwt-secret}") String secret,@Value("${app.token-minutes}") long minutes){
  if(secret.getBytes(StandardCharsets.UTF_8).length<32)throw new IllegalStateException("JWT_SECRET cần ít nhất 32 byte, cung cấp qua môi trường.");
  if(minutes<1||minutes>120)throw new IllegalStateException("TOKEN_MINUTES phải trong 1..120.");
  this.minutes=minutes;
  var key=new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256");
  encoder=new NimbusJwtEncoder(new ImmutableSecret<>(key));
  var d=NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
  d.setJwtValidator(JwtValidators.createDefaultWithIssuer("kdrive"));decoder=d;
 }
 public String issue(Account account){
  Instant now=Instant.now();
  var claims=JwtClaimsSet.builder().issuer("kdrive").subject(account.id).issuedAt(now).expiresAt(now.plusSeconds(minutes*60)).claim("tv",account.tokenVersion).build();
  return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
 }
 public Jwt decode(String token){return decoder.decode(token);}
}
