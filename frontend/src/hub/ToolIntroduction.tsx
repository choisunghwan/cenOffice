import { DocumentContent } from './DocumentContent'
type Props = { name: string; description: string; owner: string; category: string; example?: string; usage?: string; ready: boolean; simulated?: boolean; onUse: () => void }

/** Public introduction uses registered content, without inventing performance claims. */
export function ToolIntroduction({ name, description, owner, category, example, usage, ready, simulated, onUse }: Props) {
  return <div className="tool-introduction">
    <div className="tool-intro-main">
      <section className="tool-benefit-hero"><p className="hub-section-label">{category} · AI 업무 도구</p><h2>{name}</h2><p className="tool-benefit-lead">{description}</p><div className="tool-benefit-path"><span>내 자료와 요청</span><b aria-hidden="true">→</b><span>AI 도구</span><b aria-hidden="true">→</b><span>결과 확인</span></div></section>
      <section className="tool-intro-section"><h3>이렇게 활용하세요</h3>{usage ? <p className="whitespace-pre-wrap">{usage}</p> : <p>{description}</p>}</section>
      <section className="tool-intro-section"><div className="flex justify-between gap-3 items-center mb-5"><h3>어떤 결과를 받을 수 있나요?</h3><span className="text-xs text-slate-400">제작자가 등록한 예시</span></div><div className="tool-large-example"><div className="tool-large-example-toolbar"><span aria-hidden="true">● ● ●</span><span>{name}</span></div>{example ? <DocumentContent text={example} /> : <div className="tool-example-missing"><span aria-hidden="true">▤</span><p>결과 예시를 준비하고 있어요.</p><small>등록된 소개와 사용 안내를 먼저 확인해 주세요.</small></div>}</div></section>
      <details className="hub-advanced"><summary>시작하는 방법 자세히 보기</summary><ol className="mt-4 space-y-3"><li>1. 도구의 사용 안내에서 필요한 자료와 요청 내용을 확인하세요.</li><li>2. 사용하기를 누르고 요청을 입력하세요.</li><li>3. 실행 결과를 확인하고 업무에 적용하기 전에 검토하세요.</li></ol></details>
    </div>
    <aside className="tool-start-panel"><p className="hub-section-label">{owner}</p><h3>{name}</h3><p>{description}</p><div className="tool-start-status"><span className={ready ? 'text-emerald-300' : 'text-amber-200'}>{ready ? simulated ? '테스트 실행 가능' : '사용 가능' : '실행 연동 준비 중'}</span></div><button className="tool-use-button" disabled={!ready} onClick={onUse}>{ready ? simulated ? '테스트로 사용하기' : '사용하기' : '연결 후 사용할 수 있어요'}<span aria-hidden="true">→</span></button><small>{ready ? '다음 화면에서 자료와 요청을 입력합니다.' : '지금은 소개와 예시를 둘러볼 수 있습니다.'}</small></aside>
  </div>
}
