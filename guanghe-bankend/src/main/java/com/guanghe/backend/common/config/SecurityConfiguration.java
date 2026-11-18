package com.guanghe.backend.common.config;

import com.guanghe.backend.auth.security.JsonAccessDeniedHandler;
import com.guanghe.backend.auth.security.JsonAuthenticationEntryPoint;
import com.guanghe.backend.auth.security.JwtAuthenticationFilter;
import com.guanghe.backend.integration.internal.security.InternalHmacVerificationFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectProvider<JwtAuthenticationFilter> jwtFilterProvider,
            ObjectProvider<InternalHmacVerificationFilter> internalHmacFilterProvider,
            JsonAuthenticationEntryPoint authenticationEntryPoint,
            JsonAccessDeniedHandler accessDeniedHandler
    ) throws Exception {
        InternalHmacVerificationFilter internalHmacFilter = internalHmacFilterProvider.getIfAvailable();
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(authorize -> {
                    authorize.dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll();
                    authorize.requestMatchers("/actuator/health/**", "/actuator/info", "/api/v1/public/**")
                            .permitAll();
                    authorize.requestMatchers(HttpMethod.POST,
                                    "/api/v1/callbacks/media/order-progress",
                                    "/api/v1/callbacks/media/order-receipt",
                                    "/api/v1/callbacks/media/order-rejection",
                                    "/api/v1/callbacks/media/resource-update",
                                    "/api/callback/mt-order-progress",
                                    "/api/callback/mt-order-receipt",
                                    "/api/callback/mt-order-refund",
                                    "/api/callback/mt-resource-update")
                            .permitAll();
                    // Payment callbacks arrive from the provider, not from a signed-in browser, so
                    // they cannot carry a bearer token. Authentication happens in the controller:
                    // the message must prove itself with a platform signature before any order is
                    // touched, and nothing is accepted while that verification is unavailable.
                    authorize.requestMatchers(HttpMethod.POST, "/api/v1/callbacks/payment/notify")
                            .permitAll();
                    if (internalHmacFilter == null) {
                        authorize.requestMatchers("/internal/v1/**").denyAll();
                    } else {
                        authorize.requestMatchers("/internal/v1/**").permitAll();
                    }
                    authorize
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/complete-first-login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/me").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").authenticated()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/user/**").hasRole("USER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/execution-budget-policy").hasRole("USER")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/execution-budget-policy").hasRole("USER")
                        .requestMatchers("/api/v1/agents", "/api/v1/agent-installations/**", "/api/v1/projects/**",
                                "/api/v1/memory/**", "/api/v1/article-full-preparations/**",
                                "/api/v1/spaces/**", "/api/v1/products/**",
                                "/api/v1/competitors/**", "/api/v1/conversations/**",
                                "/api/v1/task-drafts/**", "/api/v1/tasks/**",
                                "/api/v1/billing/**", "/api/v1/assets/**",
                                "/api/v1/geo/**", "/api/v1/articles/**",
                                "/api/v1/media/**", "/api/v1/workflow-plans/**").hasRole("USER")
                        .anyRequest().denyAll();
                });

        JwtAuthenticationFilter jwtFilter = jwtFilterProvider.getIfAvailable();
        if (internalHmacFilter != null) {
            http.addFilterBefore(internalHmacFilter, UsernamePasswordAuthenticationFilter.class);
        }
        if (jwtFilter != null) {
            http.addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        }
        return http.build();
    }
}
