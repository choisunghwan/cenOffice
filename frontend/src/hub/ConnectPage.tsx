import { useEffect, useState } from 'react'
import { API_URL } from '../api'
import { startTour, startTourOnce } from '../common/coach'
import { hub } from '../hubApi'
import { Badge, Btn, Card, ErrorNote, Input, errMsg, fmtTime, useLoad } from '../workspace/kit'

const MCP_URL = `${API_URL}/mcp`

function Cmd({ text, label, disabled }: { text: string; label?: string; disabled?: boolean }) {
  const [done, setDone] = useState(false)
  return (
    <div>
      {label && <p className="text-xs text-slate-400 mb-1">{label}</p>}
      <div className="flex items-start gap-2">
        <code className="flex-1 text-xs whitespace-pre-wrap break-all rounded-lg bg-black/10 px-3 py-2 font-mono">{text}</code>
        <Btn small tone="ghost" disabled={disabled} onClick={() => { void navigator.clipboard?.writeText(text); setDone(true); setTimeout(() => setDone(false), 1500) }}>{done ? '복사됨' : '복사'}</Btn>
      </div>
    </div>
  )
}

/** AI HUB 도구를 Claude Code / Codex 에서 쓰는 방법. ① 토큰 만들기 ② 명령 복사 ③ AI 에게 말하기 */
export function ConnectPage() {
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const tokens = useLoad(() => hub.tokens(), [])
  const [name, setName] = useState('내 PC')
  const [secret, setSecret] = useState<string | null>(null)
  const [tool, setTool] = useState<'claude' | 'codex'>('claude')
  const [tokErr, setTokErr] = useState<string | null>(null)

  useEffect(() => { startTourOnce('connect') }, [])

  const download = async () => {
    setBusy(true); setErr(null)
    try { await hub.exportSkills(false) } catch (e) { setErr(errMsg(e)) } finally { setBusy(false) }
  }
  const create = async () => {
    setTokErr(null)
    try { const r = await hub.createToken(name.trim(), 90); setSecret(r.secret); tokens.reload() } catch (e) { setTokErr(errMsg(e)) }
  }
  const revoke = async (id: number) => {
    setTokErr(null)
    try { await hub.revokeToken(id); tokens.reload() } catch (e) { setTokErr(errMsg(e)) }
  }
  const tok = secret ?? '<1단계에서 만든 토큰>'
  const command = tool === 'claude'
    ? `claude mcp add --scope user --transport http cen-ai-hub ${MCP_URL} --header "Authorization: Bearer ${tok}"`
    : `[mcp_servers.cen-ai-hub]\nurl = "${MCP_URL}"\nbearer_token_env_var = "CEN_AI_HUB_TOKEN"\n# 환경변수 CEN_AI_HUB_TOKEN 에 만든 토큰을 넣어 두세요`
  const active = tokens.data?.filter((t) => !t.revoked && !t.expired).length ?? 0

  return (
    <div className="space-y-5">
      <header className="hub-page-heading">
        <p>ITCEN AI HUB</p><h1>AI 도구에 연결하기</h1>
        <span>Claude Code·Codex 에서 AI HUB 의 승인된 도구를 바로 쓸 수 있게 연결합니다. 세 단계면 끝나요.</span>
        <div className="mt-3"><Btn small tone="ghost" onClick={() => startTour('connect', { stay: true })}>사용법 보기</Btn></div>
      </header>

      <div data-coach="connect-token"><Card title="1단계. 내 토큰 만들기">
        <p className="text-sm text-slate-400 mb-3">토큰은 “내 계정으로 도구를 쓰는 열쇠”입니다. 내가 쓸 수 있는 도구만 열립니다.</p>
        <div className="flex gap-2 flex-wrap items-end">
          <div className="flex-1 min-w-48"><label className="text-xs text-slate-400">이름 (어디에 쓸 토큰인지)</label><Input aria-label="토큰 이름" value={name} maxLength={64} onChange={(e) => setName(e.target.value)} /></div>
          <Btn disabled={!name.trim()} onClick={() => void create()}>토큰 만들기</Btn>
        </div>
        <ErrorNote message={tokErr} />
        {secret && <p role="status" className="mt-3 text-sm rounded-lg border border-amber-400/60 bg-amber-50/60 p-3">토큰을 만들었어요. 아래 2단계 명령에 이미 들어 있습니다. <b>이 화면을 벗어나면 다시 볼 수 없으니</b> 지금 복사하세요.</p>}
      </Card></div>

      <div data-coach="connect-cmd"><Card title="2단계. 명령 복사해서 붙여 넣기">
        <div className="flex gap-2 mb-3" role="group" aria-label="사용할 도구">
          <Btn small tone={tool === 'claude' ? 'primary' : 'ghost'} onClick={() => setTool('claude')}>Claude Code</Btn>
          <Btn small tone={tool === 'codex' ? 'primary' : 'ghost'} onClick={() => setTool('codex')}>Codex</Btn>
        </div>
        <Cmd text={command} disabled={!secret} />
        <p className="text-xs text-slate-400 mt-2">{tool === 'claude' ? '터미널(PowerShell)에 붙여 넣고 Enter. 끝나면 Claude Code(VS Code 는 창 새로 고침)를 새로 시작하세요. 어느 폴더에서 열어도 보이도록 내 계정 전체에 등록합니다.' : '~/.codex/config.toml 파일에 붙여 넣고 Codex 를 새로 시작하세요.'} 토큰을 만들기 전에는 복사 버튼이 꺼져 있습니다.</p>
      </Card></div>

      <Card title="3단계. AI 에게 말해 보기">
        <p className="text-sm text-slate-400">연결된 Claude 에게 이렇게 말해 보세요.</p>
        <p className="mt-2 text-sm rounded-lg bg-black/10 px-3 py-2">“이 오류 로그를 cen-log-analyzer 로 분석해줘.”</p>
        <p className="text-xs text-slate-400 mt-2">쓸 수 있는 도구 이름은 도구 화면의 영문 이름과 같습니다. 결과는 검토용 초안이며 개인정보·비밀번호는 입력하지 마세요.</p>
      </Card>

      <div data-coach="connect-list"><details className="hub-advanced"><summary>내가 만든 토큰 관리 ({active}개 사용 중)</summary>
        {!tokens.data?.length ? <p className="mt-3 text-sm text-slate-400">아직 만든 토큰이 없습니다.</p> : (
          <ul className="mt-3 divide-y divide-white/8 text-sm">
            {tokens.data.map((t) => (
              <li key={t.id} className="py-2 flex items-center gap-2 flex-wrap">
                <b>{t.name}</b><code className="text-xs">{t.prefix}</code>
                {t.revoked ? <Badge tone="idle">폐기됨</Badge> : t.expired ? <Badge tone="warn">만료됨</Badge> : <Badge tone="ok">사용 중</Badge>}
                <span className="text-xs text-slate-500">만료 {fmtTime(t.expiresAt)}{t.lastUsedAt ? ` · 마지막 사용 ${fmtTime(t.lastUsedAt)}` : ' · 사용 기록 없음'}</span>
                <span className="flex-1" />
                <Btn small tone="ghost" disabled={t.revoked} onClick={() => void revoke(t.id)}>폐기</Btn>
              </li>
            ))}
          </ul>
        )}
        <p className="text-xs text-slate-500 mt-3">토큰이 새어 나갔다면 바로 폐기하세요. 폐기하면 그 토큰으로는 더 이상 연결되지 않습니다.</p>
      </details></div>

      <details className="hub-advanced"><summary>다른 방법: 스킬 파일로 설치</summary>
        <p className="text-sm text-slate-400 my-3">승인된 도구의 지침(SKILL.md)을 내려받아 스킬 폴더에 풉니다. AI 가 내 PC 에서 직접 수행합니다.</p>
        <Btn disabled={busy} onClick={() => void download()}>{busy ? '만드는 중…' : '스킬 ZIP 내려받기'}</Btn>
        <ErrorNote message={err} />
        <div className="space-y-3 mt-4">
          <Cmd label="Claude Code · Windows (PowerShell)" text={'Expand-Archive .\\cen-ai-hub-skills.zip -DestinationPath "$HOME\\.claude\\skills" -Force'} />
          <Cmd label="Claude Code · macOS / Linux" text="unzip -o cen-ai-hub-skills.zip -d ~/.claude/skills" />
        </div>
      </details>
    </div>
  )
}
