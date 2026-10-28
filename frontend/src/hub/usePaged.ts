import { useState } from 'react'

/** 이미 불러온 목록을 쪽으로 나눈다(서버가 쪽 단위로 주지 않는 목록용). page 는 0부터. 목록이 줄어 현재 쪽이 없어지면 마지막 쪽으로 당긴다. */
export function usePaged<T>(all: T[] | null | undefined, size: number) {
  const [page, setPage] = useState(0)
  const list = all ?? []
  const pages = Math.max(1, Math.ceil(list.length / size))
  const cur = Math.min(page, pages - 1)
  return { page: cur, setPage, pages, total: list.length, items: list.slice(cur * size, (cur + 1) * size) }
}
