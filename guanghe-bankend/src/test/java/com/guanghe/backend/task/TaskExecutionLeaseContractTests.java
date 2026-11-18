package com.guanghe.backend.task;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskExecutionLeaseContractTests {
    @Test
    void heartbeatAtomicallyRenewsOnlyTheCurrentLiveExecutionLease() {
        assertThat(updateSql("heartbeat")).contains(
                "set lease_expires_at=#{lease}",
                "last_heartbeat_at=#{now}",
                "execution_no=#{executionno}",
                "execution_token_hash=#{tokenhash}",
                "status='running'",
                "lease_expires_at>=#{now}");
    }

    @Test
    void watchdogRechecksCurrentLeaseBeforeLockingAndExpiringExecution() {
        assertThat(selectSql("lockExpiredExecution")).contains(
                "status='running'", "lease_expires_at<#{now}", "for update");
        assertThat(updateSql("expireExecution")).contains(
                "status='running'", "lease_expires_at<#{now}");
    }

    private String updateSql(String methodName) {
        Method method = method(methodName);
        return String.join(" ", method.getAnnotation(Update.class).value())
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private String selectSql(String methodName) {
        Method method = method(methodName);
        return String.join(" ", method.getAnnotation(Select.class).value())
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private Method method(String methodName) {
        return Arrays.stream(TaskControlMapper.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
    }
}
