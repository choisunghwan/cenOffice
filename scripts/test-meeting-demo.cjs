// Two isolated real browser processes, synthetic camera/microphone for repeatable local smoke testing.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '..');
const credentials = Object.fromEntries(fs.readFileSync(path.join(root, 'backend/.local-credentials-meeting.txt'), 'utf8').trim().split(/\r?\n/).map(l => l.split('=')));
const out = path.join(root, 'docs/meeting-demo'); fs.mkdirSync(out, { recursive: true });
const args = ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream', '--auto-select-desktop-capture-source=Entire screen', '--autoplay-policy=no-user-gesture-required'];
(async () => {
  const browsers = [];
  const errors = [];
  try {
    for (const exe of ['C:/Program Files/Google/Chrome/Application/chrome.exe', 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe']) browsers.push(await chromium.launch({ executablePath: exe, headless: true, args }));
    const pages = [];
    for (let i=0; i<2; i++) {
      const ctx=await browsers[i].newContext({ viewport:{width:1440,height:960}, permissions:['camera','microphone','clipboard-read','clipboard-write'] });
      await ctx.addInitScript(() => {
        window.__meetingTestPeers=[];
        const Original=window.RTCPeerConnection;
        window.RTCPeerConnection=class extends Original { constructor(...a) {super(...a);window.__meetingTestPeers.push(this);} };
      });
      const page=await ctx.newPage(); pages.push(page);
      page.on('pageerror', e=>errors.push(e.message));
      await page.goto('http://localhost:5174');
      await page.getByPlaceholder('아이디', {exact:true}).fill(i===0?'admin':'user');
      await page.getByPlaceholder('비밀번호', {exact:true}).fill(credentials[i===0?'OFFICE_ADMIN_PASSWORD':'OFFICE_USER_PASSWORD']);
      await page.locator('form').getByRole('button',{name:'로그인',exact:true}).click();
      await page.locator('nav button').filter({hasText:'회의'}).click();
      await page.getByText('화상회의 준비 완료',{exact:true}).waitFor({timeout:30000});
    }
    const [host,guest]=pages;
    await host.screenshot({path:path.join(out,'01-meetings.png')});
    const created=host.waitForResponse(r=>r.url().endsWith('/api/meetings') && r.request().method()==='POST');
    await host.getByLabel('회의 이름',{exact:true}).fill('CEN OFFICE · 로컬 협업 시연');
    await host.getByRole('button',{name:'회의 만들기'}).click();
    const response=await created; assert.equal(response.status(),201);
    const meeting=await response.json();
    await host.getByLabel('카메라 켜고 참여').check();
    await host.getByLabel('마이크 켜고 참여').check();
    await host.getByRole('button',{name:'회의 참여하기'}).click();
    await host.getByText('LIVE',{exact:true}).waitFor({timeout:30000});
    await guest.getByLabel('참여 코드',{exact:true}).fill(meeting.code);
    await guest.getByRole('button',{name:/^참여하기/}).click();
    await guest.getByLabel('카메라 켜고 참여').check();
    await guest.getByRole('button',{name:'회의 참여하기'}).click();
    await guest.getByText('LIVE',{exact:true}).waitFor({timeout:30000});
    for (const p of pages) {
      await p.waitForFunction(()=>document.querySelectorAll('.meeting-people > span').length===2,{},{timeout:30000});
      await p.waitForFunction(()=>[...document.querySelectorAll('.meeting-video-grid video')].filter(v=>v.videoWidth>0 && v.readyState>=2).length>=2,{},{timeout:30000});
    }
    await guest.waitForFunction(async()=> {
      for(const peer of window.__meetingTestPeers) {
        const stats=await peer.getStats();
        for(const stat of stats.values()) if(stat.type==='inbound-rtp' && stat.kind==='audio' && stat.bytesReceived>0) return true;
      }
      return false;
    },{},{timeout:20000});
    await host.getByRole('textbox',{name:'회의 메시지'}).fill('두 브라우저의 실시간 영상과 대화가 연결되었습니다.');
    await host.getByRole('button',{name:'메시지 전송'}).click();
    await guest.getByText('두 브라우저의 실시간 영상과 대화가 연결되었습니다.',{exact:true}).waitFor({timeout:15000});
    await guest.screenshot({path:path.join(out,'02-two-participants.png')});
    await host.getByRole('button',{name:'화면 공유',exact:false}).click();
    await guest.locator('.meeting-share-stage video').waitFor({timeout:20000});
    await guest.waitForFunction(()=>document.querySelector('.meeting-share-stage video')?.videoWidth>0,{},{timeout:20000});
    await guest.screenshot({path:path.join(out,'03-screen-share.png')});
    await host.getByRole('button',{name:'공유 중지',exact:false}).click();
    await host.getByRole('button',{name:'전체 종료',exact:true}).click();
    await host.getByRole('dialog').getByRole('button',{name:'전체 종료',exact:true}).click();
    for(const p of pages) await p.getByRole('heading',{name:'함께 모이면, 일이 시작됩니다.'}).waitFor({timeout:20000});
    assert.equal(errors.length,0,errors.join('\n'));
    console.log('PASS: Chrome + Edge, authenticated admission, 2 remote video streams, inbound audio RTP bytes, meeting chat, screen sharing, host termination; synthetic devices.');
  } catch(e) {
    for(let i=0;i<browsers.length;i++) for(const c of browsers[i].contexts()) for(const p of c.pages()) {
      await p.screenshot({path:path.join(out,`failure-${i}.png`)}).catch(()=>{});
      console.log(`Browser ${i}: `+(await p.locator('body').innerText()).slice(-3000));
    }
    console.error(e.message);process.exitCode=1;
  } finally { for(const b of browsers) await b.close(); }
})();
