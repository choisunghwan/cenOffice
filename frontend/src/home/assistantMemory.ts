/**
 * 홈 입력창의 대화를 화면을 옮겨 다녀도 이어 가려고 메모리에 잡아 둔다(브라우저를 새로 고치거나 로그아웃하면 사라진다).
 * 대화에는 일정·할 일 같은 업무 정보가 들어 있어 디스크(localStorage)에는 저장하지 않는다.
 */
export const assistantMemory: { owner: string; turns: unknown[]; nextId: number; previous: string | null } = { owner: '', turns: [], nextId: 1, previous: null }

export function clearAssistantMemory() {
  assistantMemory.owner = ''
  assistantMemory.turns = []
  assistantMemory.nextId = 1
  assistantMemory.previous = null
}
