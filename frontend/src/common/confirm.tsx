import { useEffect, useRef, useState } from 'react'
import { create } from 'zustand'
import './confirm.css'

interface Options { title?: string; message: string; okText?: string; cancelText?: string; danger?: boolean }
interface TextOptions extends Options { placeholder?: string; required?: boolean }
interface Pending extends Options { resolve: (ok: boolean) => void; text?: { placeholder?: string; required?: boolean; resolve: (v: string | null) => void } }

const useConfirm = create<{ pending: Pending | null }>(() => ({ pending: null }))

/** 브라우저 기본 confirm 창 대신 쓰는 확인 창. 확인하면 true, 취소·바깥 클릭·Esc 면 false. */
export function askConfirm(options: Options): Promise<boolean> {
  return new Promise((resolve) => {
    useConfirm.getState().pending?.resolve(false) // 이미 떠 있는 창이 있으면 취소로 닫는다
    useConfirm.setState({ pending: { ...options, resolve } })
  })
}

/** 브라우저 기본 prompt 창 대신 쓰는 입력 창. 확인하면 입력한 글(빈칸일 수 있음), 취소·바깥 클릭·Esc 면 null. */
export function askText(options: TextOptions): Promise<string | null> {
  return new Promise((resolveText) => {
    useConfirm.getState().pending?.resolve(false)
    useConfirm.setState({ pending: { ...options, resolve: () => resolveText(null), text: { placeholder: options.placeholder, required: options.required, resolve: resolveText } } })
  })
}

/** 앱 맨 위에 한 번만 둔다 */
export function ConfirmHost() {
  const pending = useConfirm((s) => s.pending)
  const cancelRef = useRef<HTMLButtonElement>(null)
  const inputRef = useRef<HTMLTextAreaElement>(null)
  const [value, setValue] = useState('')
  const done = (ok: boolean) => {
    if (pending?.text) { if (ok) pending.text.resolve(value.trim()); else pending.resolve(false) } else pending?.resolve(ok)
    useConfirm.setState({ pending: null })
  }

  useEffect(() => {
    if (!pending) return
    setValue('')
    if (pending.text) inputRef.current?.focus()
    else cancelRef.current?.focus() // 지우기처럼 되돌릴 수 없는 일은 취소에 먼저 초점을 둔다
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
        {pending.text && <textarea ref={inputRef} className="confirm-input" rows={3} maxLength={500} value={value} placeholder={pending.text.placeholder} aria-labelledby="confirm-title"
          onChange={(e) => setValue(e.target.value)} onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) { e.preventDefault(); if (!pending.text?.required || value.trim()) done(true) } }} />}
        <div className="confirm-actions">
          <button type="button" ref={cancelRef} className="confirm-cancel" onClick={() => done(false)}>{pending.cancelText ?? '취소'}</button>
          <button type="button" className={pending.danger ? 'confirm-ok danger' : 'confirm-ok'} disabled={!!pending.text?.required && !value.trim()} onClick={() => done(true)}>{pending.okText ?? '확인'}</button>
        </div>
      </div>
    </div>
  )
}
