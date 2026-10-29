import { askText } from '../common/confirm'
import { useState } from 'react'
import { hub } from '../hubApi'
import { Badge, Btn, Card, Empty, ErrorNote, errMsg, fmtTime, useLoad } from '../workspace/kit'
import { Pager } from './Pager'
import { usePaged } from './usePaged'

/** 관리자 승인 대기열: Skill 버전 / Agent. 위험 플래그(외부 URL, 네트워크 명령, 인젝션 의심 문구 등)를 함께 보여준다. */
export function ReviewQueue({ onOpenSkill, onOpenAgent, onChanged }: { onOpenSkill: (id: number) => void; onOpenAgent: (id: number) => void; onChanged: () => void }) {
  const q = useLoad(() => hub.queue(), [])
  const aq = useLoad(() => hub.agentQueue(), [])
  const qp = usePaged(q.data, 8)
  const ap = usePaged(aq.data, 8)
  const [err, setErr] = useState<string | null>(null)
  const act = async (fn: () => Promise<unknown>) => { try { await fn(); setErr(null); q.reload(); aq.reload(); onChanged() } catch (x) { setErr(errMsg(x)) } }
  const reject = async (fn: (c: string) => Promise<unknown>) => { const c = await askText({ title: '반려할까요?', message: '반려 사유를 적어 주세요.', okText: '반려', danger: true, placeholder: '사유를 입력해 주세요', required: true }); if (c) void act(() => fn(c)) }
  return (
    <div className="space-y-4">
      <ErrorNote message={err ?? q.error ?? aq.error} />
      <Card title={`Skill 검토 대기 ${q.data?.length ?? 0}건`}>
        {!q.data?.length ? <Empty>검토 대기 중인 Skill 이 없습니다.</Empty> : (
          <ul className="divide-y divide-white/8">{qp.items.map((i) => (
            <li key={i.versionId} className="py-3 space-y-1.5">
              <div className="flex items-center gap-2 flex-wrap text-sm">
                <span>{i.icon || '🧩'}</span><b>{i.name}</b><Badge tone="info">v{i.version}</Badge><Badge tone="idle">{i.packageKind}</Badge>
                <span className="text-xs text-slate-500">{i.ownerName} · {fmtTime(i.submittedAt)}</span><span className="flex-1" />
                <Btn small tone="ghost" onClick={() => onOpenSkill(i.skillId)}>내용 검토</Btn>
                <Btn small onClick={() => act(() => hub.approve(i.skillId, i.versionId))}>승인</Btn>
                <Btn small tone="danger" onClick={() => reject((c) => hub.reject(i.skillId, i.versionId, c))}>반려</Btn>
              </div>
              {i.riskFlags.length > 0 ? <div className="flex flex-wrap gap-1">{i.riskFlags.map((f) => <Badge key={f} tone="warn">{f}</Badge>)}</div> : <span className="text-[11px] text-slate-500">자동 탐지된 위험 플래그 없음 (내용은 직접 검토하세요)</span>}
            </li>
          ))}</ul>
        )}
        <Pager page={qp.page} pages={qp.pages} total={qp.total} onChange={qp.setPage} />
        <p className="text-[11px] text-slate-500 mt-2">본인이 등록한 항목은 다른 관리자가 승인해야 합니다. 위험 플래그는 참고용 자동 탐지이며 승인 여부는 관리자가 판단합니다.</p>
      </Card>
      <Card title={`Agent 검토 대기 ${aq.data?.length ?? 0}건`}>
        {!aq.data?.length ? <Empty>검토 대기 중인 Agent 가 없습니다.</Empty> : (
          <ul className="divide-y divide-white/8">{ap.items.map((a) => (
            <li key={a.id} className="py-3 flex items-center gap-2 flex-wrap text-sm">
              <span>🤖</span><b>{a.name}</b><Badge tone="idle">v{a.version}</Badge><Badge tone="idle">Skill {a.skillCount}개</Badge><span className="text-xs text-slate-500">{a.ownerName}</span><span className="flex-1" />
              <Btn small tone="ghost" onClick={() => onOpenAgent(a.id)}>구성 검토</Btn>
              <Btn small onClick={() => act(() => hub.approveAgent(a.id))}>승인</Btn>
              <Btn small tone="danger" onClick={() => reject((c) => hub.rejectAgent(a.id, c))}>반려</Btn>
            </li>
          ))}</ul>
        )}
        <Pager page={ap.page} pages={ap.pages} total={ap.total} onChange={ap.setPage} />
      </Card>
    </div>
  )
}
