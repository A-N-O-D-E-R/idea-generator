package com.anode.ideagenerator.config;

import com.anode.ideagenerator.Js;
import com.anode.ideagenerator.service.ApiKeys;
import com.anode.ideagenerator.service.TenantService;
import com.anode.security.AuthorizationRules;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.Customizer;
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
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.server.resource.authentication.OpaqueTokenAuthenticationProvider;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Both modes: CSRF for the SPA (XSRF-TOKEN cookie, X-XSRF-TOKEN header) and JSON errors under /api.
 * <p>
 * {@code ideagen.auth=oauth}: the A-N-O-D-E-R security starter builds the chain ({@code anode.security.env-oidc}, set from
 * {@code ideagen.auth} in application.yaml; GitHub and Google are two Spring client registrations, so the login is
 * multi-provider as it stands). What is specific to this app is a set of beans the starter picks up: the tenant behind
 * each login, which providers are offered and who may sign in, JSON errors, logout as {@code POST /api/logout},
 * {@code /api/**} needing a signed-in tenant while static files stay public, and personal API keys
 * ({@code idg_...}) as bearer credentials for other apps (no session, no CSRF). Sessions live in Postgres.
 * <p>
 * {@code ideagen.auth=none}: the starter has no "no login" mode, so this stays one small chain of its own: no login,
 * and requests must name a loopback host (stops DNS rebinding).
 */
@Slf4j
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
    private static final RequestMatcher API = PathPatternRequestMatcher.withDefaults().matcher("/api/**");

    // ---- ideagen.auth=none: no login, loopback only

    @Bean
    @ConditionalOnProperty(name = "ideagen.auth", havingValue = "none")
    SecurityFilterChain noLogin(HttpSecurity http) throws Exception {
        http.csrf(c -> c.spa()).addFilterAfter(csrfCookie(), AuthorizationFilter.class);
        jsonErrors(http, List.of());
        return http.authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(loopbackHostOnly(), SecurityContextHolderFilter.class)
                .build();
    }

    // ---- ideagen.auth=oauth: on top of the starter

    /** Only /api needs a signed-in tenant; the static frontend and the API docs are public. */
    @Bean
    @ConditionalOnProperty(name = "ideagen.auth", havingValue = "oauth")
    AuthorizationRules staticFilesArePublic() {
        return rules -> rules.requestMatchers(new NegatedRequestMatcher(API)).permitAll();
    }

    /** Personal {@code idg_} API keys as bearer tokens (the starter would otherwise resolve JWT issuers). */
    @Bean
    @ConditionalOnProperty(name = "ideagen.auth", havingValue = "oauth")
    AuthenticationManagerResolver<HttpServletRequest> apiKeyAuthentication(ApiKeys apiKeys) {
        var manager = new ProviderManager(new OpaqueTokenAuthenticationProvider(apiKeys));
        return request -> manager;
    }

    /** GitHub (OAuth2) and Google (OIDC) logins land on a tenant; only configured providers are offered. */
    @Bean
    @ConditionalOnProperty(name = "ideagen.auth", havingValue = "oauth")
    Customizer<HttpSecurity> tenantLogin(TenantService tenants, ClientRegistrationRepository registrations,
                                         @Value("${ideagen.allowed-logins:}") String allowedLogins) {
        List<String> logins = configured(registrations);
        if (logins.isEmpty()) {
            throw new IllegalStateException("ideagen.auth=oauth needs GITHUB_CLIENT_ID/GITHUB_CLIENT_SECRET or GOOGLE_CLIENT_ID/GOOGLE_CLIENT_SECRET");
        }
        Set<String> allowed = allowed(allowedLogins);
        if (allowed.isEmpty()) log.warn("ideagen.allowed-logins is empty: anyone with a {} account can sign up", logins);
        // Only configured providers get a login route (an "unset" one would redirect with client_id=unset).
        var defaults = new DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization");
        OAuth2AuthorizationRequestResolver onlyConfigured = new OAuth2AuthorizationRequestResolver() {
            public OAuth2AuthorizationRequest resolve(HttpServletRequest req) {
                String uri = req.getRequestURI();
                String id = uri.substring(uri.lastIndexOf('/') + 1);
                return uri.startsWith(req.getContextPath() + "/oauth2/authorization/") && !logins.contains(id) ? null : defaults.resolve(req);
            }

            public OAuth2AuthorizationRequest resolve(HttpServletRequest req, String id) {
                return logins.contains(id) ? defaults.resolve(req, id) : null;
            }
        };
        return http -> {
            try {
                http.oauth2Login(o -> o.loginPage("/")
                        .authorizationEndpoint(e -> e.authorizationRequestResolver(onlyConfigured))
                        .defaultSuccessUrl("/", true)
                        .failureUrl("/?signin=failed")
                        .userInfoEndpoint(u -> u.userService(github(tenants, allowed)).oidcUserService(google(tenants, allowed))));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            // the starter also enables oauth2Client(), which has its own redirect filter on /oauth2/authorization/{id}
            http.oauth2Client(c -> c.authorizationCodeGrant(g -> g.authorizationRequestResolver(onlyConfigured)));
            http.logout(l -> l.logoutUrl("/api/logout").logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)));
            http.addFilterAfter(csrfCookie(), AuthorizationFilter.class);
            jsonErrors(http, logins);
        };
    }

    /** 401 and 403 as the API's JSON under /api. */
    private static void jsonErrors(HttpSecurity http, List<String> logins) {
        http.exceptionHandling(e -> e
                .defaultAuthenticationEntryPointFor((req, res, ex) -> json(res, 401,
                        Js.obj("error", "Sign in first", "code", "unauthenticated", "login", logins)), API)
                .accessDeniedHandler((req, res, ex) -> json(res, 403, Js.obj("error", "Forbidden: missing or invalid CSRF token", "code", "forbidden"))));
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

    /** "github:<numeric id>,google:<sub>": stable ids, not logins or emails (a GitHub login can be renamed and re-taken). */
    public static Set<String> allowed(String csv) {
        return java.util.Arrays.stream(csv.split("[,\\s]+")).map(String::trim).filter(x -> !x.isEmpty()).collect(java.util.stream.Collectors.toSet());
    }

    /** Refuse before any tenant is created; the log line tells the operator which id to add. */
    public static void checkAllowed(Set<String> allowed, String provider, String subject) {
        if (allowed.isEmpty() || allowed.contains(provider + ":" + subject)) return;
        log.warn("Sign-in refused for {}:{} (not in ideagen.allowed-logins)", provider, subject);
        throw new org.springframework.security.oauth2.core.OAuth2AuthenticationException(
                new org.springframework.security.oauth2.core.OAuth2Error("not_allowed"), "This account is not allowed on this server");
    }

    private static OAuth2UserService<OAuth2UserRequest, OAuth2User> github(TenantService tenants, Set<String> allowed) {
        var delegate = new DefaultOAuth2UserService();
        return req -> {
            OAuth2User user = delegate.loadUser(req);
            checkAllowed(allowed, "github", String.valueOf(user.<Object>getAttribute("id")));
            String login = String.valueOf(user.<Object>getAttribute("login"));
            String email = user.getAttribute("email") instanceof String e ? e : null; // public profile email, display only
            return new TenantUser(tenants.login("github", String.valueOf(user.<Object>getAttribute("id")), login, email), login, user);
        };
    }

    private static OAuth2UserService<OidcUserRequest, OidcUser> google(TenantService tenants, Set<String> allowed) {
        var delegate = new OidcUserService();
        return req -> {
            OidcUser user = delegate.loadUser(req);
            checkAllowed(allowed, req.getClientRegistration().getRegistrationId(), user.getSubject());
            String email = Boolean.TRUE.equals(user.getEmailVerified()) ? user.getEmail() : null;
            String name = user.getFullName() != null ? user.getFullName() : email != null ? email : "Google user";
            return new TenantUser(tenants.login(req.getClientRegistration().getRegistrationId(), user.getSubject(), name, email), name, user);
        };
    }
}
