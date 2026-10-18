import { useState } from 'react'
import { API_URL } from '../api'
import { hub } from '../hubApi'
import { Badge, Btn, Card, ErrorNote, Input, errMsg, fmtTime, useLoad } from '../workspace/kit'

const MCP_URL = `${API_URL}/mcp`

function Cmd({ text, label }: { text: string; label?: string }) {
  const [done, setDone] = useState(false)
  return (
    <div>
      {label && <p className="text-xs text-slate-400 mb-1">{label}</p>}
      <div className="flex items-start gap-2">
        <code className="flex-1 text-xs whitespace-pre-wrap break-all rounded-lg bg-black/10 px-3 py-2 font-mono">{text}</code>
        <Btn small tone="ghost" onClick={() => { void navigator.clipboard?.writeText(text); setDone(true); setTimeout(() => setDone(false), 1500) }}>{done ? '복사됨' : '복사'}</Btn>
      </div>
    </div>
  )
}

/** AI HUB 도구를 Claude Code / Codex / 사내 AI 도구에서 쓰는 방법: ① 스킬 파일 ② MCP 연결(개인 토큰) */
export function ConnectPage() {
  const [samples, setSamples] = useState(false)
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const tokens = useLoad(() => hub.tokens(), [])
  const [name, setName] = useState('')
  const [days, setDays] = useState(90)
  const [secret, setSecret] = useState<string | null>(null)
  const [tokErr, setTokErr] = useState<string | null>(null)

  const download = async () => {
    setBusy(true); setErr(null)
    try { await hub.exportSkills(samples) } catch (e) { setErr(errMsg(e)) } finally { setBusy(false) }
  }
  const create = async () => {
    setTokErr(null)
    try { const r = await hub.createToken(name.trim(), days); setSecret(r.secret); setName(''); tokens.reload() } catch (e) { setTokErr(errMsg(e)) }
  }
  const revoke = async (id: number) => {
    setTokErr(null)
    try { await hub.revokeToken(id); tokens.reload() } catch (e) { setTokErr(errMsg(e)) }
  }
  const tok = secret ?? '<발급한 토큰>'

  return (
    <div className="space-y-5">
      <header className="hub-page-heading"><p>ITCEN AI HUB</p><h1>AI 도구에서 쓰기</h1><span>AI HUB 의 승인된 업무 도구를 내가 쓰는 AI 코딩·업무 도구로 가져가세요.</span></header>

      <Card title="방법 A. 스킬 파일로 설치 (Claude Code · Codex)">
        <p className="text-sm text-slate-400 mb-3">승인된 도구의 지침(SKILL.md)을 내려받아 스킬 폴더에 풉니다. AI 가 지침을 읽고 내 PC 에서 직접 수행합니다. 접근 권한이 있는 승인 도구만 담깁니다.</p>
        <label className="flex items-center gap-2 text-sm mb-3"><input type="checkbox" checked={samples} onChange={(e) => setSamples(e.target.checked)} /> 샘플 도구도 포함</label>
        <Btn disabled={busy} onClick={() => void download()}>{busy ? '만드는 중…' : '스킬 ZIP 내려받기'}</Btn>
        <ErrorNote message={err} />
        <div className="space-y-3 mt-4">
          <Cmd label="Claude Code · Windows (PowerShell)" text={'Expand-Archive .\\cen-ai-hub-skills.zip -DestinationPath "$HOME\\.claude\\skills" -Force'} />
          <Cmd label="Claude Code · macOS / Linux" text="unzip -o cen-ai-hub-skills.zip -d ~/.claude/skills" />
          <Cmd label="Codex · Windows (PowerShell)" text={'Expand-Archive .\\cen-ai-hub-skills.zip -DestinationPath "$HOME\\.codex\\skills" -Force'} />
          <Cmd label="Codex · macOS / Linux" text="unzip -o cen-ai-hub-skills.zip -d ~/.codex/skills" />
        </div>
      </Card>

      <Card title="방법 B. MCP 로 연결 (서버에서 실행 · 항상 최신)">
        <p className="text-sm text-slate-400 mb-3">AI 도구가 AI HUB 서버의 도구를 직접 호출합니다. 파일을 내려받거나 올릴 필요가 없고, 승인·권한·사용 통계가 웹과 같이 적용됩니다. 먼저 개인 토큰을 발급하세요.</p>
        <div className="flex gap-2 flex-wrap items-end">
          <div className="flex-1 min-w-48"><label className="text-xs text-slate-400">토큰 이름 (예: 내 노트북 Claude Code)</label><Input aria-label="토큰 이름" value={name} maxLength={64} onChange={(e) => setName(e.target.value)} /></div>
          <div><label className="text-xs text-slate-400">유효 기간(일)</label><Input aria-label="유효 기간" type="number" min={1} max={365} value={days} onChange={(e) => setDays(Number(e.target.value) || 90)} style={{ width: 90 }} /></div>
          <Btn disabled={!name.trim()} onClick={() => void create()}>토큰 발급</Btn>
        </div>
        <ErrorNote message={tokErr} />
        {secret && (
          <div role="alert" className="mt-3 rounded-lg border border-amber-400/60 bg-amber-50/60 p-3 text-sm">
            <p className="font-semibold mb-2">토큰은 지금 한 번만 보여 드립니다. 안전한 곳에 보관하세요.</p>
            <Cmd text={secret} />
            <Btn small tone="ghost" onClick={() => setSecret(null)}>닫기</Btn>
          </div>
        )}
        {!!tokens.data?.length && (
          <ul className="mt-4 divide-y divide-white/8 text-sm">
            {tokens.data.map((t) => (
              <li key={t.id} className="py-2 flex items-center gap-2 flex-wrap">
                <b>{t.name}</b><code className="text-xs">{t.prefix}</code>
                {t.revoked ? <Badge tone="idle">폐기됨</Badge> : t.expired ? <Badge tone="warn">만료됨</Badge> : <Badge tone="ok">사용 중</Badge>}
                <span className="text-xs text-slate-500">만료 {fmtTime(t.expiresAt)}{t.lastUsedAt ? ` · 마지막 사용 ${fmtTime(t.lastUsedAt)}` : ' · 사용 기록 없음'}</span>
                <span className="flex-1" />
                {!t.revoked && <Btn small tone="ghost" onClick={() => void revoke(t.id)}>폐기</Btn>}
              </li>
            ))}
          </ul>
        )}
        <div className="space-y-3 mt-4">
          <Cmd label="Claude Code" text={`claude mcp add --transport http cen-ai-hub ${MCP_URL} --header "Authorization: Bearer ${tok}"`} />
          <Cmd label="Codex (~/.codex/config.toml)" text={`[mcp_servers.cen-ai-hub]\nurl = "${MCP_URL}"\nbearer_token_env_var = "CEN_AI_HUB_TOKEN"\n# 환경변수 CEN_AI_HUB_TOKEN 에 발급한 토큰을 넣어 두세요`} />
          <Cmd label="그 밖의 MCP 지원 도구 (Streamable HTTP)" text={`URL: ${MCP_URL}\n헤더: Authorization: Bearer ${tok}`} />
        </div>
        <p className="text-xs text-slate-400 mt-3">도구 이름은 AI HUB 의 영문 이름(예: cen-sql-reviewer)으로 보입니다. 서버 주소는 접속한 환경에 맞게 바꿔 쓰세요.</p>
      </Card>

      <Card title="사내 AI 도구 (인텔리센코드 등)">
        <p className="text-sm text-slate-400">MCP(방법 B)를 지원하면 위와 같은 방식으로 연결할 수 있습니다. SKILL.md 폴더만 지원하면 방법 A 의 ZIP 을 해당 도구의 스킬·지침 폴더에 넣으세요. 지원 방식이 확인되면 안내를 보완하겠습니다.</p>
      </Card>
      <p className="text-xs text-slate-500">토큰은 비밀번호처럼 다루세요. 코드·채팅·문서에 붙여 넣지 마세요. 유출되면 바로 폐기하면 됩니다. 결과는 검토용 초안이며 개인정보·비밀번호는 입력하지 마세요.</p>
    </div>
  )
}
