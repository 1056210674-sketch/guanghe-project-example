package com.guanghe.backend.auth.account;

import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import org.springframework.stereotype.Component;

@Component
public final class ChinaPhoneNormalizer {
    public String normalize(String input) {
        String value=input==null?"":input.strip().replaceAll("[\\s()-]", "");
        if(value.startsWith("0086")) value=value.substring(4);
        else if(value.startsWith("+86")) value=value.substring(3);
        if(!value.matches("^1[3-9][0-9]{9}$")) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        return "+86"+value;
    }
    public boolean looksLikePhone(String input) {
        if(input==null)return false; String value=input.strip().replaceAll("[\\s()-]", "");
        return value.matches("^(?:\\+86|0086)?1[3-9][0-9]{9}$");
    }
}
