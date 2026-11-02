import type {
  CongestionLevel,
  ManualRefreshSummary,
  TrafficOverviewResponse,
  TrafficSource
} from "../types/traffic";

export async function fetchTrafficOverview(
  source?: TrafficSource | "",
  congestion?: CongestionLevel | "",
  bbox?: string | null,
  zoom?: number | null
): Promise<TrafficOverviewResponse> {
  const params = new URLSearchParams();
  if (source) {
    params.set("source", source);
  }
  if (congestion) {
    params.set("congestion", congestion);
  }
  if (bbox) {
    params.set("bbox", bbox);
  }
  if (typeof zoom === "number") {
    params.set("zoom", String(zoom));
  }
  const query = params.toString();
  const response = await fetch(`/api/v1/traffic/overview${query ? `?${query}` : ""}`);
  if (!response.ok) {
    throw new Error(`도로 현황 조회 실패: HTTP ${response.status}`);
  }
  return response.json() as Promise<TrafficOverviewResponse>;
}

export async function refreshTrafficFeeds(options: {
  source?: TrafficSource | "";
  linkId?: string | null;
}): Promise<ManualRefreshSummary> {
  const params = new URLSearchParams();
  if (options.linkId) {
    params.set("linkId", options.linkId);
  } else if (options.source) {
    params.set("source", options.source);
  }
  const query = params.toString();
  const response = await fetch(`/api/v1/traffic/refresh${query ? `?${query}` : ""}`, {
    method: "POST"
  });
  if (!response.ok) {
    throw new Error(`실시간 오픈API 새로고침 실패: HTTP ${response.status}`);
  }
  return response.json() as Promise<ManualRefreshSummary>;
}
