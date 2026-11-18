package dev.screenoff.recorder.remote

/** The two web pages, self-contained (no external files, works offline over Tailscale). */
object Pages {

    private const val STYLE = """
<style>
  :root { color-scheme: dark; --bg:#101317; --card:#1b2027; --line:#2a313b; --text:#e9edf2; --dim:#9aa5b1;
          --rec:#e53935; --armed:#ef6c00; --ok:#2e7d32; --accent:#4f8cff; }
  * { box-sizing: border-box; }
  body { margin:0; background:var(--bg); color:var(--text); font:16px/1.4 system-ui, -apple-system, Segoe UI, Roboto, sans-serif; }
  main { max-width: 760px; margin: 0 auto; padding: 16px; }
  h1 { font-size: 20px; margin: 4px 0 12px; }
  .card { background: var(--card); border: 1px solid var(--line); border-radius: 14px; padding: 14px; margin-bottom: 14px; }
  .dim { color: var(--dim); font-size: 14px; }
  button { font: inherit; border: 0; border-radius: 12px; padding: 14px 12px; color: #fff; background: #2b3440; cursor: pointer; width: 100%; }
  button.sel { outline: 3px solid var(--accent); }
  button:disabled { opacity: .5; }
  input { font: inherit; width: 100%; padding: 14px; border-radius: 12px; border: 1px solid var(--line); background: #0c0f12; color: var(--text); letter-spacing: .3em; text-align: center; }
  .grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
  @media (max-width: 480px) { .grid { grid-template-columns: 1fr; } }
</style>"""

    fun login(error: Boolean): String = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>ScreenOff Recorder</title>$STYLE</head>
<body><main>
  <h1>ScreenOff Recorder</h1>
  <form class="card" method="post" action="/login">
    <p>Enter the PIN shown in the app on the phone (Settings, Remote viewing).</p>
    ${if (error) "<p style=\"color:#ff8a80\">Wrong PIN. Try again.</p>" else ""}
    <input name="pin" inputmode="numeric" autocomplete="one-time-code" maxlength="12" autofocus required>
    <p><button type="submit" style="background:var(--accent)">Open</button></p>
  </form>
</main></body></html>"""

    val DASHBOARD = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>ScreenOff Recorder</title>$STYLE
<style>
  #stage { position: relative; width: 100%; background: #000; border-radius: 14px; overflow: hidden; aspect-ratio: 16 / 9; }
  #live { position: absolute; left: 50%; top: 50%; transform-origin: center; }
  #badge { position: absolute; left: 10px; top: 10px; padding: 4px 10px; border-radius: 999px; font-weight: 600; font-size: 14px; background: #0009; }
  #msg { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; color: var(--dim); }
  .row { display: flex; justify-content: space-between; gap: 10px; flex-wrap: wrap; }
</style></head>
<body><main>
  <div class="row"><h1>ScreenOff Recorder</h1><a class="dim" href="/logout">Log out</a></div>

  <div id="stage" class="card" style="padding:0">
    <img id="live" alt="Live view">
    <div id="msg">Connecting to the camera...</div>
    <div id="badge">...</div>
  </div>

  <div class="card">
    <div class="grid">
      <button id="m-CONTINUOUS" onclick="mode('CONTINUOUS')">Record everything</button>
      <button id="m-MOTION" onclick="mode('MOTION')">Record when something moves</button>
      <button id="m-STANDBY" onclick="mode('STANDBY')">Watch only</button>
    </div>
    <p><button id="keep" onclick="keep()" disabled>Keep this clip (never delete it automatically)</button></p>
    <div id="line" class="dim"></div>
  </div>

  <div class="card dim">
    The picture is live, about one second behind. Watching uses data on this device only while this page is open.
    The phone always shows its recording notification.
  </div>
</main>
<script>
const img = document.getElementById('live'), stage = document.getElementById('stage'), msg = document.getElementById('msg');
let rotation = 0, streamOk = false;

function layout() {
  const w = img.naturalWidth || 16, h = img.naturalHeight || 9;
  const side = rotation % 180 !== 0;
  stage.style.aspectRatio = side ? (h + ' / ' + w) : (w + ' / ' + h);
  const sw = stage.clientWidth, sh = stage.clientHeight;
  img.style.width = (side ? sh : sw) + 'px';
  img.style.height = (side ? sw : sh) + 'px';
  img.style.transform = 'translate(-50%, -50%) rotate(' + rotation + 'deg)';
}
function connect() {
  img.src = '/live.mjpg?t=' + Date.now();
}
img.onload = () => { streamOk = true; msg.style.display = 'none'; layout(); };
img.onerror = () => { streamOk = false; msg.style.display = 'flex'; msg.textContent = 'Camera not available. Retrying...'; setTimeout(connect, 3000); };
window.addEventListener('resize', layout);

function fmtBytes(b) { const u = ['B','KB','MB','GB','TB']; let i = 0; while (b >= 1024 && i < 4) { b /= 1024; i++; } return b.toFixed(1) + ' ' + u[i]; }
function fmtTime(ms) { const s = Math.max(0, Math.floor(ms / 1000)); const h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60), x = s % 60;
  return (h ? h + ':' + String(m).padStart(2, '0') : m) + ':' + String(x).padStart(2, '0'); }

async function refresh() {
  try {
    const r = await fetch('/api/status', { cache: 'no-store' });
    if (r.status === 401) { location.reload(); return; }
    const s = await r.json();
    if (s.rotation !== rotation) { rotation = s.rotation; layout(); }
    const badge = document.getElementById('badge');
    let text = 'Off', color = '#0009';
    if (s.state === 'RECORDING') { text = 'REC ' + (s.segmentStartedAtMs ? fmtTime(s.serverTimeMs - s.segmentStartedAtMs) : ''); color = 'var(--rec)'; }
    else if (s.state === 'ARMED' && s.mode === 'MOTION') { text = 'Watching for movement'; color = 'var(--armed)'; }
    else if (s.state === 'ARMED') { text = 'Live, not recording'; color = 'var(--ok)'; }
    else if (s.state === 'FINALIZING') { text = 'Saving clip'; }
    badge.textContent = text; badge.style.background = color;
    for (const m of ['CONTINUOUS', 'MOTION', 'STANDBY']) {
      const b = document.getElementById('m-' + m);
      b.classList.toggle('sel', s.mode === m);
      b.disabled = s.state === 'IDLE' || s.state === 'FINALIZING';
    }
    document.getElementById('keep').disabled = s.state !== 'RECORDING';
    const parts = [];
    if (s.state === 'IDLE') parts.push('The app is not running on the phone. Start it there in Standby to use this page.');
    if (s.battery !== null) parts.push('Battery ' + s.battery + '%' + (s.charging ? ', charging' : ', not charging'));
    parts.push(s.clips + ' clips, ' + fmtBytes(s.usedBytes) + ' of ' + fmtBytes(s.capBytes));
    parts.push('Sound ' + (s.audio ? 'on' : 'off'));
    if (s.error) parts.push('Last problem: ' + s.error);
    document.getElementById('line').textContent = parts.join('. ') + '.';
    if (!streamOk && s.state !== 'IDLE' && !img.src) connect();
  } catch (e) {
    document.getElementById('line').textContent = 'Cannot reach the phone. Check that Tailscale is on for both devices.';
  }
}
async function mode(m) { await fetch('/api/mode?m=' + m, { method: 'POST' }); refresh(); }
async function keep() { await fetch('/api/keep', { method: 'POST' }); refresh(); }

connect();
refresh();
setInterval(refresh, 2000);
</script>
</body></html>"""
}
