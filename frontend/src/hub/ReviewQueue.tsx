import { askText } from '../common/confirm'
import { useEffect, useState } from 'react'
import { hub } from '../hubApi'
import { Badge, Btn, Card, Empty, ErrorNote, errMsg, fmtTime, useLoad } from '../workspace/kit'
import { Pager } from './Pager'
import { usePaged } from './usePaged'
import { RichText } from '../home/RichText'

const VERDICT = { PASS: { label: '검증 통과', tone: 'ok' }, FIX: { label: '보완 필요', tone: 'warn' }, REJECT: { label: '반려 권고', tone: 'error' } } as const

/**
 * 관리자 승인 대기열: Skill 버전 / Agent. 승인 요청된 스킬은 '스킬 검증'이 자동으로 점검하고(규칙 + AI 검토),
 * 관리자는 그 판정과 보고서를 보고 승인·반려한다.
 */
export function ReviewQueue({ onOpenSkill, onOpenAgent, onChanged }: { onOpenSkill: (id: number) => void; onOpenAgent: (id: number) => void; onChanged: () => void }) {
  const q = useLoad(() => hub.queue(), [])
  const aq = useLoad(() => hub.agentQueue(), [])
  const qp = usePaged(q.data, 8)
  const ap = usePaged(aq.data, 8)
  const [err, setErr] = useState<string | null>(null)
  const [checking, setChecking] = useState<number | null>(null)
  // 검증이 아직 안 끝난 항목이 있으면 잠시 뒤 다시 불러온다
  const pending = (q.data ?? []).some((i) => !i.checkVerdict)
  useEffect(() => { if (!pending) return; const t = setTimeout(() => q.reload(), 3000); return () => clearTimeout(t) }, [pending, q])
  const recheck = async (skillId: number, versionId: number) => { setChecking(versionId); try { await hub.recheck(skillId, versionId); q.reload(); setErr(null) } catch (x) { setErr(errMsg(x)) } finally { setChecking(null) } }
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
                <span>{i.icon || '🧩'}</span><b>{i.title || i.name}</b>
                {i.checkVerdict ? <Badge tone={VERDICT[i.checkVerdict].tone}>{VERDICT[i.checkVerdict].label}</Badge> : <Badge tone="idle">검증 중…</Badge>}<Badge tone="info">v{i.version}</Badge><Badge tone="idle">{i.packageKind}</Badge>
                <span className="text-xs text-slate-500">{i.ownerName} · {fmtTime(i.submittedAt)}</span><span className="flex-1" />
                <Btn small tone="ghost" onClick={() => onOpenSkill(i.skillId)}>내용 검토</Btn>
                <Btn small onClick={() => act(() => hub.approve(i.skillId, i.versionId))}>승인</Btn>
                <Btn small tone="danger" onClick={() => reject((c) => hub.reject(i.skillId, i.versionId, c))}>반려</Btn>
              </div>
              {i.checkReport ? (
                <details className="review-check">
                  <summary>스킬 검증 보고서 보기{i.checkedAt ? ` · ${fmtTime(i.checkedAt)}` : ''}</summary>
                  <RichText className="review-check-body" text={i.checkReport} />
                  <Btn small tone="ghost" disabled={checking === i.versionId} onClick={() => void recheck(i.skillId, i.versionId)}>{checking === i.versionId ? '검증 중…' : '다시 검증'}</Btn>
                </details>
              ) : <span className="text-[11px] text-slate-500">스킬 검증이 점검하고 있습니다. 잠시 뒤 결과가 나옵니다.</span>}
            </li>
          ))}</ul>
        )}
        <Pager page={qp.page} pages={qp.pages} total={qp.total} onChange={qp.setPage} />
        <p className="text-[11px] text-slate-500 mt-2">승인 요청된 스킬은 ‘스킬 검증’이 형식·완결성·안전·중복을 자동 점검합니다. 검증 결과는 참고용이며 승인 여부는 관리자가 판단합니다. 본인이 등록한 항목은 다른 관리자가 승인해야 합니다.</p>
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
