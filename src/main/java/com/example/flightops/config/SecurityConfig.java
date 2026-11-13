package com.example.flightops.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 测试账号（仅隔离开发/验证环境）：manager 可读写，viewer 只读。
 * Swagger UI 与 OpenAPI 文档开放访问。
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(AppProperties properties, PasswordEncoder encoder) {
        String managerPassword = properties.security() != null ? properties.security().managerPassword() : null;
        String viewerPassword = properties.security() != null ? properties.security().viewerPassword() : null;
        return new InMemoryUserDetailsManager(
                User.builder().username("manager")
                        .password(encoder.encode(managerPassword != null ? managerPassword : "manager123"))
                        .roles("MANAGER").build(),
                User.builder().username("viewer")
                        .password(encoder.encode(viewerPassword != null ? viewerPassword : "viewer123"))
                        .roles("VIEWER").build());
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/**").hasAnyRole("MANAGER", "VIEWER")
                        .requestMatchers("/api/**").hasRole("MANAGER")
                        .anyRequest().permitAll())
                .httpBasic(Customizer.withDefaults());
        return http.build();
    }
}
