import type { Entry } from './api'

const DOW = '일월화수목금토'
export const dowLabel = (i: number) => DOW[i]
export const pad = (n: number) => String(n).padStart(2, '0')

export const startOfDay = (d: Date) => new Date(d.getFullYear(), d.getMonth(), d.getDate())
export const addDays = (d: Date, n: number) => new Date(d.getFullYear(), d.getMonth(), d.getDate() + n)
export const startOfWeek = (d: Date) => addDays(startOfDay(d), -d.getDay()) // 일요일 시작
export const startOfMonth = (d: Date) => new Date(d.getFullYear(), d.getMonth(), 1)
export const sameDay = (a: Date, b: Date) => a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate()

/** input[type=date] 값 yyyy-mm-dd (로컬 날짜) */
export const toDateInput = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
export const toTimeInput = (d: Date) => `${pad(d.getHours())}:${pad(d.getMinutes())}`
/** 날짜 입력값 + 시간 입력값 → 로컬 시각 */
export const fromInputs = (date: string, time: string) => new Date(`${date}T${time || '00:00'}`)

/** 일정이 그 날(로컬 자정~다음 자정)과 겹치는가 */
export const overlapsDay = (e: Entry, day: Date) => new Date(e.startAt) < addDays(day, 1) && new Date(e.endAt) > startOfDay(day)

const md = (d: Date) => `${d.getMonth() + 1}월 ${d.getDate()}일 (${DOW[d.getDay()]})`
const hm = (d: Date) => `${pad(d.getHours())}:${pad(d.getMinutes())}`

/** "10월 31일 (금) 14:00 – 15:00" 같은 한 줄 표시 */
export function formatRange(e: Entry): string {
  const s = new Date(e.startAt), end = new Date(e.endAt)
  if (e.allDay) {
    const last = addDays(end, -1) // 종일 일정의 종료는 마지막 날의 다음 날 0시
    return sameDay(s, last) || last < s ? `${md(s)} 종일` : `${md(s)} – ${md(last)} 종일`
  }
  return sameDay(s, end) ? `${md(s)} ${hm(s)} – ${hm(end)}` : `${md(s)} ${hm(s)} – ${md(end)} ${hm(end)}`
}

/** 종일이거나 하루 이상 이어지는 일정(시간표가 아니라 "종일" 줄에 표시) */
export const isAllDayLike = (e: Entry) => e.allDay || new Date(e.endAt).getTime() - new Date(e.startAt).getTime() >= 24 * 3600_000

export const KIND_LABEL: Record<Entry['kind'], string> = { EVENT: '일정', RESERVATION: '회의실 예약', TASK: '내 할 일' }
