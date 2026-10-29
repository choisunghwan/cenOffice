// Layout regression: canned AI/transcript responses; no paid AI calls or real microphone input.
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const fs=require('node:fs');const path=require('node:path');const assert=require('node:assert/strict');
const root=path.resolve(__dirname,'..');
const credentials=Object.fromEntries(fs.readFileSync(path.join(root,'backend/.local-credentials-meeting.txt'),'utf8').trim().split(/\r?\n/).map(l=>{const i=l.indexOf('=');return[l.slice(0,i),l.slice(i+1)]}));
const out=path.join(root,'docs/meeting-demo');fs.mkdirSync(out,{recursive:true});
(async()=>{
 const browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
 const page=await browser.newPage({viewport:{width:1280,height:720}});
 try{
  await page.routeWebSocket(/\/ws(?:\?|$)/,socket=>socket.close());
  const turn={id:'layout-test',username:'layout-fixture',question:'지금 나온 방안에 대한 의견은 어때?',answer:Array.from({length:24},(_,i)=>`의견 ${i+1}: 검토할 내용을 확인하고 다음 회의에서 결과를 공유하겠습니다.`).join('\n'),state:'PLAYING',message:'',createdAt:new Date().toISOString()};
  await page.route(/\/api\/meetings\/[^/]+\/assistant$/,r=>r.fulfill({json:{available:true,enabled:true,reason:'',turn,history:[turn]}}));
  await page.route(/\/transcript\/status$/,r=>r.fulfill({json:{consented:false,segmentCount:10,recording:['관리자']}}));
  await page.route(/\/transcript(?:\?.*)?$/,r=>r.fulfill({json:new URL(r.request().url()).searchParams.get('after')!=='0'?[]:Array.from({length:10},(_,i)=>({id:i+1,username:'user',displayName:'참석자',content:`발언 ${i+1}: 회의 기록이 충분한 높이에서 보여야 합니다.`,spokenAt:new Date().toISOString()}))}));
  await page.goto('http://localhost:5174');
  await page.locator('input[autocomplete="username"]').fill('admin');
  await page.locator('input[autocomplete="current-password"]').fill(credentials.OFFICE_ADMIN_PASSWORD);
  await page.locator('form').getByRole('button',{name:'로그인',exact:true}).click();
  const skip=page.getByRole('button',{name:'건너뛰기',exact:true});
  await skip.waitFor({timeout:2000}).catch(()=>{});if(await skip.isVisible())await skip.click();
  await page.locator('button[aria-label="회의"]').click();
  await page.getByText('화상회의 준비 완료',{exact:true}).waitFor({timeout:30000});
  // Keep this a layout fixture: do not establish media or disturb any active real meeting.
  await page.evaluate(async()=>{
   const url=performance.getEntriesByType('resource').map(e=>e.name).find(u=>u.includes('/livekit-client.js'));
   if(!url)throw new Error('LiveKit module not loaded');
   const {Room,ConnectionState}=await import(url);
   Room.prototype.connect=async function(){this.state=ConnectionState.Connected;this.emit('connectionStateChanged',this.state);};
   Room.prototype.disconnect=async function(){};
  });
  await page.getByLabel('회의 이름',{exact:true}).fill('회의 패널 레이아웃 검증');
  await page.getByRole('button',{name:'회의 만들기'}).click();
  await page.locator('.meeting-lobby .meeting-primary').click();
  await page.locator('.meeting-call').waitFor({timeout:30000});
  for(const [width,height]of [[1280,720],[1024,600],[850,600],[1440,900]]){
   await page.setViewportSize({width,height});
   await page.getByRole('tab',{name:/음성 기록/}).click();
   const log=page.locator('.meeting-chat-messages');
   await log.locator('.stt-line').first().waitFor();
   const bounds=await page.evaluate(()=>{
    const log=document.querySelector('.meeting-chat-messages').getBoundingClientRect();
    const footer=document.querySelector('.meeting-controls').getBoundingClientRect();
    return{logHeight:log.height,logBottom:log.bottom,footerTop:footer.top,footerBottom:footer.bottom,viewport:innerHeight};
   });
   assert.ok(bounds.logHeight>=160,`Transcript collapsed at ${width}x${height}: ${JSON.stringify(bounds)}`);
   assert.ok(bounds.logBottom<=bounds.footerTop+1,'Transcript overlaps meeting controls');
   assert.ok(bounds.footerBottom<=bounds.viewport+1,'Meeting controls outside viewport');
   await page.locator('.meeting-ai-callout').getByRole('button',{name:'말 멈추기',exact:true}).waitFor({state:'visible'});
   await page.screenshot({path:path.join(out,`layout-voice-${width}x${height}.png`)});
   await page.getByRole('tab',{name:/AI 센/}).click();
   const panel=page.getByRole('region',{name:'회의 AI 센'});await panel.waitFor();
   await panel.getByText('의견 24:',{exact:false}).scrollIntoViewIfNeeded();
   const answer=await panel.locator('.meeting-assistant-answer p').boundingBox();
   const scroll=await page.locator('.meeting-sidebar-panel').evaluate(e=>({scrollHeight:e.scrollHeight,clientHeight:e.clientHeight,scrollTop:e.scrollTop}));
   assert.ok(answer&&scroll.scrollHeight>scroll.clientHeight&&scroll.scrollTop>0,'Long AI answer must remain scrollable');
   const end=await page.locator('.meeting-sidebar-panel').evaluate(e=>{e.scrollTop=e.scrollHeight;return e.getBoundingClientRect().bottom;});
   const lastLine=await panel.locator('.meeting-assistant-answer p').boundingBox();
   assert.ok(lastLine.y+lastLine.height<=end+1,'The end of an AI answer must be reachable above meeting controls');
   await page.locator('.meeting-sidebar-panel').evaluate(e=>e.scrollTop=0);
   await panel.getByRole('button',{name:'말 멈추기',exact:true}).waitFor({state:'visible'});
   await page.screenshot({path:path.join(out,`layout-${width}x${height}.png`)});
  }
  await page.getByRole('button',{name:'전체 종료',exact:true}).click();
  await page.getByRole('button',{name:'전체 종료',exact:true}).last().click();
  console.log('PASS: transcript height, long AI answer scrolling, visible stop action, fixed meeting controls at 4 viewport sizes; no AI calls.');
 }catch(e){await page.screenshot({path:path.join(out,'layout-failure.png')}).catch(()=>{});console.error(e.message);process.exitCode=1;}
 finally{
  // Only terminate this script's explicitly named fixtures, including leftovers from failing layout runs.
  try{
   const {api,token}=await page.evaluate(async()=>{const m=await import('/src/api.ts');return{api:m.API_URL,token:m.tokenStore.get()}});
   if(token){const headers={Authorization:`Bearer ${token}`};const response=await page.request.get(`${api}/api/meetings`,{headers});
    if(response.ok())for(const m of await response.json())if(m.title==='회의 패널 레이아웃 검증'&&m.state==='OPEN')await page.request.post(`${api}/api/meetings/${m.id}/end`,{headers,data:{}});
   }
  }catch{}
  await browser.close();
 }
})();
