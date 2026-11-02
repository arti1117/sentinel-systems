import {
  CONGESTION_LABELS,
  ROAD_TYPE_LABELS,
  type RoadLinkTraffic
} from "../types/traffic";

interface TrafficTableProps {
  links: RoadLinkTraffic[];
  selectedLinkId: string | null;
  refreshing: boolean;
  onSelectLink: (linkId: string) => void;
  onRefreshSingleLink: (linkId: string) => void;
}

export function TrafficTable({
  links,
  selectedLinkId,
  refreshing,
  onSelectLink,
  onRefreshSingleLink
}: TrafficTableProps) {
  return (
    <div className="table-wrapper">
      <table>
        <thead>
          <tr>
            <th>출처</th>
            <th>도로명 / 구간 (클릭 시 지도 추적)</th>
            <th>실측 속도</th>
            <th>상태</th>
            <th>단일 호출</th>
          </tr>
        </thead>
        <tbody>
          {links.map((link) => {
            const isUnknown = link.congestionLevel === "UNKNOWN";
            return (
              <tr
                key={link.linkId}
                className={link.linkId === selectedLinkId ? "selected" : ""}
                onClick={() => onSelectLink(link.linkId)}
              >
                <td>
                  <span className="source-tag">{link.source}</span>
                </td>
                <td>
                  <strong>{link.roadName}</strong>
                  <span className="road-type-sub">
                    {ROAD_TYPE_LABELS[link.roadType]}
                  </span>
                  <div className="section-sub">{link.sectionName}</div>
                </td>
                <td>
                  <strong>
                    {isUnknown ? "-" : `${link.speedKph.toFixed(1)} km/h`}
                  </strong>
                </td>
                <td>
                  <span className={`badge badge-${link.congestionLevel}`}>
                    {CONGESTION_LABELS[link.congestionLevel]}
                  </span>
                </td>
                <td>
                  <button
                    type="button"
                    className="row-refresh-btn"
                    disabled={refreshing}
                    onClick={(e) => {
                      e.stopPropagation();
                      onRefreshSingleLink(link.linkId);
                    }}
                  >
                    1건 갱신
                  </button>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
