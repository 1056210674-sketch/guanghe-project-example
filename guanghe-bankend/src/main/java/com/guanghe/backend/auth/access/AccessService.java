package com.guanghe.backend.auth.access;

import com.guanghe.backend.auth.account.AccountRecord;
import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.account.ChinaPhoneNormalizer;
import com.guanghe.backend.auth.account.UsernameNormalizer;
import com.guanghe.backend.auth.config.AccountAccessProperties;
import com.guanghe.backend.auth.registration.RegistrationProperties;
import com.guanghe.backend.auth.security.ActivationTokenService;
import com.guanghe.backend.auth.security.AdaptivePasswordService;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.auth.security.IssuedJwt;
import com.guanghe.backend.auth.security.JwtTokenService;
import com.guanghe.backend.auth.web.AuthUserResponse;
import com.guanghe.backend.auth.web.CompleteFirstLoginRequest;
import com.guanghe.backend.auth.web.LoginResponse;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "zhixiaohe.auth.account-access", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class AccessService {
    private static final char[] TEMP_CHARS="ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789".toCharArray();
    private final AccessMapper mapper;private final SnowflakeIdGenerator ids;private final ChinaPhoneNormalizer phones;
    private final UsernameNormalizer usernames;
    private final AdaptivePasswordService passwords;private final ActivationTokenService activations;private final JwtTokenService jwt;
    private final RegistrationProperties agreements;private final AccountAccessProperties config;private final Clock clock;
    private final SecureRandom random=new SecureRandom();
    public AccessService(AccessMapper mapper,SnowflakeIdGenerator ids,ChinaPhoneNormalizer phones,UsernameNormalizer usernames,
                         AdaptivePasswordService passwords,ActivationTokenService activations,JwtTokenService jwt,
                         RegistrationProperties agreements,AccountAccessProperties config,
                         @Qualifier("authenticationClock") Clock clock){
        this.mapper=mapper;this.ids=ids;this.phones=phones;this.usernames=usernames;this.passwords=passwords;this.activations=activations;
        this.jwt=jwt;this.agreements=agreements;this.config=config;this.clock=clock;
    }

    @Transactional
    public AccessDtos.ProvisionedUserResponse create(AuthenticatedAccount actor,AccessDtos.CreateUserRequest request){
        requireAdmin(actor);
        if(!request.isSingleLoginIdentifier() || (request.username()!=null
                && !request.username().matches("^[A-Za-z][A-Za-z0-9_]{2,31}$")))throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        String phone=request.phone()==null?null:phones.normalize(request.phone());
        String username=phone==null?request.username():phone;
        int grant=request.initialPointGrant()==null?0:request.initialPointGrant();
        if(grant<0||grant>config.initialPointGrantMax())throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        String temp=temporaryPassword(),hash=passwords.encode(temp);OffsetDateTime now=now();long id=ids.nextId();
        AccountRecord account=new AccountRecord(id,username,usernames.normalize(username),phone,hash,AdaptivePasswordService.BCRYPT_V1,1,
                AccountRole.USER,AccountStatus.ACTIVE,request.displayName().strip(),null,"MUST_CHANGE_PASSWORD",now);
        try{
            mapper.insertAccount(account,now);
        }catch(DuplicateKeyException exception){
            String detail=exception.getMostSpecificCause().getMessage();
            if(detail!=null && (detail.contains("uk_account_username_normalized")||detail.contains("uk_account_phone_e164")))
                throw new BusinessException(phone==null?ErrorCode.USERNAME_ALREADY_EXISTS:ErrorCode.PHONE_ALREADY_EXISTS);
            throw exception;
        }
        mapper.insertPointAccount(id,grant,now);
        if(grant>0)mapper.insertGrantLedger(ids.nextId(),id,grant,"admin-initial-grant:"+id,actor.id(),now);
        mapper.insertAudit(ids.nextId(),actor.id(),id,"USER_PROVISIONED","{\"initialPointGrant\":"+grant+"}",now);
        return new AccessDtos.ProvisionedUserResponse(response(account),temp);
    }
    @Transactional(readOnly=true)
    public PageResponse<AccessDtos.UserResponse> list(AuthenticatedAccount actor,String search,int page,int size){
        requireAdmin(actor);String normalized=search==null||search.isBlank()?null:search.strip();long offset=(long)(page-1)*size;
        return PageResponse.of(mapper.findUsers(normalized,offset,size).stream().map(this::response).toList(),page,size,mapper.countUsers(normalized));
    }
    @Transactional(readOnly=true)
    public AccessDtos.UserResponse get(AuthenticatedAccount actor,long id){requireAdmin(actor);return response(user(id));}
    @Transactional
    public AccessDtos.ResetPasswordResponse reset(AuthenticatedAccount actor,long id){
        requireAdmin(actor);AccountRecord target=user(id);String temp=temporaryPassword();OffsetDateTime now=now();
        if(mapper.resetPassword(target.id(),passwords.encode(temp),now)!=1)throw new BusinessException(ErrorCode.ACCOUNT_STATE_CONFLICT);
        mapper.invalidateActivations(id);mapper.insertAudit(ids.nextId(),actor.id(),id,"PASSWORD_RESET","{}",now);
        return new AccessDtos.ResetPasswordResponse(Long.toString(id),"MUST_CHANGE_PASSWORD",temp);
    }
    @Transactional
    public AccessDtos.UserResponse disable(AuthenticatedAccount actor,long id){return status(actor,id,"DISABLED","USER_DISABLED");}
    @Transactional
    public AccessDtos.UserResponse restore(AuthenticatedAccount actor,long id){return status(actor,id,"ACTIVE","USER_RESTORED");}
    private AccessDtos.UserResponse status(AuthenticatedAccount actor,long id,String status,String event){
        requireAdmin(actor);user(id);OffsetDateTime now=now();
        if(mapper.setStatus(id,status,now)!=1)throw new BusinessException(ErrorCode.ACCOUNT_STATE_CONFLICT);
        if("DISABLED".equals(status))mapper.invalidateActivations(id);
        mapper.insertAudit(ids.nextId(),actor.id(),id,event,"{}",now);return response(user(id));
    }

    @Transactional
    public LoginResponse issueFirstLogin(AccountRecord account){
        if(account.status()!=AccountStatus.ACTIVE||!"MUST_CHANGE_PASSWORD".equals(account.credentialState()))throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        mapper.invalidateActivations(account.id());var issued=activations.issue(account);OffsetDateTime now=now();
        mapper.insertActivation(new AccessRecords.Activation(ids.nextId(),account.id(),issued.hash(),account.tokenVersion(),
                "AVAILABLE",OffsetDateTime.ofInstant(issued.expiresAt(),ZoneOffset.UTC),null,now));
        return new LoginResponse(null,"Activation",issued.expiresAt(),AuthUserResponse.from(account),"COMPLETE_FIRST_LOGIN",
                issued.value(),new LoginResponse.Agreement(agreements.termsVersion(),agreements.termsUrl()),
                new LoginResponse.Agreement(agreements.privacyVersion(),agreements.privacyUrl()));
    }
    @Transactional
    public LoginResponse complete(CompleteFirstLoginRequest request,String sourceIp){
        if(!agreements.termsVersion().equals(request.termsVersion())||!agreements.privacyVersion().equals(request.privacyVersion()))
            throw new BusinessException(ErrorCode.AGREEMENT_VERSION_MISMATCH);
        ActivationTokenService.ActivationClaims claims;
        try{claims=activations.decode(request.activationToken());}catch(JwtException|IllegalArgumentException e){throw new BusinessException(ErrorCode.ACTIVATION_TOKEN_INVALID);}
        AccessRecords.Activation activation=mapper.lockActivation(claims.tokenHash()).orElseThrow(()->new BusinessException(ErrorCode.ACTIVATION_TOKEN_INVALID));
        AccountRecord account=mapper.lockAccount(claims.userId()).orElseThrow(()->new BusinessException(ErrorCode.ACTIVATION_TOKEN_INVALID));
        OffsetDateTime now=now();
        if(activation.userId()!=account.id()||activation.tokenVersion()!=account.tokenVersion()||claims.tokenVersion()!=account.tokenVersion()
                ||!"AVAILABLE".equals(activation.status())||!activation.expiresAt().isAfter(now)||account.status()!=AccountStatus.ACTIVE
                ||!"MUST_CHANGE_PASSWORD".equals(account.credentialState()))throw new BusinessException(ErrorCode.ACTIVATION_TOKEN_INVALID);
        if(passwords.matches(request.newPassword(),account))throw new BusinessException(ErrorCode.PASSWORD_POLICY_VIOLATION);
        String ipHash=sha256(sourceIp==null?"unknown":sourceIp);
        if(mapper.completeActivation(account.id(),account.tokenVersion(),passwords.encode(request.newPassword()),now)!=1
                ||mapper.consumeActivation(activation.id(),now)!=1)throw new BusinessException(ErrorCode.ACTIVATION_TOKEN_INVALID);
        mapper.insertConsent(ids.nextId(),account.id(),"TERMS",agreements.termsVersion(),ipHash,now);
        mapper.insertConsent(ids.nextId(),account.id(),"PRIVACY",agreements.privacyVersion(),ipHash,now);
        mapper.insertAudit(ids.nextId(),account.id(),account.id(),"FIRST_LOGIN_COMPLETED","{}",now);
        AccountRecord active=mapper.findAccount(account.id()).orElseThrow();IssuedJwt token=jwt.issue(active);
        return new LoginResponse(token.value(),"Bearer",token.expiresAt(),AuthUserResponse.from(active));
    }
    @Transactional
    public void auditLegacyUpgrade(AccountRecord account){
        mapper.insertAudit(ids.nextId(),account.id(),account.id(),"LEGACY_PASSWORD_UPGRADED","{}",now());
    }
    private AccountRecord user(long id){return mapper.findAccount(id).filter(a->a.role()==AccountRole.USER).orElseThrow(()->new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));}
    private void requireAdmin(AuthenticatedAccount actor){AdminAccess.requireAdmin(actor);}
    private AccessDtos.UserResponse response(AccountRecord a){return new AccessDtos.UserResponse(Long.toString(a.id()),a.username(),a.phoneE164(),a.displayName(),a.status().name(),a.credentialState(),a.tokenVersion(),a.createdAt());}
    private String temporaryPassword(){StringBuilder out=new StringBuilder(config.temporaryPasswordLength());for(int i=0;i<config.temporaryPasswordLength();i++)out.append(TEMP_CHARS[random.nextInt(TEMP_CHARS.length)]);return out.toString();}
    private String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private OffsetDateTime now(){return OffsetDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
}
