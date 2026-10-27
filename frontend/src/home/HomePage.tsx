import { AssistantBox } from './AssistantBox'
import '../calendar/calendar.css'
import './home.css'

/** 첫 화면. ChatGPT·Claude 처럼 입력창 하나로 일정·예약·할 일·도구 사용을 모두 처리한다. 정보는 물어볼 때 답변으로 보여 준다. */
export function HomePage() {
  return (
    <div className="calendar-page home-page chat-home">
      <AssistantBox />
    </div>
  )
}
