package edu.gzhu.sitesafe.service;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Single source of truth for the device families that may report each metric.
 *
 * <p>The current alarm-rule schema infers a TYPE target from the metric instead
 * of storing a separate target_device_type column. Metrics shared by more than
 * one family therefore remain valid for DEVICE rules, but are intentionally
 * ambiguous for TYPE rules.</p>
 */
public final class DeviceMetricCatalog {
    private static final Map<String, Set<String>> METRIC_DEVICE_TYPES = Map.ofEntries(
            entry("weight", "TOWER_CRANE"),
            entry("windSpeed", "TOWER_CRANE"),
            entry("rotation", "TOWER_CRANE"),
            entry("height", "TOWER_CRANE"),
            entry("amplitude", "TOWER_CRANE"),
            entry("moment", "TOWER_CRANE"),
            entry("obliquity", "TOWER_CRANE"),
            entry("pm25", "ENVIRONMENT"),
            entry("pm10", "ENVIRONMENT"),
            entry("noise", "ENVIRONMENT"),
            entry("temperature", "ENVIRONMENT"),
            entry("humidity", "ENVIRONMENT"),
            entry("load", "ELEVATOR"),
            entry("floor", "ELEVATOR"),
            entry("speed", "ELEVATOR"),
            entry("direction", "ELEVATOR"),
            entry("doorStatus", "ELEVATOR"),
            entry("limitStatus", "ELEVATOR"),
            entry("displacement", "FORMWORK"),
            entry("pressure", "FORMWORK"),
            entry("horizontalDisplacement", "FOUNDATION_PIT"),
            entry("waterLevel", "FOUNDATION_PIT"),
            entry("earthPressure", "FOUNDATION_PIT"),
            entry("strain", "FOUNDATION_PIT"),
            entry("settlementRate", "FOUNDATION_PIT"),
            Map.entry("axialForce", Set.of("FORMWORK", "FOUNDATION_PIT")),
            Map.entry("settlement", Set.of("FORMWORK", "FOUNDATION_PIT")),
            Map.entry("xAngle", Set.of("FORMWORK", "FOUNDATION_PIT")),
            Map.entry("yAngle", Set.of("FORMWORK", "FOUNDATION_PIT"))
    );

    private DeviceMetricCatalog() {}

    public static boolean isKnownMetric(String metricCode) {
        return METRIC_DEVICE_TYPES.containsKey(metricCode);
    }

    public static boolean supports(String deviceType, String metricCode) {
        return METRIC_DEVICE_TYPES.getOrDefault(metricCode, Set.of()).contains(deviceType);
    }

    public static Set<String> deviceTypesFor(String metricCode) {
        return METRIC_DEVICE_TYPES.getOrDefault(metricCode, Set.of());
    }

    public static Optional<String> uniqueDeviceTypeFor(String metricCode) {
        Set<String> types = deviceTypesFor(metricCode);
        return types.size() == 1 ? Optional.of(types.iterator().next()) : Optional.empty();
    }

    public static String sourceTypeFor(String metricCode) {
        return "ENVIRONMENT".equals(uniqueDeviceTypeFor(metricCode).orElse(null))
                ? "ENVIRONMENT_RULE"
                : "DEVICE_RULE";
    }

    private static Map.Entry<String, Set<String>> entry(String metricCode, String deviceType) {
        return Map.entry(metricCode, Set.of(deviceType));
    }
}
