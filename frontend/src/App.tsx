import { useCallback, useEffect, useState } from "react";
import { fetchTrafficOverview, refreshTrafficFeeds } from "./api/trafficApi";
import { RoadMap } from "./components/RoadMap";
import { TrafficTable } from "./components/TrafficTable";
import type {
  CongestionLevel,
  TrafficOverviewResponse,
  TrafficSource
} from "./types/traffic";

export function App() {
  const [overview, setOverview] = useState<TrafficOverviewResponse | null>(null);
  const [sourceFilter, setSourceFilter] = useState<TrafficSource | "">("");
  const [congestionFilter, setCongestionFilter] = useState<CongestionLevel | "">("");
  const [viewportFilterEnabled, setViewportFilterEnabled] = useState(false);
  const [currentBbox, setCurrentBbox] = useState<string | null>(null);
  const [currentZoom, setCurrentZoom] = useState<number>(11);
  const [selectedLinkId, setSelectedLinkId] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [statusMessage, setStatusMessage] = useState(
    "쿼터 보호 모드 (최근 오픈API 실측 스냅샷 즉시 로드)"
  );
  const [fitTrigger, setFitTrigger] = useState(1);

  const loadData = useCallback(
    async (triggerMapFit = false) => {
      const data = await fetchTrafficOverview(
        sourceFilter,
        congestionFilter,
        viewportFilterEnabled ? currentBbox : null,
        currentZoom
      );
      setOverview(data);
      if (triggerMapFit) {
        setFitTrigger((n) => n + 1);
      }
    },
    [sourceFilter, congestionFilter, viewportFilterEnabled, currentBbox, currentZoom]
  );

  useEffect(() => {
    void loadData(false);
  }, [loadData]);

  const handleViewportChange = useCallback((bbox: string, zoom: number) => {
    setCurrentBbox(bbox);
    setCurrentZoom(zoom);
  }, []);

  const handleManualRefresh = async (linkIdOnly: string | null) => {
    setRefreshing(true);
    setStatusMessage("오픈API 실시간 소통 속도 수신 중...");
    try {
      const summary = await refreshTrafficFeeds({
        source: linkIdOnly ? "" : sourceFilter,
        linkId: linkIdOnly
      });
      await loadData(false);
      if (linkIdOnly) {
        setSelectedLinkId(linkIdOnly);
      }
      const timeStr = new Date(summary.lastLiveRefreshAt).toLocaleTimeString(
        "ko-KR"
      );
      setStatusMessage(
        `실시간 수신 완료 (${timeStr}, 이번 호출 ${summary.apiCallsUsedNow}건)`
      );
    } catch (err) {
      setStatusMessage(
        err instanceof Error ? err.message : "오픈API 호출 중 오류가 발생했습니다."
      );
    } finally {
      setRefreshing(false);
    }
  };

  return (
    <div className="app-shell">
      <header>
        <div className="title-group">
          <h1>Sentinel Systems — 실시간 도로 관제 상황판 (React)</h1>
          <p>
            국토부 ITS (고속도로·국도) + 서울시 TOPIS (도시고속·간선도로) 정밀 노선 매핑 및 실측 소통 현황
          </p>
        </div>
        <div className="stages">
          <span className="stage-pill active">1. 도로현황</span>
          <span className="stage-pill">2. 돌발상황</span>
          <span className="stage-pill">3. 이상징후 (기상+인구)</span>
          <span className="stage-pill">4. 사고범위예측</span>
        </div>
      </header>

      <section className="kpi-grid">
        <div className="kpi-card">
          <div className="kpi-label">관제 노선 구간</div>
          <div className="kpi-value">{overview ? `${overview.totalLinks}개` : "-"}</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-label">정체 (CONGESTED)</div>
          <div className="kpi-value" style={{ color: "var(--congested)" }}>
            {overview ? `${overview.congestedCount}개` : "-"}
          </div>
        </div>
        <div className="kpi-card">
          <div className="kpi-label">서행 (SLOW)</div>
          <div className="kpi-value" style={{ color: "var(--slow)" }}>
            {overview ? `${overview.slowCount}개` : "-"}
          </div>
        </div>
        <div className="kpi-card">
          <div className="kpi-label">원활 (SMOOTH)</div>
          <div className="kpi-value" style={{ color: "var(--smooth)" }}>
            {overview ? `${overview.smoothCount}개` : "-"}
          </div>
        </div>
        <div className="kpi-card">
          <div className="kpi-label">오픈API 미수신</div>
          <div className="kpi-value" style={{ color: "var(--muted)" }}>
            {overview ? `${overview.unknownCount}개` : "-"}
          </div>
        </div>
        <div className="kpi-card">
          <div className="kpi-label">실측 평균 속도</div>
          <div className="kpi-value">
            {overview ? `${overview.averageSpeedKph} km/h` : "-"}
          </div>
        </div>
        <div className="kpi-card">
          <div className="kpi-label">세션 API 호출 수</div>
          <div className="kpi-value" style={{ color: "var(--accent)" }}>
            {overview ? `${overview.sessionApiCallsUsed}건` : "0건"}
          </div>
        </div>
      </section>

      <section className="toolbar">
        <div className="filter-group">
          <button
            type="button"
            className={sourceFilter === "" ? "active" : ""}
            onClick={() => setSourceFilter("")}
          >
            전체 소스 (21개 노선)
          </button>
          <button
            type="button"
            className={sourceFilter === "ITS" ? "active" : ""}
            onClick={() => setSourceFilter("ITS")}
          >
            ITS (고속도로·국도 9개)
          </button>
          <button
            type="button"
            className={sourceFilter === "TOPIS" ? "active" : ""}
            onClick={() => setSourceFilter("TOPIS")}
          >
            TOPIS (서울시 도로 12개)
          </button>
        </div>

        <div className="filter-group">
          {(["", "CONGESTED", "SLOW", "SMOOTH", "UNKNOWN"] as const).map(
            (lvl) => (
              <button
                key={lvl || "ALL"}
                type="button"
                className={congestionFilter === lvl ? "active" : ""}
                onClick={() => setCongestionFilter(lvl)}
              >
                {lvl === ""
                  ? "전체 상태"
                  : lvl === "CONGESTED"
                    ? "정체만"
                    : lvl === "SLOW"
                      ? "서행만"
                      : lvl === "SMOOTH"
                        ? "원활만"
                        : "미수신만"}
              </button>
            )
          )}
          <button
            type="button"
            className={viewportFilterEnabled ? "active" : ""}
            onClick={() => setViewportFilterEnabled((prev) => !prev)}
            title="JTS STRtree 공간 인덱스를 사용해 현재 지도 화면(BBox)과 교차하는 도로만 조회합니다"
          >
            {viewportFilterEnabled
              ? `화면 영역 R-Tree 검색 ON (Z${currentZoom})`
              : `화면 영역 R-Tree 검색 OFF (Z${currentZoom})`}
          </button>
          <button
            type="button"
            onClick={() => {
              setViewportFilterEnabled(false);
              setFitTrigger((n) => n + 1);
            }}
          >
            지도 전체 보기
          </button>
        </div>

        <div className="filter-group">
          <span className="quota-badge">
            {statusMessage}
            {overview?.lastLiveRefreshAt
              ? ` · 최근 실측: ${new Date(overview.lastLiveRefreshAt).toLocaleTimeString("ko-KR")}`
              : ""}
          </span>
          <button
            type="button"
            className="refresh-btn"
            disabled={refreshing}
            onClick={() => void handleManualRefresh(null)}
          >
            {refreshing ? "API 수신 중..." : "현재 목록 API 새로고침"}
          </button>
        </div>
      </section>

      <main className="workspace-grid">
        <RoadMap
          links={overview?.links ?? []}
          selectedLinkId={selectedLinkId}
          fitTrigger={fitTrigger}
          onSelectLink={setSelectedLinkId}
          onViewportChange={handleViewportChange}
        />
        <TrafficTable
          links={overview?.links ?? []}
          selectedLinkId={selectedLinkId}
          refreshing={refreshing}
          onSelectLink={setSelectedLinkId}
          onRefreshSingleLink={(linkId) => void handleManualRefresh(linkId)}
        />
      </main>
    </div>
  );
}
