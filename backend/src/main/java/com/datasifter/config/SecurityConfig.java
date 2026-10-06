package com.datasifter.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(
            PasswordEncoder passwordEncoder,
            @Value("${datasifter.security.admin.username:}") String adminUsername,
            @Value("${datasifter.security.admin.password:}") String adminPassword,
            @Value("${datasifter.security.operator.username:}") String operatorUsername,
            @Value("${datasifter.security.operator.password:}") String operatorPassword,
            @Value("${datasifter.security.auditor.username:}") String auditorUsername,
            @Value("${datasifter.security.auditor.password:}") String auditorPassword,
            @Value("${datasifter.security.viewer.username:}") String viewerUsername,
            @Value("${datasifter.security.viewer.password:}") String viewerPassword) {
        List<UserDetails> users = new ArrayList<>();
        addUser(users, "admin", adminUsername, adminPassword, passwordEncoder);
        addUser(users, "operator", operatorUsername, operatorPassword, passwordEncoder);
        addUser(users, "auditor", auditorUsername, auditorPassword, passwordEncoder);
        addUser(users, "viewer", viewerUsername, viewerPassword, passwordEncoder);
        if (users.stream().noneMatch(user -> user.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN")))) {
            throw new IllegalStateException(
                    "Configure DATASIFTER_SECURITY_ADMIN_USERNAME and DATASIFTER_SECURITY_ADMIN_PASSWORD before starting DataSifter");
        }
        return new InMemoryUserDetailsManager(users);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        CookieCsrfTokenRepository csrfTokens = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokens.setHeaderName("X-XSRF-TOKEN");
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();

        http
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokens)
                        .csrfTokenRequestHandler(csrfHandler)
                        .ignoringRequestMatchers("/api/workers/**"))
                .httpBasic(Customizer.withDefaults())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/csrf").permitAll()
                        .requestMatchers("/api/workers/**").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/api/keyvault/**").hasRole("ADMIN")
                        .requestMatchers("/api/audit/**").hasAnyRole("ADMIN", "AUDITOR")
                        .requestMatchers(HttpMethod.POST, "/api/jobs/run").hasAnyRole("ADMIN", "OPERATOR")
                        .requestMatchers(HttpMethod.POST, "/api/**").hasAnyRole("ADMIN", "OPERATOR")
                        .requestMatchers(HttpMethod.PUT, "/api/**").hasAnyRole("ADMIN", "OPERATOR")
                        .requestMatchers(HttpMethod.PATCH, "/api/**").hasAnyRole("ADMIN", "OPERATOR")
                        .requestMatchers(HttpMethod.DELETE, "/api/**").hasAnyRole("ADMIN", "OPERATOR")
                        .anyRequest().authenticated());
        return http.build();
    }

    private void addUser(
            List<UserDetails> users,
            String role,
            String username,
            String password,
            PasswordEncoder passwordEncoder) {
        if (username.isBlank() && password.isBlank()) {
            return;
        }
        if (username.isBlank() || password.isBlank()) {
            throw new IllegalStateException("Both username and password must be configured for the " + role + " role");
        }
        if (password.length() < 16) {
            throw new IllegalStateException("The " + role + " role password must contain at least 16 characters");
        }
        users.add(User.withUsername(username)
                .password(passwordEncoder.encode(password))
                .roles(role.toUpperCase())
                .build());
    }
}
