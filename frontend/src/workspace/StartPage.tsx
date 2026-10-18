import { useOffice } from '../store'

export function StartPage({ go }: { go: (page: string) => void }) {
  const user = useOffice((s) => s.user)
  return <div className="max-w-4xl mx-auto py-8 md:py-16">
    <p className="text-sm text-sky-300 mb-4">CEN OFFICE</p>
    <h1 className="text-3xl md:text-4xl font-semibold tracking-tight leading-tight">{user?.displayName}님,<br />어떤 일을 도와드릴까요?</h1>
    <p className="text-slate-400 mt-5 mb-10">하고 싶은 일을 선택하세요. 필요한 화면으로 바로 안내합니다.</p>
    <div className="grid md:grid-cols-2 gap-4">
      {[
        { page: 'aihub', mark: '✧', title: '프로젝트 자료로 업무 시작', desc: 'FP 산정·제안서 도우미를 살펴보고 자료를 선택해요.', primary: true },
        { page: 'agents', mark: '↗', title: 'AI와 요구사항 정리', desc: '필요한 기능을 설명하고 개발 작업을 함께 정리해요.' },
        { page: 'projects', mark: '▤', title: '프로젝트 이어하기', desc: '진행 중인 프로젝트와 할 일을 확인해요.' },
        { page: 'office', mark: '▦', title: '오피스 이용하기', desc: '회의실을 예약하고 사무실 장비를 관리해요.' },
      ].map((item) => <button key={item.page} onClick={() => go(item.page)} className={`text-left rounded-2xl border p-7 transition hover:-translate-y-0.5 focus-visible:outline-2 focus-visible:outline-sky-400 ${item.primary ? 'bg-sky-500/10 border-sky-400/30 hover:bg-sky-500/15' : 'bg-[#152238] border-white/10 hover:border-white/25'}`}>
        <span aria-hidden="true" className="text-2xl text-sky-300 block mb-6">{item.mark}</span>
        <h2 className="text-lg font-semibold mb-2">{item.title} <span aria-hidden="true" className="text-slate-500">→</span></h2>
        <p className="text-sm text-slate-400 leading-relaxed">{item.desc}</p>
      </button>)}
    </div>
  </div>
}
