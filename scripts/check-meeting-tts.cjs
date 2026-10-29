// Explicit paid API smoke check. Never prints the API key or provider response body.
const fs=require('node:fs');const path=require('node:path');
const root=path.resolve(__dirname,'..');const credentials=path.join(root,'backend/.local-credentials.txt');
const settings={};if(fs.existsSync(credentials))for(const line of fs.readFileSync(credentials,'utf8').split(/\r?\n/)){const i=line.indexOf('=');if(i>0)settings[line.slice(0,i)]=line.slice(i+1).trim()}
const key=process.env.GEMINI_API_KEY||settings.GEMINI_API_KEY;
const model=process.env.MEETING_TTS_MODEL||settings.MEETING_TTS_MODEL||'gemini-3.8-flash-lite-tts';
(async()=>{
  if(!key)throw new Error('GEMINI_API_KEY is not configured');
  const r=await fetch('https://generativelanguage.googleapis.com/v1beta/interactions',{method:'POST',signal:AbortSignal.timeout(65000),headers:{'Content-Type':'application/json','x-goog-api-key':key},body:JSON.stringify({model,
    input:[{type:'user_input',content:[{type:'text',text:'안녕하세요. 회의에 참여하는 AI 센입니다.'}]}],
    response_format:{type:'audio',mime_type:'audio/wav'},generation_config:{speech_config:[{voice:process.env.MEETING_TTS_VOICE||'Kore'}]}})});
  console.log('TTS HTTP:',r.status,'model:',model);
  if(!r.ok){const e=await r.json().catch(()=>({}));console.log('Provider status:',e.error?.status||'unknown');process.exitCode=1;return;}
  const result=await r.json();const audio=(result.steps||[]).filter(s=>s.type==='model_output').flatMap(s=>s.content||[]).filter(s=>s.type==='audio').at(-1);
  const wav=Buffer.from(audio?.data||'','base64');
  if(wav.toString('ascii',0,4)!=='RIFF')throw new Error('Expected WAV response');
  const out=path.join(root,'backend/data/meeting-demo-runtime');fs.mkdirSync(out,{recursive:true});fs.writeFileSync(path.join(out,'tts-check.wav'),wav);
  console.log('PASS: Korean speech WAV generated, bytes:',wav.length);
})().catch(e=>{console.error(e.name==='TimeoutError'?'TTS timeout':'TTS check failed; inspect API configuration/network.');process.exitCode=1;});
