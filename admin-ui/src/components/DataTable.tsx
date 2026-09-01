import { ReactNode } from "react";

/** 헤더 셀 정의. align="right" 는 숫자 컬럼용. */
export interface DataTableColumn {
  label: string;
  align?: "right";
}

/**
 * admin 표 공통 껍데기 — 가로 스크롤 래퍼 + 헤더 스타일 + tbody divider.
 * 셀 렌더는 화면마다 달라 추상화하지 않는다: 호출자가 `<tr>` 만 children 으로 넘긴다.
 *
 * [wrapperClassName] 은 섹션 안에 중첩되는 표(얇은 테두리)와 최상위 표(카드형)를 구분하기 위한 것.
 */
export default function DataTable({
  columns,
  children,
  wrapperClassName = "rounded-lg border border-neutral-200 bg-white",
  dense = false,
}: {
  columns: DataTableColumn[];
  children: ReactNode;
  wrapperClassName?: string;
  dense?: boolean;
}) {
  const cellPad = dense ? "px-4 py-2" : "px-4 py-3";
  return (
    <div className={`overflow-x-auto ${wrapperClassName}`}>
      <table className="min-w-full divide-y divide-neutral-200 text-sm">
        <thead className="bg-neutral-50 text-left text-xs font-medium uppercase tracking-wide text-neutral-500">
          <tr>
            {columns.map((c) => (
              <th key={c.label} className={`${cellPad} ${c.align === "right" ? "text-right" : ""}`}>
                {c.label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-neutral-100">{children}</tbody>
      </table>
    </div>
  );
}
