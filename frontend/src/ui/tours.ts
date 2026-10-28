/** 코치 마크(첫 사용 안내) 단계. target 은 화면 요소의 data-coach 이름이고, 화면에 보이지 않는 요소의 단계는 건너뛴다. */
export interface CoachStep {
  target: string
  title: string
  body: string
  /** 말풍선을 놓고 싶은 쪽(자리가 모자라면 다른 쪽으로 옮긴다) */
  placement: 'top' | 'bottom' | 'left' | 'right'
}

export const TOURS: Record<string, CoachStep[]> = {
  welcome: [
    { target: 'home-input', placement: 'top', title: '무엇이든 말로 시키세요',
      body: '일정 잡기, 회의 열기, 도구 쓰기, 다크 모드 바꾸기까지 여기에 말하면 됩니다. 예) “내일 오후 3시에 팀 회의 잡아줘”, “주간 팀 회의 회의록 다운로드해줘”' },
    { target: 'menu', placement: 'right', title: '업무 메뉴',
      body: '홈, 일정, 회의, 도구로 이동합니다. 입력창에서 “일정 화면 열어줘”라고 말해도 됩니다.' },
    { target: 'chat-history', placement: 'right', title: '대화는 저장돼요',
      body: '새 채팅으로 새 대화를 시작하고, 지난 대화는 여기서 다시 열 수 있습니다. 서버에 저장되어 다른 PC에서도 이어집니다.' },
    { target: 'menu-home', placement: 'right', title: '도구',
      body: '업무를 대신해 주는 도구를 찾아 쓰고, 직접 만들어 동료와 나눌 수 있습니다. 도구마다 결과물 양식(한글·워드·엑셀·파워포인트)을 올려 둘 수 있어요.' },
    { target: 'menu-meetings', placement: 'right', title: '회의와 회의록',
      body: '화상 회의를 열고, 끝나면 회의록을 만들어 확정하세요. 내 양식을 올려 두면 회의록이 그 양식에 맞춰 나옵니다.' },
    { target: 'topbar-right', placement: 'bottom', title: '알림과 화면 모드',
      body: '회의록 확정·할 일 알림은 종 모양에서, 다크 모드는 달 모양 버튼에서 바꿉니다. 이 안내는 설정에서 언제든 다시 볼 수 있어요.' },
  ],
}
