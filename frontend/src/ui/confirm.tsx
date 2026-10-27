import { useEffect, useRef } from 'react'
import { create } from 'zustand'
import './confirm.css'

interface Options { title?: string; message: string; okText?: string; cancelText?: string; danger?: boolean }
interface Pending extends Options { resolve: (ok: boolean) => void }

const useConfirm = create<{ pending: Pending | null }>(() => ({ pending: null }))

/** 브라우저 기본 confirm 창 대신 쓰는 확인 창. 확인하면 true, 취소·바깥 클릭·Esc 면 false. */
export function askConfirm(options: Options): Promise<boolean> {
  return new Promise((resolve) => {
    useConfirm.getState().pending?.resolve(false) // 이미 떠 있는 창이 있으면 취소로 닫는다
    useConfirm.setState({ pending: { ...options, resolve } })
  })
}

/** 앱 맨 위에 한 번만 둔다 */
export function ConfirmHost() {
  const pending = useConfirm((s) => s.pending)
  const cancelRef = useRef<HTMLButtonElement>(null)
  const done = (ok: boolean) => { pending?.resolve(ok); useConfirm.setState({ pending: null }) }

  useEffect(() => {
    if (!pending) return
    cancelRef.current?.focus() // 지우기처럼 되돌릴 수 없는 일은 취소에 먼저 초점을 둔다
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') { e.stopPropagation(); pending.resolve(false); useConfirm.setState({ pending: null }) } }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [pending])

  if (!pending) return null
  return (
    <div className="confirm-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) done(false) }}>
      <div className="confirm-box" role="alertdialog" aria-modal="true" aria-labelledby="confirm-title" aria-describedby="confirm-message">
        <h2 id="confirm-title">{pending.title ?? (pending.danger ? '삭제할까요?' : '확인')}</h2>
        <p id="confirm-message">{pending.message}</p>
        <div className="confirm-actions">
          <button type="button" ref={cancelRef} className="confirm-cancel" onClick={() => done(false)}>{pending.cancelText ?? '취소'}</button>
          <button type="button" className={pending.danger ? 'confirm-ok danger' : 'confirm-ok'} onClick={() => done(true)}>{pending.okText ?? '확인'}</button>
        </div>
      </div>
    </div>
  )
}
