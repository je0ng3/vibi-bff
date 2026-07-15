// 로그인 provider 배지 스타일 + 라벨. Users 목록과 User 상세가 공유 —
// 두 화면의 배지가 어긋나지 않도록 단일 소스로 둔다.

const PROVIDER_BADGE: Record<string, string> = {
  google: "bg-red-50 text-red-700 border border-red-200",
  apple: "bg-neutral-900 text-white border border-neutral-900",
};

/** provider dbValue → Tailwind 배지 클래스. 미지 provider 는 중립 회색 fallback. */
export function providerBadgeClass(p: string): string {
  return PROVIDER_BADGE[p] ?? "bg-neutral-100 text-neutral-600 border border-neutral-200";
}

/** provider dbValue → 표시 라벨 (첫 글자 대문자). */
export function providerLabel(p: string): string {
  return p.length ? p.charAt(0).toUpperCase() + p.slice(1) : p;
}
