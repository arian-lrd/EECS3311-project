package ca.yorku.eecs3311.huntassist.presentation.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class BrowserSessionProtection implements WebMvcConfigurer {
    private static final String CSRF_ATTRIBUTE = "huntassist.csrf";
    private static final Pattern LOCAL_HOST = Pattern.compile(
            "(?:127\\.0\\.0\\.1|localhost|\\[::1\\])(?::[0-9]{1,5})?", Pattern.CASE_INSENSITIVE);
    private final SecureRandom random = new SecureRandom();

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                    throws Exception {
                response.setHeader("Content-Security-Policy", "default-src 'none'; style-src 'self'; "
                        + "img-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'");
                response.setHeader("X-Content-Type-Options", "nosniff");
                response.setHeader("Referrer-Policy", "no-referrer");
                response.setHeader("Cache-Control", "no-store");

                String host = request.getHeader("Host");
                if (host == null || !LOCAL_HOST.matcher(host).matches()) {
                    return forbidden(response, "Open HuntAssist on localhost.");
                }
                if ("GET".equals(request.getMethod())) {
                    HttpSession session = request.getSession();
                    synchronized (session) {
                        if (session.getAttribute(CSRF_ATTRIBUTE) == null) {
                            byte[] secret = new byte[32];
                            random.nextBytes(secret);
                            session.setAttribute(CSRF_ATTRIBUTE, Base64.getUrlEncoder().withoutPadding()
                                    .encodeToString(secret));
                        }
                        request.setAttribute("csrfToken", session.getAttribute(CSRF_ATTRIBUTE));
                    }
                    return true;
                }
                if ("HEAD".equals(request.getMethod())) {
                    return true;
                }
                HttpSession session = request.getSession(false);
                String submitted = request.getParameter("_csrf");
                if (session == null || !(session.getAttribute(CSRF_ATTRIBUTE) instanceof String expected)
                        || submitted == null || submitted.length() != expected.length()
                        || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                                submitted.getBytes(StandardCharsets.UTF_8))) {
                    return forbidden(response, "Open the workspace again and resubmit the form.");
                }
                return true;
            }
        }).addPathPatterns("/", "/projects", "/projects/**");
    }

    private static boolean forbidden(HttpServletResponse response, String message) throws java.io.IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write(message);
        return false;
    }
}
