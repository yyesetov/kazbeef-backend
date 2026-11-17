package kz.kazbeef.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * Один администратор из переменных окружения (пароль хэшируется BCrypt). CSRF включён.
 * Интерфейс — одностраничное приложение: статика открыта, данные ({@code /api/**}) только после входа.
 * Вход: форма SPA шлёт POST /api/login; для скриптов оставлен HTTP Basic.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // 401 без заголовка WWW-Authenticate: иначе браузер покажет своё окно ввода пароля
        HttpStatusEntryPoint unauthorized = new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED);
        http
                // правила — в CorsConfig; без KAZBEEF_CORS_ORIGINS запросы с других доменов не разрешены
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/api/csrf").permitAll()
                        // публичный чат по базе знаний: без входа
                        .requestMatchers("/public/**").permitAll()
                        .requestMatchers("/admin/**", "/actuator/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                // у публичного чата нет сессии и действий от имени пользователя — CSRF-токен не нужен
                .csrf(c -> c.ignoringRequestMatchers("/public/**"))
                .exceptionHandling(e -> e.authenticationEntryPoint(unauthorized))
                .httpBasic(b -> b.authenticationEntryPoint(unauthorized))
                .formLogin(f -> f
                        .loginPage("/login")
                        .loginProcessingUrl("/api/login")
                        .successHandler((request, response, auth) -> response.setStatus(HttpStatus.NO_CONTENT.value()))
                        .failureHandler((request, response, ex) -> response.setStatus(HttpStatus.UNAUTHORIZED.value())))
                .logout(l -> l
                        .logoutUrl("/api/logout")
                        .logoutSuccessHandler((request, response, auth) -> response.setStatus(HttpStatus.NO_CONTENT.value())));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(KazbeefProperties props, PasswordEncoder encoder) {
        KazbeefProperties.Admin admin = props.admin();
        if (admin == null || admin.password() == null || admin.password().isBlank()) {
            throw new IllegalStateException("Не задан пароль администратора: переменная окружения ADMIN_PASSWORD");
        }
        return new InMemoryUserDetailsManager(User.withUsername(admin.username())
                .password(encoder.encode(admin.password()))
                .roles("ADMIN")
                .build());
    }
}
