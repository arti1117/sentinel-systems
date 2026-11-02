export type TrafficSource = "ITS" | "TOPIS";

export type RoadType =
  | "HIGHWAY"
  | "NATIONAL"
  | "URBAN_HIGHWAY"
  | "URBAN_ARTERIAL";

export type CongestionLevel = "CONGESTED" | "SLOW" | "SMOOTH" | "UNKNOWN";

export interface GeoPoint {
  lat: number;
  lng: number;
}

export interface RoadLinkTraffic {
  source: TrafficSource;
  linkId: string;
  roadName: string;
  sectionName: string;
  roadType: RoadType;
  speedKph: number;
  congestionLevel: CongestionLevel;
  geometry: GeoPoint[];
  observedAt: string;
}

export interface TrafficOverviewResponse {
  totalLinks: number;
  smoothCount: number;
  slowCount: number;
  congestedCount: number;
  unknownCount: number;
  averageSpeedKph: number;
  sessionApiCallsUsed: number;
  lastLiveRefreshAt: string | null;
  links: RoadLinkTraffic[];
}

export interface ManualRefreshSummary {
  updatedCount: number;
  apiCallsUsedNow: number;
  sessionApiCallsUsed: number;
  lastLiveRefreshAt: string;
}

export const CONGESTION_LABELS: Record<CongestionLevel, string> = {
  CONGESTED: "정체",
  SLOW: "서행",
  SMOOTH: "원활",
  UNKNOWN: "미수신"
};

export const CONGESTION_COLORS: Record<CongestionLevel, string> = {
  CONGESTED: "#ef4444",
  SLOW: "#f59e0b",
  SMOOTH: "#10b981",
  UNKNOWN: "#64748b"
};

export const ROAD_TYPE_LABELS: Record<RoadType, string> = {
  HIGHWAY: "고속도로",
  NATIONAL: "일반국도",
  URBAN_HIGHWAY: "도시고속도로",
  URBAN_ARTERIAL: "도심간선도로"
};
