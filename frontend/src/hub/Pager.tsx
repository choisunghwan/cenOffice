/** 숫자 페이지 버튼. page 는 0부터 시작한다. 쪽이 많으면 처음·끝과 현재 쪽 둘레만 보이고 나머지는 … 로 줄인다. */
export function Pager({ page, pages, total, onChange }: { page: number; pages: number; total?: number; onChange: (page: number) => void }) {
  if (pages <= 1 && !total) return null // 항목이 하나도 없을 때만 감춘다
  const shown = new Set<number>([0, pages - 1])
  for (let i = page - 2; i <= page + 2; i++) if (i >= 0 && i < pages) shown.add(i)
  const nums = [...shown].sort((a, b) => a - b)
  return (
    <nav className="pager" aria-label="페이지 이동">
      <button type="button" disabled={page === 0} onClick={() => onChange(page - 1)}>‹ 이전</button>
      {nums.map((n, i) => (
        <span key={n} className="pager-item">
          {i > 0 && n - nums[i - 1] > 1 && <span className="pager-gap" aria-hidden="true">…</span>}
          <button type="button" className={n === page ? 'on' : ''} aria-current={n === page ? 'page' : undefined} aria-label={`${n + 1}쪽`} onClick={() => onChange(n)}>{n + 1}</button>
        </span>
      ))}
      <button type="button" disabled={page + 1 >= pages} onClick={() => onChange(page + 1)}>다음 ›</button>
      {total !== undefined && <span className="pager-total">총 {total}개</span>}
    </nav>
  )
}
