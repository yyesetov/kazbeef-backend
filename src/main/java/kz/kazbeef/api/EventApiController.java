package kz.kazbeef.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import kz.kazbeef.risk.KazakhstanGeometry;
import kz.kazbeef.risk.RiskProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Чтение данных для интерфейса. Все списки и агрегаты принимают один и тот же {@link EventFilter}. */
@RestController
@RequestMapping("/api")
public class EventApiController {

    private final EventQueries queries;
    private final ObjectNode borderZone;

    public EventApiController(EventQueries queries, KazakhstanGeometry geometry, RiskProperties risk) {
        this.queries = queries;
        this.borderZone = geometry.borderZoneGeoJson(risk.borderZoneKm());
    }

    /** Текущий пользователь; 401, если не вошёл. */
    @GetMapping("/me")
    public Map<String, Object> me(Authentication auth) {
        List<String> roles = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
        return Map.of("username", auth.getName(), "roles", roles, "admin", roles.contains("ROLE_ADMIN"));
    }

    /** CSRF-токен для изменяющих запросов (вход, выход, действия админа). Доступен без входа. */
    @GetMapping("/csrf")
    public Map<String, String> csrf(HttpServletRequest request) {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        return Map.of("headerName", token.getHeaderName(), "parameterName", token.getParameterName(), "token", token.getToken());
    }

    @GetMapping("/reference")
    public Map<String, Object> reference() {
        return queries.reference();
    }

    /** Контур границы Казахстана (Natural Earth 1:50m). */
    @GetMapping(value = "/geo/kazakhstan", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Resource> kazakhstan() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)))
                .body(new ClassPathResource("geo/kazakhstan.geojson"));
    }

    /** Приграничная зона (ширина задаётся kazbeef.risk.border-zone-km). */
    @GetMapping("/geo/border-zone")
    public ResponseEntity<ObjectNode> borderZone() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofDays(1))).body(borderZone);
    }

    @GetMapping("/events")
    public Map<String, Object> events(EventFilter filter,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "50") int size,
                                      @RequestParam(defaultValue = "eventDate") String sort,
                                      @RequestParam(defaultValue = "desc") String dir) {
        return queries.page(filter, page, size, sort, dir);
    }

    @GetMapping("/events/{id}")
    public ResponseEntity<Map<String, Object>> event(@PathVariable long id) {
        Map<String, Object> event = queries.detail(id);
        return event == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(event);
    }

    @GetMapping("/map")
    public List<Map<String, Object>> map(EventFilter filter) {
        return queries.mapPoints(filter);
    }

    @GetMapping("/stats/summary")
    public Map<String, Object> summary(EventFilter filter) {
        return queries.summary(filter);
    }

    @GetMapping("/stats/timeseries")
    public List<Map<String, Object>> timeseries(EventFilter filter,
                                                @RequestParam(defaultValue = "week") String interval,
                                                @RequestParam(defaultValue = "none") String by) {
        return queries.timeseries(filter, interval, by);
    }

    @GetMapping("/stats/by-country")
    public List<Map<String, Object>> byCountry(EventFilter filter) {
        return queries.byCountry(filter);
    }

    @GetMapping("/stats/by-disease")
    public List<Map<String, Object>> byDisease(EventFilter filter) {
        return queries.byDisease(filter);
    }

    @GetMapping("/stats/by-species")
    public List<Map<String, Object>> bySpecies(EventFilter filter) {
        return queries.bySpecies(filter);
    }

    @GetMapping("/stats/by-source")
    public List<Map<String, Object>> bySource(EventFilter filter) {
        return queries.bySource(filter);
    }

    @GetMapping("/stats/matrix")
    public List<Map<String, Object>> matrix(EventFilter filter) {
        return queries.matrix(filter);
    }
}
