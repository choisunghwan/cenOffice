import { useEffect, useState } from 'react'
import { useOffice } from '../store'

const event = 'hub-favorites-changed'
export function useToolFavorites() {
  const user = useOffice((s) => s.user)
  const key = `cen-hub-favorites:${user?.username ?? 'guest'}`
  const read = (): string[] => { try { const list: unknown = JSON.parse(localStorage.getItem(key) ?? '[]'); return Array.isArray(list) ? list.filter((x): x is string => typeof x === 'string').slice(0, 200) : [] } catch { return [] } }
  const [favorites, setFavorites] = useState(read)
  useEffect(() => { const refresh = () => setFavorites(read()); refresh(); window.addEventListener(event, refresh); window.addEventListener('storage', refresh); return () => { window.removeEventListener(event, refresh); window.removeEventListener('storage', refresh) } }, [key])
  const toggle = (id: string) => { const old = read(); const next = old.includes(id) ? old.filter((x) => x !== id) : [...old, id].slice(-200); try { localStorage.setItem(key, JSON.stringify(next)) } catch { /* Storage-disabled browsers still allow this session's selection. */ } setFavorites(next); window.dispatchEvent(new Event(event)) }
  return { favorites, toggle }
}
