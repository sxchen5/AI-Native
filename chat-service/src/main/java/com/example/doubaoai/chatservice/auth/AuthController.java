package com.example.doubaoai.chatservice.auth;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.doubaoai.chatservice.auth.dto.LoginRequest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
@Validated
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthUserProperties authUserProperties;
    private final CaptchaService captchaService;
    private final CaptchaImageRenderer captchaImageRenderer;
    private final HttpSessionSecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    public AuthController(AuthUserProperties authUserProperties, CaptchaService captchaService, CaptchaImageRenderer captchaImageRenderer) {
        this.authUserProperties = authUserProperties;
        this.captchaService = captchaService;
        this.captchaImageRenderer = captchaImageRenderer;
    }

    @GetMapping(value = "/captcha", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> captcha() throws IOException {
        String id = UUID.randomUUID().toString().replace("-", "");
        String code = captchaService.createCode();
        captchaService.save(id, code);
        byte[] png = captchaImageRenderer.renderPng(code);
        log.debug("captcha issued id={}", id);
        return ResponseEntity.ok()
                .header("X-Captcha-Id", id)
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate")
                .body(png);
    }

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest req, HttpServletRequest request, HttpServletResponse response) {
        captchaService.validate(req.captchaId(), req.captchaCode());
        boolean ok = authUserProperties.getUsers().stream()
                .anyMatch(u -> u.getUsername() != null && u.getUsername().equalsIgnoreCase(req.username().strip())
                        && u.getPassword() != null && u.getPassword().equals(req.password()));
        if (!ok) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        var auth = new UsernamePasswordAuthenticationToken(
                req.username().strip(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        HttpSession session = request.getSession(true);
        securityContextRepository.saveContext(context, request, response);
        log.info("login ok user={}", auth.getName());
        return Map.of("username", auth.getName());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        log.info("logout");
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        var a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !a.isAuthenticated() || "anonymousUser".equals(a.getPrincipal())) {
            return Map.of("authenticated", false);
        }
        return Map.of("authenticated", true, "username", a.getName());
    }
}
