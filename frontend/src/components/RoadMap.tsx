import { useEffect, useRef } from "react";
import L from "leaflet";
import {
  CONGESTION_COLORS,
  CONGESTION_LABELS,
  ROAD_TYPE_LABELS,
  type RoadLinkTraffic
} from "../types/traffic";

interface RoadMapProps {
  links: RoadLinkTraffic[];
  selectedLinkId: string | null;
  fitTrigger: number;
  onSelectLink: (linkId: string) => void;
}

export function RoadMap({
  links,
  selectedLinkId,
  fitTrigger,
  onSelectLink
}: RoadMapProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<L.Map | null>(null);
  const layerGroupRef = useRef<L.FeatureGroup | null>(null);
  const polylineByLinkIdRef = useRef<Map<string, L.Polyline>>(new Map());

  useEffect(() => {
    if (!containerRef.current || mapRef.current) {
      return;
    }
    const map = L.map(containerRef.current, {
      center: [37.52, 126.99],
      zoom: 11
    });

    L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
      attribution:
        '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
      maxZoom: 19
    }).addTo(map);

    const group = L.featureGroup().addTo(map);
    mapRef.current = map;
    layerGroupRef.current = group;

    return () => {
      map.remove();
      mapRef.current = null;
      layerGroupRef.current = null;
    };
  }, []);

  useEffect(() => {
    const map = mapRef.current;
    const group = layerGroupRef.current;
    if (!map || !group) {
      return;
    }

    group.clearLayers();
    polylineByLinkIdRef.current.clear();

    for (const link of links) {
      if (!link.geometry || link.geometry.length === 0) {
        continue;
      }
      const latLngs: L.LatLngTuple[] = link.geometry.map((pt) => [pt.lat, pt.lng]);
      const color = CONGESTION_COLORS[link.congestionLevel];
      const isSelected = link.linkId === selectedLinkId;
      const isUnknown = link.congestionLevel === "UNKNOWN";

      L.polyline(latLngs, {
        color: "#0f172a",
        weight: isSelected ? 11 : 9,
        opacity: 0.88,
        lineCap: "round",
        lineJoin: "round"
      }).addTo(group);

      const polyline = L.polyline(latLngs, {
        color,
        weight: isSelected ? 7 : 5,
        opacity: 0.96,
        dashArray: isUnknown ? "6, 8" : undefined,
        lineCap: "round",
        lineJoin: "round"
      }).addTo(group);

      const speedText = isUnknown ? "오픈API 미수신" : `${link.speedKph.toFixed(1)} km/h`;
      const popupHtml = `
        <div style="min-width:210px">
          <div style="font-size:0.75rem;color:#94a3b8">[${link.source}] ${ROAD_TYPE_LABELS[link.roadType]}</div>
          <div style="font-size:1rem;font-weight:700;margin:2px 0">${link.roadName}</div>
          <div style="font-size:0.84rem;margin-bottom:6px">${link.sectionName}</div>
          <div style="font-weight:700;color:${color}">
            ${CONGESTION_LABELS[link.congestionLevel]} · ${speedText}
          </div>
        </div>
      `;
      polyline.bindPopup(popupHtml);
      polyline.on("click", () => onSelectLink(link.linkId));
      polylineByLinkIdRef.current.set(link.linkId, polyline);
    }
  }, [links, selectedLinkId, onSelectLink]);

  useEffect(() => {
    const map = mapRef.current;
    const group = layerGroupRef.current;
    if (!map || !group || group.getLayers().length === 0) {
      return;
    }
    map.fitBounds(group.getBounds().pad(0.08));
  }, [fitTrigger]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map || !selectedLinkId) {
      return;
    }
    const polyline = polylineByLinkIdRef.current.get(selectedLinkId);
    if (polyline) {
      map.fitBounds(polyline.getBounds().pad(0.25), { maxZoom: 14 });
      polyline.openPopup();
    }
  }, [selectedLinkId]);

  return (
    <div className="map-card">
      <div ref={containerRef} id="road-map" />
      <div className="map-legend">
        <span className="legend-item">
          <i className="legend-dot" style={{ background: "#ef4444" }} />
          정체 (CONGESTED)
        </span>
        <span className="legend-item">
          <i className="legend-dot" style={{ background: "#f59e0b" }} />
          서행 (SLOW)
        </span>
        <span className="legend-item">
          <i className="legend-dot" style={{ background: "#10b981" }} />
          원활 (SMOOTH)
        </span>
        <span className="legend-item">
          <i className="legend-dot" style={{ background: "#64748b" }} />
          오픈API 미수신 (점선)
        </span>
      </div>
    </div>
  );
}
