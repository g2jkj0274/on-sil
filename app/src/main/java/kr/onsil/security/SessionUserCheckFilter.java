package kr.onsil.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 같은 브라우저의 다른 탭에서 다른 계정으로 로그인하면 쿠키(세션)가 바뀐다.
 * 그러면 이전 탭은 화면과 다른 사람으로 요청을 보내게 되므로, 탭이 알고 있는 사용자(X-Onsil-User)와
 * 세션 사용자가 다르면 409 로 거절한다. 브라우저는 이를 받으면 새로고침한다.
 */
public class SessionUserCheckFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Onsil-User";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String expected = req.getHeader(HEADER);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (expected != null && auth != null && auth.getPrincipal() instanceof AppUser me
                && !expected.equals(String.valueOf(me.getId()))) {
            res.setStatus(HttpServletResponse.SC_CONFLICT);
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            res.setCharacterEncoding("UTF-8");
            res.getWriter().write("{\"code\":\"SESSION_USER_CHANGED\",\"message\":\"이 브라우저에서 다른 계정으로 로그인되었습니다.\"}");
            return;
        }
        chain.doFilter(req, res);
    }
}
