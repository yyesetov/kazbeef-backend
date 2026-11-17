package kz.kazbeef.risk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.operation.distance.DistanceOp;
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Контур Казахстана (Natural Earth 1:50m) и расстояния до него.
 * Геометрия хранится в плоскости, где долгота сжата на cos(48°): на широтах Казахстана
 * градус по обеим осям тогда примерно одинаков, и ближайшая точка границы ищется корректно.
 * Само расстояние считается по сфере (гаверсинус), погрешность — единицы процентов.
 */
@Component
public class KazakhstanGeometry {

    private static final double LON_SCALE = Math.cos(Math.toRadians(48));
    private static final double KM_PER_DEGREE = 111.32;
    private static final double EARTH_RADIUS_KM = 6371.0088;

    private final GeometryFactory gf = new GeometryFactory();
    private final ObjectMapper mapper;
    private final Geometry country;

    public KazakhstanGeometry(ObjectMapper mapper) {
        this.mapper = mapper;
        try (InputStream in = new ClassPathResource("geo/kazakhstan.geojson").getInputStream()) {
            this.country = read(mapper.readTree(in).path("geometry"));
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось прочитать контур Казахстана", e);
        }
    }

    /** Расстояние до границы Казахстана, км; 0 для точки внутри страны. */
    public double distanceKm(double latitude, double longitude) {
        Geometry point = gf.createPoint(new Coordinate(longitude * LON_SCALE, latitude));
        if (country.covers(point)) {
            return 0;
        }
        Coordinate nearest = DistanceOp.nearestPoints(country, point)[0];
        return haversineKm(latitude, longitude, nearest.y, nearest.x / LON_SCALE);
    }

    /** Приграничная зона: полоса заданной ширины вокруг страны, GeoJSON Feature. */
    public ObjectNode borderZoneGeoJson(double widthKm) {
        Geometry zone = DouglasPeuckerSimplifier.simplify(country.buffer(widthKm / KM_PER_DEGREE, 12), 0.05);
        ObjectNode feature = mapper.createObjectNode();
        feature.put("type", "Feature");
        feature.putObject("properties").put("widthKm", widthKm);
        ObjectNode geometry = feature.putObject("geometry");
        geometry.put("type", "MultiPolygon");
        ArrayNode polygons = geometry.putArray("coordinates");
        for (int i = 0; i < zone.getNumGeometries(); i++) {
            if (zone.getGeometryN(i) instanceof Polygon p) {
                ArrayNode rings = polygons.addArray();
                writeRing(rings.addArray(), p.getExteriorRing().getCoordinates());
                for (int h = 0; h < p.getNumInteriorRing(); h++) {
                    writeRing(rings.addArray(), p.getInteriorRingN(h).getCoordinates());
                }
            }
        }
        return feature;
    }

    private static void writeRing(ArrayNode ring, Coordinate[] coordinates) {
        for (Coordinate c : coordinates) {
            ring.addArray().add(Math.round(c.x / LON_SCALE * 1e4) / 1e4).add(Math.round(c.y * 1e4) / 1e4);
        }
    }

    private Geometry read(JsonNode geometry) {
        List<Polygon> polygons = new ArrayList<>();
        JsonNode coordinates = geometry.path("coordinates");
        if ("Polygon".equals(geometry.path("type").asText())) {
            polygons.add(polygon(coordinates));
        } else {
            coordinates.forEach(p -> polygons.add(polygon(p)));
        }
        // buffer(0) чинит возможные самопересечения упрощённого контура
        return gf.createMultiPolygon(polygons.toArray(Polygon[]::new)).buffer(0);
    }

    private Polygon polygon(JsonNode rings) {
        LinearRing shell = null;
        List<LinearRing> holes = new ArrayList<>();
        for (JsonNode ring : rings) {
            Coordinate[] cs = new Coordinate[ring.size()];
            for (int i = 0; i < cs.length; i++) {
                cs[i] = new Coordinate(ring.get(i).get(0).asDouble() * LON_SCALE, ring.get(i).get(1).asDouble());
            }
            LinearRing lr = gf.createLinearRing(cs);
            if (shell == null) shell = lr;
            else holes.add(lr);
        }
        return gf.createPolygon(shell, holes.toArray(LinearRing[]::new));
    }

    public static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(a));
    }
}
