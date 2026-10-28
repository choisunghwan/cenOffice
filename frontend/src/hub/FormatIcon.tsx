/** 결과물 양식 형식(한글·워드·엑셀·파워포인트)의 아이콘 */
/** 형식별 아이콘 색과 글자. 상표 로고 대신 문서 모양에 글자만 얹는다. */
const LOOK: Record<string, { color: string; mark: string }> = {
  hwpx: { color: '#2f6fb0', mark: '한' },
  docx: { color: '#2b579a', mark: 'W' },
  xlsx: { color: '#1e7145', mark: 'X' },
  pptx: { color: '#d24726', mark: 'P' },
  pdf: { color: '#c0392b', mark: 'PDF' },
}

export function FormatIcon({ format, on, size = 40 }: { format: string; on: boolean; size?: number }) {
  const { color, mark } = LOOK[format] ?? { color: '#64748b', mark: '?' }
  return (
    <svg viewBox="0 0 40 48" width={size} height={Math.round(size * 1.2)} aria-hidden="true" className="tpl-icon-svg">
      <path d="M6 2h20l10 10v32a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z" fill={on ? color : 'currentColor'} opacity={on ? 1 : 0.18} />
      <path d="M26 2v8a2 2 0 0 0 2 2h8z" fill="#fff" opacity={on ? 0.45 : 0.3} />
      <text x="20" y="36" textAnchor="middle" fontSize={mark.length > 1 ? 11 : 18} fontWeight="800" fill={on ? '#fff' : 'currentColor'} opacity={on ? 1 : 0.5} fontFamily="inherit">{mark}</text>
    </svg>
  )
}
