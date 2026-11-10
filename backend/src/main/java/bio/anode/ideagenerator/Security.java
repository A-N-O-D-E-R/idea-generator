package bio.anode.ideagenerator;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Both modes: CSRF for the SPA (XSRF-TOKEN cookie, X-XSRF-TOKEN header) and JSON errors under /api.
 * ideagen.auth=none: no login, and requests must name a loopback host (stops DNS rebinding).
 * ideagen.auth=oauth: GitHub (OAuth2) or Google (OIDC) login, sessions in Postgres (Spring Session JDBC),
 * /api/** needs a signed-in tenant (401 JSON otherwise), static files are public, logout is POST /api/logout.
 */
@Configuration
public class Security {

    /** The signed-in principal: the provider's user, named by our tenant id. Stored in the JDBC session. */
    public record TenantUser(String tenantId, String displayName, OAuth2User login) implements OidcUser, Serializable {
        @Override public String getName() { return tenantId; }
        @Override public Map<String, Object> getAttributes() { return login.getAttributes(); }
        @Override public Collection<? extends GrantedAuthority> getAuthorities() { return login.getAuthorities(); }
        @Override public Map<String, Object> getClaims() { return login instanceof OidcUser o ? o.getClaims() : login.getAttributes(); }
        @Override public OidcUserInfo getUserInfo() { return login instanceof OidcUser o ? o.getUserInfo() : null; }
        @Override public OidcIdToken getIdToken() { return login instanceof OidcUser o ? o.getIdToken() : null; }
    }

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    @Bean
    SecurityFilterChain chain(HttpSecurity http, IdeaGeneratorApplication.Mode mode, Repo repo, ClientRegistrationRepository registrations) throws Exception {
        var api = PathPatternRequestMatcher.withDefaults().matcher("/api/**");
        List<String> logins = configured(registrations);
        http.csrf(c -> c.spa())
                .addFilterAfter(csrfCookie(), AuthorizationFilter.class)
                .exceptionHandling(e -> e
                        .defaultAuthenticationEntryPointFor((req, res, ex) -> json(res, 401,
                                Js.obj("error", "Sign in first", "code", "unauthenticated", "login", logins)), api)
                        .accessDeniedHandler((req, res, ex) -> json(res, 403, Js.obj("error", "Forbidden: missing or invalid CSRF token", "code", "forbidden"))));
        if (!mode.oauth()) {
            return http.authorizeHttpRequests(a -> a.anyRequest().permitAll())
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .addFilterBefore(loopbackHostOnly(), SecurityContextHolderFilter.class)
                    .build();
        }
        if (logins.isEmpty()) {
            throw new IllegalStateException("ideagen.auth=oauth needs GITHUB_CLIENT_ID/GITHUB_CLIENT_SECRET or GOOGLE_CLIENT_ID/GOOGLE_CLIENT_SECRET");
        }
        return http.authorizeHttpRequests(a -> a.requestMatchers(api).authenticated().anyRequest().permitAll())
                .oauth2Login(o -> o.loginPage("/")
                        .defaultSuccessUrl("/", true)
                        .failureUrl("/?signin=failed")
                        .userInfoEndpoint(u -> u.userService(github(repo)).oidcUserService(google(repo))))
                .logout(l -> l.logoutUrl("/api/logout").logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
                .build();
    }

    /** Registrations with a real client id (the yaml defaults them to "unset"). */
    static List<String> configured(ClientRegistrationRepository registrations) {
        if (!(registrations instanceof Iterable<?> all)) return List.of();
        return StreamSupport.stream(all.spliterator(), false).map(r -> (ClientRegistration) r)
                .filter(r -> !"unset".equals(r.getClientId()) && !r.getClientId().isBlank())
                .map(ClientRegistration::getRegistrationId).sorted().toList();
    }

    private static void json(HttpServletResponse res, int status, Object body) throws IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write(Js.stringify(body));
    }

    /** Resolves the deferred CSRF token on every request, so the SPA has its XSRF-TOKEN cookie before its first POST. */
    static OncePerRequestFilter csrfCookie() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws IOException, ServletException {
                if (req.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken token) token.getToken();
                chain.doFilter(req, res);
            }
        };
    }

    /** Without login the server only answers requests addressed to a loopback name, so a rebound DNS name gets nothing. */
    static OncePerRequestFilter loopbackHostOnly() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws IOException, ServletException {
                if (!LOOPBACK_HOSTS.contains(req.getServerName())) {
                    json(res, 403, Js.obj("error", "This server only answers on localhost", "code", "bad_host"));
                    return;
                }
                chain.doFilter(req, res);
            }
        };
    }

    private static OAuth2UserService<OAuth2UserRequest, OAuth2User> github(Repo repo) {
        var delegate = new DefaultOAuth2UserService();
        return req -> {
            OAuth2User user = delegate.loadUser(req);
            String login = String.valueOf(user.<Object>getAttribute("login"));
            String email = user.getAttribute("email") instanceof String e ? e : null; // public profile email, display only
            return new TenantUser(repo.login("github", String.valueOf(user.<Object>getAttribute("id")), login, email), login, user);
        };
    }

    private static OAuth2UserService<OidcUserRequest, OidcUser> google(Repo repo) {
        var delegate = new OidcUserService();
        return req -> {
            OidcUser user = delegate.loadUser(req);
            String email = Boolean.TRUE.equals(user.getEmailVerified()) ? user.getEmail() : null;
            String name = user.getFullName() != null ? user.getFullName() : email != null ? email : "Google user";
            return new TenantUser(repo.login(req.getClientRegistration().getRegistrationId(), user.getSubject(), name, email), name, user);
        };
    }
}
