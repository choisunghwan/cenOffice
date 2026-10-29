// Speech-recognition events are synthetic; LLM/TTS and LiveKit RTP are real.
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const fs=require('node:fs');const path=require('node:path');const assert=require('node:assert/strict');
const root=path.resolve(__dirname,'..');
const credentials=Object.fromEntries(fs.readFileSync(path.join(root,'backend/.local-credentials-meeting.txt'),'utf8').trim().split(/\r?\n/).map(l=>{const i=l.indexOf('=');return[l.slice(0,i),l.slice(i+1)]}));
const out=path.join(root,'docs/meeting-demo');fs.mkdirSync(out,{recursive:true});
const args=['--use-fake-ui-for-media-stream','--use-fake-device-for-media-stream','--autoplay-policy=no-user-gesture-required'];
async function audioStreams(page){return page.evaluate(async()=>{
  const streams=[];
  for(const peer of window.__voicePeers){for(const s of (await peer.getStats()).values())if(s.type==='inbound-rtp'&&s.kind==='audio'&&s.bytesReceived>100)streams.push(String(s.ssrc));}
  return streams;
});}
(async()=>{
  const browsers=[];const pages=[];const errors=[];
  try{
    for(const exe of ['C:/Program Files/Google/Chrome/Application/chrome.exe','C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe']){
      const browser=await chromium.launch({executablePath:exe,headless:true,args});browsers.push(browser);
      const ctx=await browser.newContext({viewport:{width:1440,height:960},permissions:['camera','microphone']});
      await ctx.addInitScript(()=>{
        const Original=window.RTCPeerConnection;window.__voicePeers=[];
        window.RTCPeerConnection=class extends Original{constructor(...args){super(...args);window.__voicePeers.push(this)}};
        window.SpeechRecognition=class{start(){window.__voiceRecognition=this}stop(){}};
      });
      const page=await ctx.newPage();pages.push(page);page.on('pageerror',e=>errors.push(e.message));
      await page.goto(process.env.MEETING_WEB_URL||'http://localhost:5174');
      const user=pages.length===1?'admin':'user';
      await page.locator('input[autocomplete="username"]').fill(user);
      await page.locator('input[autocomplete="current-password"]').fill(credentials[user==='admin'?'OFFICE_ADMIN_PASSWORD':'OFFICE_USER_PASSWORD']);
      await page.locator('form').getByRole('button',{name:'로그인',exact:true}).click();
      const skip=page.getByRole('button',{name:'건너뛰기',exact:true});await skip.waitFor({timeout:2000}).catch(()=>{});if(await skip.isVisible())await skip.click();
      await page.locator('button[aria-label="회의"]').click();
      await page.getByText('화상회의 준비 완료',{exact:true}).waitFor({timeout:30000});
    }
    const [host,guest]=pages;
    const created=host.waitForResponse(r=>r.url().endsWith('/api/meetings')&&r.request().method()==='POST');
    await host.getByLabel('회의 이름',{exact:true}).fill('센 음성 참가자 검증');
    await host.getByRole('button',{name:'회의 만들기'}).click();const meeting=await(await created).json();
    await host.getByLabel('마이크 켜고 참여').check();
    await host.locator('.meeting-lobby .meeting-primary').click();
    await host.getByText('LIVE',{exact:true}).waitFor({timeout:30000});
    await guest.getByLabel('참여 코드',{exact:true}).fill(meeting.code);
    await guest.getByRole('button',{name:/^참여하기/}).click();
    await guest.locator('.meeting-lobby .meeting-primary').click();
    await guest.getByText('LIVE',{exact:true}).waitFor({timeout:30000});
    for(const p of pages)await p.getByRole('tab',{name:'AI 센',exact:true}).click();
    const panel=host.getByRole('region',{name:'회의 AI 센'});
    await panel.getByRole('button',{name:'AI 센 켜기',exact:true}).click({timeout:15000});
    await guest.getByText('질문하면 회의 내용을 참고해 의견을 드려요.',{exact:true}).waitFor({timeout:10000});
    // Stabilize the existing host microphone before distinguishing the additional AI stream.
    await guest.waitForFunction(async()=>{
      for(const peer of window.__voicePeers)for(const s of (await peer.getStats()).values())if(s.type==='inbound-rtp'&&s.kind==='audio'&&s.bytesReceived>1000)return true;
      return false;
    },{},{timeout:15000});
    const baseline=await Promise.all(pages.map(audioStreams));
    await panel.getByRole('button',{name:'센에게 질문',exact:true}).click();
    await host.waitForFunction(()=>!!window.__voiceRecognition?.onresult);
    await host.evaluate(()=>window.__voiceRecognition.onresult({resultIndex:0,results:[Object.assign([{transcript:'센아, 짧은 팀 회의에서 의견이 갈릴 때 어떻게 결정하면 좋을까? 세 문장으로 답해 줘.'}],{isFinal:true})]}));
    const askedAt=Date.now();
    await panel.getByRole('button',{name:'질문 완료',exact:true}).click();
    await panel.getByRole('button',{name:'말 멈추기',exact:true}).waitFor({timeout:10000});
    let heard=false;
    for(let i=0;i<180;i++){
      const alerts=await panel.locator('[role="alert"]').allTextContents();const text=await panel.innerText();
      if(alerts.length||text.includes('답변을 만들지 못했습니다')||text.includes('목소리를 만들지 못했습니다'))throw new Error('AI 결과: '+text);
      const current=await Promise.all(pages.map(audioStreams));
      if(current.every((streams,index)=>streams.length>baseline[index].length&&streams.some(ssrc=>!baseline[index].includes(ssrc)))){heard=true;break;}
      await host.waitForTimeout(1000);
    }
    assert.ok(heard,'Both browsers must receive a new assistant audio RTP stream');
    console.log('Both browsers received AI audio after',((Date.now()-askedAt)/1000).toFixed(1),'seconds');
    await host.screenshot({path:path.join(out,'04-cen-voice.png')});
    await guest.screenshot({path:path.join(out,'05-cen-remote-voice.png')});
    await guest.getByRole('button',{name:'말 멈추기',exact:true}).click();
    await host.waitForFunction(()=>!document.querySelector('.meeting-people')?.textContent?.includes('센'),{},{timeout:10000});
    await panel.getByRole('button',{name:'센에게 질문',exact:true}).waitFor({timeout:10000});
    assert.ok((await panel.locator('.meeting-assistant-answer p').last().innerText()).length>0,'Text remains after stopping');
    // Exercise the other exit path too: a second typed question finishes naturally and permits another turn.
    await panel.getByLabel('센에게 할 질문',{exact:true}).fill('센아, 회의 참석자들에게 인사만 짧게 해 줘.');
    const second=host.waitForResponse(r=>r.url().endsWith('/assistant/ask')&&r.request().method()==='POST');
    await panel.getByRole('button',{name:'질문 보내기',exact:true}).click();
    const response=await second;assert.equal(response.status(),202);
    const auth=(await response.request().allHeaders()).authorization;
    const stateUrl=response.url().replace(/\/ask$/,'');
    let completed=false;
    for(let i=0;i<100;i++){
      const status=await host.request.get(stateUrl,{headers:{Authorization:auth}});const state=await status.json();
      if(state.turn?.state==='DONE'){completed=true;break;}
      if(['FAILED','TEXT_ONLY','STOPPED'].includes(state.turn?.state))throw new Error('Second turn did not finish with audio: '+state.turn.state);
      await host.waitForTimeout(1000);
    }
    assert.ok(completed,'A naturally completed voice answer must release the next question');
    await panel.getByRole('button',{name:'센에게 질문',exact:true}).waitFor({timeout:10000});
    assert.equal(errors.length,0,errors.join('\n'));
    await host.getByRole('button',{name:'전체 종료',exact:true}).click();
    await host.getByRole('button',{name:'전체 종료',exact:true}).last().click();
    console.log('PASS: synthetic STT and typed questions -> real AI/TTS -> assistant RTP received by Chrome and Edge -> guest stop -> text retained -> natural completion and next-question readiness; no page errors.');
  }catch(e){
    for(let i=0;i<pages.length;i++){await pages[i].screenshot({path:path.join(out,`voice-failure-${i}.png`)}).catch(()=>{});console.log(await pages[i].locator('.meeting-assistant').innerText().catch(()=>''));}
    console.error(e.message);process.exitCode=1;
  }finally{for(const b of browsers)await b.close();}
})();
