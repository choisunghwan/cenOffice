import { useEffect, useState } from 'react'

const KEY = 'cen-office-appearance'
export function useAppearance() {
  const [appearance, setAppearance] = useState<'light' | 'dark'>(() => {
    try { return localStorage.getItem(KEY) === 'dark' ? 'dark' : 'light' } catch { return 'light' }
  })
  useEffect(() => {
    try { localStorage.setItem(KEY, appearance) } catch { /* Session preference still works. */ }
  }, [appearance])
  useEffect(() => {
    const sync = (e: StorageEvent) => {
      if (e.key === KEY) setAppearance(e.newValue === 'dark' ? 'dark' : 'light')
    }
    window.addEventListener('storage', sync)
    return () => window.removeEventListener('storage', sync)
  }, [])
  return { appearance, toggleAppearance: () => setAppearance((current) => current === 'light' ? 'dark' : 'light') }
}
