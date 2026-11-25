import { useEffect, useState } from 'react'
import { createPortal } from 'react-dom'
import QRCode from 'qrcode'

/**
 * 휴대폰으로 열기: 지금 사이트 주소를 QR 코드로 보여 준다(발표·시연 때 청중이 바로 스캔).
 * PC 상단 바에만 버튼을 두고, 휴대폰·태블릿에서는 숨긴다.
 */
export function MobileQr() {
  const [open, setOpen] = useState(false)
  const [img, setImg] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)
  const url = typeof window === 'undefined' ? '' : window.location.origin
  useEffect(() => {
    if (!open || img) return
    QRCode.toDataURL(url, { width: 640, margin: 1, errorCorrectionLevel: 'M', color: { dark: '#0b1b3a', light: '#ffffff' } }).then(setImg).catch(() => setImg(null))
  }, [open, img, url])
  useEffect(() => {
    if (!open) return
    const esc = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false) }
    window.addEventListener('keydown', esc)
    return () => window.removeEventListener('keydown', esc)
  }, [open])
  const copy = () => { void navigator.clipboard?.writeText(url); setCopied(true); setTimeout(() => setCopied(false), 1500) }

  return (
    <>
      <button type="button" className="qr-btn hidden lg:grid pointer-coarse:hidden" onClick={() => setOpen(true)} aria-label="휴대폰으로 열기 (QR 코드)" title="휴대폰으로 열기 (QR 코드)">
        <svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true"><rect x="3" y="3" width="7" height="7" rx="1" /><rect x="14" y="3" width="7" height="7" rx="1" /><rect x="3" y="14" width="7" height="7" rx="1" /><path d="M14 14h3v3h-3zM20 14v.01M14 20h.01M17 20h4v-3" /></svg>
      </button>
      {/* 상단 바(glass)는 fixed 자식을 가두므로, 창은 body 에 바로 띄운다 */}
      {open && createPortal(
        <div className="qr-backdrop" onClick={() => setOpen(false)}>
          <div role="dialog" aria-modal="true" aria-labelledby="qr-title" className="qr-card" onClick={(e) => e.stopPropagation()}>
            <h2 id="qr-title">휴대폰으로 열기</h2>
            <p>휴대폰 카메라로 QR 코드를 비추면 CEN HUB 가 바로 열립니다.</p>
            <div className="qr-img">{img ? <img src={img} alt={`${url} 로 가는 QR 코드`} /> : <span>QR 코드를 만드는 중…</span>}</div>
            <div className="qr-url"><code>{url}</code><button type="button" onClick={copy}>{copied ? '복사됨' : '주소 복사'}</button></div>
            <button type="button" className="qr-close" onClick={() => setOpen(false)}>닫기</button>
          </div>
        </div>, document.body)}
    </>
  )
}
