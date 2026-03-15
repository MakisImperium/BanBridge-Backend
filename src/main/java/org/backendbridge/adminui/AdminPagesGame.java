package org.backendbridge.adminui;

import org.backendbridge.repo.AdminRepository;
import org.backendbridge.repo.MetricsRepository;

import java.util.List;

import static org.backendbridge.adminui.AdminComponents.*;
import static org.backendbridge.adminui.AdminIcons.*;
import static org.backendbridge.adminui.AdminPageChrome.pageEndWithAppScript;
import static org.backendbridge.adminui.AdminPageChrome.pageStart;
import static org.backendbridge.adminui.AdminUiUtil.*;

final class AdminPagesGame {

    private AdminPagesGame() {}

    static String players(
            String serverName,
            Lang lang,
            long totalPlayers,
            long playersWithStats,
            long activeBans,
            long totalBans,
            String serverOptionsHtml,
            String rowsHtml
    ) {
        String tTitle = (lang == Lang.DE) ? "Spieler" : "Players";
        String tSubtitle = (lang == Lang.DE) ? "Übersicht (klick für Details)" : "Players overview (click a player for details)";
        String tBroadcast = (lang == Lang.DE) ? "Broadcast" : "Broadcast";
        String tMessage = (lang == Lang.DE) ? "Nachricht" : "Message";
        String tSend = (lang == Lang.DE) ? "Senden" : "Send";
        String tTarget = (lang == Lang.DE) ? "Server wählen" : "Select server";
        String tAllServers = (lang == Lang.DE) ? "Alle Server" : "All servers";

        StringBuilder html = new StringBuilder(170_000);
        html.append(pageStart(tTitle + " • " + esc(serverName)));
        html.append(appShellStart("players", serverName, lang));
        html.append(heroCenter(serverName, tSubtitle));

        String kTotalPlayers = (lang == Lang.DE) ? "Spieler gesamt" : "Total Players";
        String kWithStats = (lang == Lang.DE) ? "Spieler mit Stats" : "Players w/ Stats";
        String kActiveBans = (lang == Lang.DE) ? "Aktive Bans" : "Active Bans";
        String kTotalBans = (lang == Lang.DE) ? "Bans gesamt" : "Total Bans";

        html.append("""
            <section class="card bb-reveal" id="bbPlayersRoot">
              <div class="cardHead"><div><b>KPIs</b></div></div>
              <div class="pad">
                <div class="mono" style="display:flex; flex-wrap:wrap; gap:10px;">
                  <span class="badge">%s: <span id="bbPlayersTotal">%d</span></span>
                  <span class="badge">%s: <span id="bbPlayersWithStats">%d</span></span>
                  <span class="badge">%s: <span id="bbPlayersActiveBans">%d</span></span>
                  <span class="badge">%s: <span id="bbPlayersTotalBans">%d</span></span>
                </div>
              </div>
            </section>
            """.formatted(
                esc(kTotalPlayers), totalPlayers,
                esc(kWithStats), playersWithStats,
                esc(kActiveBans), activeBans,
                esc(kTotalBans), totalBans
        ));

        html.append("""
            <section class="card bb-reveal">
              <div class="cardHead"><div><b>%s</b></div></div>
              <div class="pad">
                <form method="post" action="/admin/players/broadcast" class="form" style="max-width:720px">
                  <select class="inp" name="serverKey" required>
                    <option value="__all__">%s</option>
                    %s
                  </select>
                  <textarea class="inp" name="message" rows="4" placeholder="%s" required></textarea>
                  <div style="display:flex; justify-content:flex-end">
                    <button class="btn primary" type="submit">%s</button>
                  </div>
                </form>
              </div>
            </section>
            """.formatted(
                esc(tBroadcast),
                esc(tAllServers),
                serverOptionsHtml == null ? "" : serverOptionsHtml,
                escAttr(tMessage),
                esc(tSend)
        ));

        html.append("""
            <div class="card bb-reveal bb-tableCard">
              <div class="cardHead">
                <div><b>%s</b></div>
                <div style="display:flex; gap:10px; align-items:center; flex-wrap:wrap">
                  <div class="mono" style="opacity:.75">%s</div>
                  <div class="bb-tableTools" data-bb-tabletools></div>
                </div>
              </div>
              <div class="pad" style="padding:0">
                <table>
                  <thead>
                    <tr>
                      <th>XUID</th>
                      <th>Name</th>
                      <th>Status</th>
                      <th>%s</th>
                      <th>%s</th>
                      <th>Kills</th>
                      <th>Deaths</th>
                      <th>%s</th>
                      <th>%s</th>
                    </tr>
                  </thead>
                  <tbody id="bbPlayersRows">
                    %s
                  </tbody>
                </table>
              </div>
            </div>
            """.formatted(
                esc(tTitle),
                esc(tTarget),
                esc((lang == Lang.DE) ? "Server" : "Server"),
                esc((lang == Lang.DE) ? "Spielzeit" : "Playtime"),
                esc((lang == Lang.DE) ? "Zuletzt gesehen" : "Last Seen"),
                esc((lang == Lang.DE) ? "Stats aktualisiert" : "Stats Updated"),
                rowsHtml == null ? "" : rowsHtml
        ));

        html.append("""
            <script>
              (function(){
                const rowsEl = document.getElementById('bbPlayersRows');
                if(!rowsEl) return;

                async function reloadPlayers(){
                  const r = await fetch('/admin/api/live/players', { headers: { 'Accept':'application/json' } });
                  if(!r.ok) throw new Error('HTTP ' + r.status);
                  const j = await r.json();

                  rowsEl.innerHTML = j.rowsHtml || '';

                  const total = document.getElementById('bbPlayersTotal');
                  const withStats = document.getElementById('bbPlayersWithStats');
                  const activeBans = document.getElementById('bbPlayersActiveBans');
                  const totalBans = document.getElementById('bbPlayersTotalBans');

                  if(total) total.textContent = String(j.totalPlayers ?? 0);
                  if(withStats) withStats.textContent = String(j.playersWithStats ?? 0);
                  if(activeBans) activeBans.textContent = String(j.activeBans ?? 0);
                  if(totalBans) totalBans.textContent = String(j.totalBans ?? 0);
                }

                try{
                  const es = new EventSource('/admin/api/live/stream');
                  es.addEventListener('invalidate', (ev) => {
                    try{
                      const msg = JSON.parse(ev.data || '{}');
                      const targets = Array.isArray(msg.targets) ? msg.targets : [];
                      if(targets.includes('players')){
                        reloadPlayers().catch(()=>{});
                      }
                    }catch(e){}
                  });
                }catch(e){}
              })();
            </script>
            """);

        html.append(appShellEnd());
        html.append(pageEndWithAppScript());
        return html.toString();
    }

    static String bans(String serverName, Lang lang, long totalPlayers, long activeBans, long revokedBans, long totalBans, String rowsHtml) {
        String tTitle = "Bans";
        String tSubtitle = (lang == Lang.DE) ? "Übersicht (live)" : "Overview (live)";

        StringBuilder html = new StringBuilder(120_000);
        html.append(pageStart(tTitle + " • " + esc(serverName)));
        html.append(appShellStart("bans", serverName, lang));
        html.append(heroCenter(tTitle, tSubtitle));

        String kTotalPlayers = (lang == Lang.DE) ? "Spieler gesamt" : "Total Players";
        String kActiveBans = (lang == Lang.DE) ? "Aktive Bans" : "Active Bans";
        String kRevoked = (lang == Lang.DE) ? "Aufgehoben" : "Revoked Bans";
        String kTotalBans = (lang == Lang.DE) ? "Bans gesamt" : "Total Bans";

        html.append("""
            <section class="card bb-reveal" id="bbBansRoot">
              <div class="cardHead"><div><b>KPIs</b></div></div>
              <div class="pad">
                <div class="mono" style="display:flex; flex-wrap:wrap; gap:10px;">
                  <span class="badge">%s: <span id="bbBansPlayers">%d</span></span>
                  <span class="badge">%s: <span id="bbBansActive">%d</span></span>
                  <span class="badge">%s: <span id="bbBansRevoked">%d</span></span>
                  <span class="badge">%s: <span id="bbBansTotal">%d</span></span>
                </div>
              </div>
            </section>
            """.formatted(
                esc(kTotalPlayers), totalPlayers,
                esc(kActiveBans), activeBans,
                esc(kRevoked), revokedBans,
                esc(kTotalBans), totalBans
        ));

        html.append("""
            <div class="card bb-reveal bb-tableCard">
              <div class="cardHead">
                <div><b>%s</b></div>
                <div style="display:flex; gap:10px; align-items:center; flex-wrap:wrap">
                  <div class="bb-tableTools" data-bb-tabletools></div>
                </div>
              </div>
              <div class="pad" style="padding:0">
                <table>
                  <thead>
                    <tr>
                      <th>ban_id</th>
                      <th>xuid</th>
                      <th>%s</th>
                      <th>created_at</th>
                      <th>expires_at</th>
                      <th>revoked_at</th>
                      <th>updated_at</th>
                    </tr>
                  </thead>
                  <tbody id="bbBansRows">
                    %s
                  </tbody>
                </table>
              </div>
            </div>
            """.formatted(
                esc(tTitle),
                esc((lang == Lang.DE) ? "Grund" : "reason"),
                rowsHtml == null ? "" : rowsHtml
        ));

        html.append("""
            <script>
              (function(){
                const rowsEl = document.getElementById('bbBansRows');
                if(!rowsEl) return;

                async function reloadBans(){
                  const r = await fetch('/admin/api/live/bans', { headers: { 'Accept':'application/json' } });
                  if(!r.ok) throw new Error('HTTP ' + r.status);
                  const j = await r.json();

                  rowsEl.innerHTML = j.rowsHtml || '';

                  const totalPlayers = document.getElementById('bbBansPlayers');
                  const activeBans = document.getElementById('bbBansActive');
                  const revokedBans = document.getElementById('bbBansRevoked');
                  const totalBans = document.getElementById('bbBansTotal');

                  if(totalPlayers) totalPlayers.textContent = String(j.totalPlayers ?? 0);
                  if(activeBans) activeBans.textContent = String(j.activeBans ?? 0);
                  if(revokedBans) revokedBans.textContent = String(j.revokedBans ?? 0);
                  if(totalBans) totalBans.textContent = String(j.totalBans ?? 0);
                }

                try{
                  const es = new EventSource('/admin/api/live/stream');
                  es.addEventListener('invalidate', (ev) => {
                    try{
                      const msg = JSON.parse(ev.data || '{}');
                      const targets = Array.isArray(msg.targets) ? msg.targets : [];
                      if(targets.includes('bans')){
                        reloadBans().catch(()=>{});
                      }
                    }catch(e){}
                  });
                }catch(e){}
              })();
            </script>
            """);

        html.append(appShellEnd());
        html.append(pageEndWithAppScript());
        return html.toString();
    }

    static String serverStats(String serverName, Lang lang, String serverKeyOrNull, MetricsRepository.Metrics latest) {
        String tTitle = "Server Performance";
        String tSubtitle = "Monitoring dashboard (auto-updates)";

        StringBuilder html = new StringBuilder(270_000);
        html.append(pageStart(tTitle + " • " + esc(serverName)));
        html.append(appShellStart("stats", serverName, lang));
        html.append(heroCenter(tTitle, tSubtitle));

        String sk = serverKeyOrNull == null ? "" : serverKeyOrNull;

        html.append("""
            <section class="card bb-reveal" id="bbStatsRoot" data-server-key="%s" data-lang="%s">
              <div class="cardHead">
                <div style="display:flex; flex-direction:column; gap:2px">
                  <div><b>Overview</b></div>
                  <div class="mono" style="opacity:.75">
                    Server Key: <span class="mono">%s</span>
                    <span style="margin-left:10px; opacity:.75" id="bbLiveStatus">live: connecting…</span>
                  </div>
                </div>
                <div class="mono" style="opacity:.75">updates: SSE (fallback poll ~5s)</div>
              </div>

              <div class="pad">
                <div class="bb-dashGrid">
                  <div class="bb-gaugeCard">
                    <div class="bb-gaugeTop">
                      <div class="bb-gaugeTitle">CPU Utilization</div>
                      <div class="bb-gaugeBadge" id="bbCpuBadge">Service: ?</div>
                    </div>
                    <div class="bb-gaugeRow">
                      <canvas class="bb-gauge" id="bbGaugeCpu" width="200" height="130"></canvas>
                      <div class="bb-gaugeValue">
                        <div class="bb-gaugeBig" id="bbCpuPct">-</div>
                        <div class="mono" id="bbCpuHint">cpuLoad (0..1)</div>
                      </div>
                    </div>
                  </div>

                  <div class="bb-gaugeCard">
                    <div class="bb-gaugeTop">
                      <div class="bb-gaugeTitle">RAM Used</div>
                      <div class="bb-gaugeBadge" id="bbRamBadge">Service: ?</div>
                    </div>
                    <div class="bb-gaugeRow">
                      <canvas class="bb-gauge" id="bbGaugeRam" width="200" height="130"></canvas>
                      <div class="bb-gaugeValue">
                        <div class="bb-gaugeBig" id="bbRamPct">-</div>
                        <div class="mono" id="bbRamHint">- / - MB</div>
                      </div>
                    </div>
                  </div>

                  <div class="bb-kpiCard">
                    <div class="bb-kpiTitle">Players</div>
                    <div class="bb-kpiBig"><span id="bbPlayersNow">-</span> <span class="mono">/</span> <span id="bbPlayersMax">-</span></div>
                    <div class="mono">Online / Max</div>
                  </div>

                  <div class="bb-kpiCard">
                    <div class="bb-kpiTitle">TPS</div>
                    <div class="bb-kpiBig" id="bbTpsNow">-</div>
                    <div class="mono">Ticks per second</div>
                  </div>

                  <div class="bb-kpiCard">
                    <div class="bb-kpiTitle">Bandwidth</div>
                    <div class="bb-kpiBig"><span id="bbRxNow">-</span><span class="mono"> / </span><span id="bbTxNow">-</span></div>
                    <div class="mono">RX / TX (kbps)</div>
                  </div>

                  <div class="bb-kpiCard">
                    <div class="bb-kpiTitle">Updated</div>
                    <div class="bb-kpiBig" id="bbUpdatedAt">-</div>
                    <div class="mono">Last metrics timestamp</div>
                  </div>
                </div>

                <div class="bb-chartGrid">
                  <div class="bb-chartCard">
                    <div class="bb-chartHead"><b>Players Online</b><span class="mono" style="opacity:.75">history</span></div>
                    <canvas class="bb-chartCanvas" id="bbChartPlayers" width="900" height="220"></canvas>
                  </div>
                  <div class="bb-chartCard">
                    <div class="bb-chartHead"><b>TPS</b><span class="mono" style="opacity:.75">history</span></div>
                    <canvas class="bb-chartCanvas" id="bbChartTps" width="900" height="220"></canvas>
                  </div>
                  <div class="bb-chartCard">
                    <div class="bb-chartHead"><b>CPU Load</b><span class="mono" style="opacity:.75">history</span></div>
                    <canvas class="bb-chartCanvas" id="bbChartCpu" width="900" height="220"></canvas>
                  </div>
                  <div class="bb-chartCard">
                    <div class="bb-chartHead"><b>RAM Used (MB)</b><span class="mono" style="opacity:.75">history</span></div>
                    <canvas class="bb-chartCanvas" id="bbChartRam" width="900" height="220"></canvas>
                  </div>
                </div>

                <div class="mono" id="bbNoMetrics" style="display:none; margin-top:14px; opacity:.75;">
                  No metrics yet. Client must POST <span class="mono">/api/server/metrics</span>
                </div>
              </div>
            </section>
            """.formatted(escAttr(sk), escAttr(lang.cookieValue()), esc(sk)));

        String seed = seedJson(serverKeyOrNull, latest);
        html.append("<script type=\"application/json\" id=\"bbStatsSeed\">").append(esc(seed)).append("</script>");

        html.append("""
            <script>
              (function(){
                const root = document.getElementById('bbStatsRoot');
                if(!root) return;

                const sk = root.getAttribute('data-server-key') || '';
                const lang = (root.getAttribute('data-lang') || 'de').toLowerCase();
                const liveStatus = document.getElementById('bbLiveStatus');

                function el(id){ return document.getElementById(id); }
                function clamp(v,min,max){ return Math.max(min, Math.min(max, v)); }
                function pad2(n){ return String(n).padStart(2,'0'); }

                function fmt2(x){
                  const n = Number(x);
                  return Number.isFinite(n) ? n.toFixed(2) : '-';
                }

                function fmtDateTime(iso){
                  if(!iso) return '-';
                  const d = new Date(iso);
                  if(Number.isNaN(d.getTime())) return String(iso);

                  const yyyy = d.getFullYear();
                  const MM = pad2(d.getMonth() + 1);
                  const dd = pad2(d.getDate());
                  const mm = pad2(d.getMinutes());

                  if(lang === 'de'){
                    const HH = pad2(d.getHours());
                    return `${dd}.${MM}.${yyyy} ${HH}:${mm}`;
                  }

                  let h = d.getHours();
                  const ampm = (h >= 12) ? 'PM' : 'AM';
                  h = h % 12;
                  if(h === 0) h = 12;
                  const hh = pad2(h);

                  return `${yyyy}.${MM}.${dd} ${hh}:${mm} ${ampm}`;
                }

                function serviceBadge(id, ok){
                  const b = el(id);
                  if(!b) return;
                  b.textContent = ok ? 'Service: OK' : 'Service: NO DATA';
                  b.style.borderColor = ok ? 'rgba(34,197,94,.35)' : 'rgba(245,158,11,.35)';
                  b.style.background = ok ? 'rgba(34,197,94,.12)' : 'rgba(245,158,11,.14)';
                  b.style.color = ok ? 'rgba(21,128,61,.95)' : 'rgba(180,83,9,.95)';
                }

                function drawGauge(canvasId, value01, color){
                  const c = el(canvasId);
                  if(!c) return;
                  const ctx = c.getContext('2d');
                  const w = c.width, h = c.height;
                  ctx.clearRect(0,0,w,h);

                  const cx = w/2, cy = h*0.92;
                  const r = Math.min(w*0.42, h*0.90);
                  const start = Math.PI;
                  const end = 2*Math.PI;

                  ctx.lineWidth = 12;
                  ctx.lineCap = 'round';
                  ctx.strokeStyle = 'rgba(15,23,42,.10)';
                  ctx.beginPath();
                  ctx.arc(cx, cy, r, start, end);
                  ctx.stroke();

                  const v = clamp(value01, 0, 1);
                  ctx.strokeStyle = color;
                  ctx.beginPath();
                  ctx.arc(cx, cy, r, start, start + (end-start)*v);
                  ctx.stroke();
                }

                function drawLineChart(canvasId, points, getY, color){
                  const c = el(canvasId);
                  if(!c) return;
                  const ctx = c.getContext('2d');
                  const w = c.width, h = c.height;
                  ctx.clearRect(0,0,w,h);

                  const pad = 26;
                  const xs = points.map(p => Date.parse(p.atIso));
                  const ys = points.map(p => {
                    const y = getY(p);
                    const n = Number(y);
                    return Number.isFinite(n) ? n : null;
                  }).filter(v => v !== null);

                  ctx.strokeStyle = 'rgba(15,23,42,.07)';
                  ctx.lineWidth = 1;
                  for(let i=0;i<=4;i++){
                    const y = pad + (h - pad*2)*(i/4);
                    ctx.beginPath(); ctx.moveTo(pad, y); ctx.lineTo(w-pad, y); ctx.stroke();
                  }

                  if(points.length < 2 || ys.length < 1){
                    ctx.fillStyle = 'rgba(15,23,42,.55)';
                    ctx.font = '12px ui-monospace, Consolas, monospace';
                    ctx.fillText('no data', pad, pad+12);
                    return;
                  }

                  const minX = Math.min.apply(null, xs);
                  const maxX = Math.max.apply(null, xs);
                  let minY = Math.min.apply(null, ys);
                  let maxY = Math.max.apply(null, ys);
                  if(!Number.isFinite(minY) || !Number.isFinite(maxY) || minY === maxY){
                    minY = (Number.isFinite(minY) ? (minY-1) : 0);
                    maxY = (Number.isFinite(maxY) ? (maxY+1) : 1);
                  }

                  function mapX(t){
                    const den = (maxX - minX) || 1;
                    return pad + ((t - minX) / den) * (w - pad*2);
                  }
                  function mapY(v){
                    const den = (maxY - minY) || 1;
                    return (h - pad) - ((v - minY) / den) * (h - pad*2);
                  }

                  ctx.strokeStyle = color;
                  ctx.lineWidth = 2.5;
                  ctx.lineJoin = 'round';
                  ctx.lineCap = 'round';
                  ctx.beginPath();
                  let started = false;
                  for(const p of points){
                    const t = Date.parse(p.atIso);
                    const yv = Number(getY(p));
                    if(!Number.isFinite(t) || !Number.isFinite(yv)) continue;
                    const x = mapX(t);
                    const y = mapY(yv);
                    if(!started){ ctx.moveTo(x,y); started = true; }
                    else ctx.lineTo(x,y);
                  }
                  ctx.stroke();
                }

                function applyLatest(latest){
                  const has = !!latest;
                  serviceBadge('bbCpuBadge', has);
                  serviceBadge('bbRamBadge', has);
                  const no = el('bbNoMetrics');
                  if(no) no.style.display = has ? 'none' : '';

                  if(!has){
                    el('bbUpdatedAt').textContent = '-';
                    el('bbCpuPct').textContent = '-';
                    el('bbRamPct').textContent = '-';
                    el('bbRamHint').textContent = '- / - MB';
                    el('bbPlayersNow').textContent = '-';
                    el('bbPlayersMax').textContent = '-';
                    el('bbTpsNow').textContent = '-';
                    el('bbRxNow').textContent = '-';
                    el('bbTxNow').textContent = '-';
                    drawGauge('bbGaugeCpu', 0, 'rgba(59,130,246,.95)');
                    drawGauge('bbGaugeRam', 0, 'rgba(168,85,247,.95)');
                    return;
                  }

                  el('bbUpdatedAt').textContent = fmtDateTime(latest.updatedAtIso);

                  const cpu = Number(latest.cpuLoad);
                  const cpu01 = Number.isFinite(cpu) ? clamp(cpu, 0, 1) : 0;
                  el('bbCpuPct').textContent = Number.isFinite(cpu) ? (Math.round(cpu01 * 10000)/100).toFixed(2) + '%' : '-';

                  const ramUsed = latest.ramUsedMb;
                  const ramMax = latest.ramMaxMb;
                  const ramPct = (ramUsed != null && ramMax != null && Number(ramMax) > 0) ? (Number(ramUsed)/Number(ramMax)) : null;
                  const ram01 = (ramPct == null) ? 0 : clamp(ramPct, 0, 1);
                  el('bbRamPct').textContent = (ramPct == null) ? '-' : (Math.round(ram01 * 10000)/100).toFixed(2) + '%';
                  el('bbRamHint').textContent = (ramUsed == null ? '-' : String(ramUsed)) + ' / ' + (ramMax == null ? '-' : String(ramMax)) + ' MB';

                  el('bbPlayersNow').textContent = (latest.playersOnline == null ? '-' : String(latest.playersOnline));
                  el('bbPlayersMax').textContent = (latest.playersMax == null ? '-' : String(latest.playersMax));
                  el('bbTpsNow').textContent = (latest.tps == null ? '-' : String(latest.tps));

                  el('bbRxNow').textContent = fmt2(latest.rxKbps);
                  el('bbTxNow').textContent = fmt2(latest.txKbps);

                  const cpuColor = cpu01 < 0.75 ? 'rgba(34,197,94,.95)' : (cpu01 < 0.90 ? 'rgba(245,158,11,.95)' : 'rgba(239,68,68,.95)');
                  const ramColor = ram01 < 0.75 ? 'rgba(34,197,94,.95)' : (ram01 < 0.90 ? 'rgba(245,158,11,.95)' : 'rgba(239,68,68,.95)');
                  drawGauge('bbGaugeCpu', cpu01, cpuColor);
                  drawGauge('bbGaugeRam', ram01, ramColor);
                }

                async function fetchAndRender(){
                  if(!sk){ applyLatest(null); return; }
                  const url = '/admin/api/live/stats/history?serverKey=' + encodeURIComponent(sk) + '&limit=600';
                  const r = await fetch(url, { headers: { 'Accept':'application/json' } });
                  if(!r.ok) throw new Error('HTTP ' + r.status);
                  const j = await r.json();
                  applyLatest(j.latest || null);
                  const pts = Array.isArray(j.points) ? j.points : [];
                  drawLineChart('bbChartPlayers', pts, p => p.playersOnline, 'rgba(59,130,246,.95)');
                  drawLineChart('bbChartTps', pts, p => p.tps, 'rgba(34,197,94,.95)');
                  drawLineChart('bbChartCpu', pts, p => p.cpuLoad, 'rgba(245,158,11,.95)');
                  drawLineChart('bbChartRam', pts, p => p.ramUsedMb, 'rgba(168,85,247,.95)');
                }

                try{
                  const seedEl = document.getElementById('bbStatsSeed');
                  if(seedEl && seedEl.textContent){
                    const seed = JSON.parse(seedEl.textContent);
                    if(seed && seed.latest) applyLatest(seed.latest);
                  }
                }catch(e){}

                fetchAndRender().catch(()=>{});

                let pollTimer = null;
                function startPollFallback(){
                  if(pollTimer) return;
                  pollTimer = window.setInterval(() => fetchAndRender().catch(()=>{}), 5000);
                }
                function setLive(text, ok){
                  if(!liveStatus) return;
                  liveStatus.textContent = 'live: ' + text;
                  liveStatus.style.color = ok ? 'rgba(21,128,61,.95)' : 'rgba(180,83,9,.95)';
                }

                try{
                  const es = new EventSource('/admin/api/live/stream');
                  setLive('connecting…', false);

                  es.addEventListener('open', () => {
                    setLive('connected', true);
                    if(pollTimer){ window.clearInterval(pollTimer); pollTimer = null; }
                  });
                  es.addEventListener('error', () => {
                    setLive('disconnected (polling)', false);
                    startPollFallback();
                  });
                  es.addEventListener('invalidate', (ev) => {
                    try{
                      const msg = JSON.parse(ev.data || '{}');
                      const targets = Array.isArray(msg.targets) ? msg.targets : [];
                      if(targets.includes('stats')) fetchAndRender().catch(()=>{});
                    }catch(e){}
                  });
                }catch(e){
                  setLive('SSE not available (polling)', false);
                  startPollFallback();
                }
              })();
            </script>
            """);

        html.append(appShellEnd());
        html.append(pageEndWithAppScript());
        return html.toString();
    }

    private static String seedJson(String serverKeyOrNull, MetricsRepository.Metrics latest) {
        try {
            var m = new java.util.LinkedHashMap<String, Object>();
            m.put("serverKey", serverKeyOrNull);
            m.put("latest", latest);
            m.put("points", java.util.List.of());
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(m);
        } catch (Exception e) {
            return "{\"serverKey\":null,\"latest\":null,\"points\":[]}";
        }
    }

    static String playerNotFound(String serverName, Lang lang) {
        String tTitle = (lang == Lang.DE) ? "Spieler" : "Player";
        String tSubtitle = (lang == Lang.DE) ? "Nicht gefunden" : "Not found";
        String tBody = (lang == Lang.DE) ? "Spieler nicht gefunden." : "Player not found.";

        StringBuilder html = new StringBuilder(20_000);
        html.append(pageStart(tTitle + " • " + esc(serverName)));
        html.append(appShellStart("players", serverName, lang));
        html.append(heroCenter(tTitle, tSubtitle));
        html.append("<div class='card bb-reveal'><div class='pad'>").append(esc(tBody)).append("</div></div>");
        html.append(appShellEnd());
        html.append(pageEndWithAppScript());
        return html.toString();
    }

    static String playerDetail(
            String serverName,
            Lang lang,
            String xuid,
            String name,
            boolean online,
            String onlineUpdatedIso,
            String onlineServerKey,
            String onlineServerName,
            String lastSeenIso,
            long playtimeSeconds,
            long kills,
            long deaths,
            boolean hasActiveBan,
            long banId,
            String banReason,
            String banCreatedAtIso,
            String banExpiresAtIso,
            String banUpdatedAtIso,
            List<AdminRepository.BanEvent> events
    ) {
        StringBuilder html = new StringBuilder(210_000);
        html.append(pageStart(((lang == Lang.DE) ? "Spieler" : "Player") + " • " + esc(name)));
        html.append(appShellStart("players", serverName, lang));

        String statusPill = online ? "<span class='pill pill-ok'>online</span>" : "<span class='pill pill-warn'>offline</span>";
        String onlineIso = onlineUpdatedIso == null ? "" : onlineUpdatedIso;
        String lastSeen = lastSeenIso == null ? "" : lastSeenIso;
        String onlineServer = (onlineServerName == null || onlineServerName.isBlank()) ? "—" : onlineServerName;

        html.append(heroCenter((lang == Lang.DE) ? "Spieler" : "Player", name));

        html.append("""
            <section class="card bb-reveal">
              <div class="cardHead"><div><b>Overview</b></div></div>
              <div class="pad">
                <div class="mono" style="display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:12px;">
                  <div>
                    <div style="opacity:.7">XUID</div>
                    <div class="mono" style="margin-top:4px">
                      <span class="copyable" role="button" tabindex="0" data-copy="%s">%s %s</span>
                    </div>
                  </div>
                  <div>
                    <div style="opacity:.7">Status</div>
                    <div style="margin-top:4px">
                      %s
                      <span class="mono" style="opacity:.7; margin-left:8px" data-iso="%s" data-sort="%s"></span>
                    </div>
                  </div>
                  <div>
                    <div style="opacity:.7">%s</div>
                    <div class="mono" style="margin-top:4px">%s</div>
                  </div>
                  <div>
                    <div style="opacity:.7">%s</div>
                    <div class="mono" style="margin-top:4px" data-iso="%s" data-sort="%s"></div>
                  </div>
                  <div>
                    <div style="opacity:.7">%s</div>
                    <div class="mono" style="margin-top:4px">%s</div>
                  </div>
                  <div>
                    <div style="opacity:.7">Kills</div>
                    <div class="mono" style="margin-top:4px">%d</div>
                  </div>
                  <div>
                    <div style="opacity:.7">Deaths</div>
                    <div class="mono" style="margin-top:4px">%d</div>
                  </div>
                </div>
              </div>
            </section>
            """.formatted(
                escAttr(xuid),
                iconCopy(),
                esc(xuid),
                statusPill,
                escAttr(onlineIso),
                escAttr(onlineIso),
                esc((lang == Lang.DE) ? "Online auf Server" : "Online on server"),
                esc(onlineServer),
                esc((lang == Lang.DE) ? "Zuletzt gesehen" : "Last Seen"),
                escAttr(lastSeen),
                escAttr(lastSeen),
                esc((lang == Lang.DE) ? "Spielzeit" : "Playtime"),
                esc(formatSeconds(playtimeSeconds)),
                kills,
                deaths
        ));

        html.append("""
            <section class="card bb-reveal">
              <div class="cardHead"><div><b>%s</b></div></div>
              <div class="pad">
                %s
              </div>
            </section>
            """.formatted(
                esc((lang == Lang.DE) ? "Moderation" : "Moderation"),
                moderationForm(xuid, hasActiveBan, lang)
        ));

        html.append("""
            <section class="card bb-reveal">
              <div class="cardHead"><div><b>%s</b></div></div>
              <div class="pad">
            """.formatted(esc((lang == Lang.DE) ? "Live Aktionen" : "Live actions")));

        if (online && onlineServerKey != null && !onlineServerKey.isBlank()) {
            html.append("""
                <div style="display:grid; gap:14px; grid-template-columns:repeat(2,minmax(0,1fr));">
                  <form method="post" action="/admin/player/kick" class="form">
                    <input type="hidden" name="xuid" value="%s">
                    <input type="hidden" name="serverKey" value="%s">
                    <input class="inp" name="reason" placeholder="%s" required>
                    <div style="display:flex; justify-content:flex-end">
                      <button class="btn danger" type="submit">%s</button>
                    </div>
                  </form>

                  <form method="post" action="/admin/player/message" class="form">
                    <input type="hidden" name="xuid" value="%s">
                    <input type="hidden" name="serverKey" value="%s">
                    <textarea class="inp" name="message" rows="3" placeholder="%s" required></textarea>
                    <div style="display:flex; justify-content:flex-end">
                      <button class="btn primary" type="submit">%s</button>
                    </div>
                  </form>
                </div>
                """.formatted(
                    escAttr(xuid),
                    escAttr(onlineServerKey),
                    escAttr((lang == Lang.DE) ? "Kick Grund" : "Kick reason"),
                    esc((lang == Lang.DE) ? "Kick" : "Kick"),
                    escAttr(xuid),
                    escAttr(onlineServerKey),
                    escAttr((lang == Lang.DE) ? "Nachricht an Spieler" : "Message to player"),
                    esc((lang == Lang.DE) ? "Nachricht senden" : "Send message")
            ));
        } else {
            html.append("""
                <div class="mono" style="opacity:.75">%s</div>
                """.formatted(esc((lang == Lang.DE)
                    ? "Spieler ist aktuell offline. Kick und Direktnachricht sind nur online möglich."
                    : "Player is currently offline. Kick and direct message are only available while online.")));
        }

        html.append("""
              </div>
            </section>
            """);

        if (hasActiveBan) {
            String created = banCreatedAtIso == null ? "" : banCreatedAtIso;
            String expires = banExpiresAtIso == null ? "" : banExpiresAtIso;
            String updated = banUpdatedAtIso == null ? "" : banUpdatedAtIso;

            html.append("""
                <section class="card bb-reveal">
                  <div class="cardHead"><div><b>%s</b></div></div>
                  <div class="pad mono">
                    Ban ID: %d<br>
                    %s: %s<br>
                    Created: <span data-iso="%s" data-sort="%s"></span><br>
                    Expires: <span data-iso="%s" data-sort="%s"></span><br>
                    Updated: <span data-iso="%s" data-sort="%s"></span>
                  </div>
                </section>
                """.formatted(
                    esc((lang == Lang.DE) ? "Aktiver Ban" : "Active Ban"),
                    banId,
                    esc((lang == Lang.DE) ? "Grund" : "Reason"),
                    esc(banReason),
                    escAttr(created), escAttr(created),
                    escAttr(expires), escAttr(expires),
                    escAttr(updated), escAttr(updated)
            ));
        }

        if (events != null && !events.isEmpty()) {
            StringBuilder rows = new StringBuilder(24_000);
            for (AdminRepository.BanEvent ev : events) {
                String atIso = ev.createdAtIso() == null ? "" : ev.createdAtIso();
                rows.append("<tr>")
                        .append("<td class='mono'>").append(esc(ev.eventType())).append("</td>")
                        .append("<td class='mono'>").append(esc(ev.actorType())).append("</td>")
                        .append("<td class='mono'>").append(esc(ev.actorUsername())).append("</td>")
                        .append("<td class='mono'>").append(esc(ev.actorServerKey())).append("</td>")
                        .append("<td class='mono' data-iso='").append(escAttr(atIso)).append("' data-sort='").append(escAttr(atIso)).append("'></td>")
                        .append("<td>").append(esc(ev.details())).append("</td>")
                        .append("</tr>");
            }

            html.append(tableStart((lang == Lang.DE) ? "Audit Verlauf" : "Audit trail", null,
                    new Th("Event", "text"),
                    new Th("Actor Type", "text"),
                    new Th("Actor User", "text"),
                    new Th("Actor Server", "text"),
                    new Th("At", "date"),
                    new Th("Details", "text")
            ));
            html.append(rows);
            html.append(tableEnd());
        }

        html.append(appShellEnd());
        html.append(pageEndWithAppScript());
        return html.toString();
    }

    static String playerRow(
            String xuid,
            String name,
            boolean online,
            String onlineUpdatedIso,
            String onlineServerName,
            long playtimeSeconds,
            long kills,
            long deaths,
            String lastSeenIso,
            String statsUpdatedIso
    ) {
        String href = "/admin/player?xuid=" + urlEncode(xuid);

        String status = online
                ? "<span class='pill pill-ok'>online</span>"
                : "<span class='pill pill-warn'>offline</span>";

        String statusHint = (onlineUpdatedIso == null || onlineUpdatedIso.isBlank())
                ? ""
                : "<span class='mono' style='opacity:.7; margin-left:8px' data-iso='"
                + escAttr(onlineUpdatedIso)
                + "' data-sort='"
                + escAttr(onlineUpdatedIso)
                + "'></span>";

        String server = (onlineServerName == null || onlineServerName.isBlank()) ? "—" : onlineServerName;
        String lastSeen = lastSeenIso == null ? "" : lastSeenIso;
        String statsUpdated = statsUpdatedIso == null ? "" : statsUpdatedIso;

        return """
            <tr>
              <td class="mono">
                <span class="copyable" role="button" tabindex="0" data-copy="%s">%s <a class="mono" href="%s">%s</a></span>
              </td>
              <td>%s</td>
              <td class="mono">%s %s</td>
              <td class="mono">%s</td>
              <td class="mono" data-sort="%d">%s</td>
              <td class="mono" data-sort="%d">%d</td>
              <td class="mono" data-sort="%d">%d</td>
              <td class="mono" data-iso="%s" data-sort="%s"></td>
              <td class="mono" data-iso="%s" data-sort="%s"></td>
            </tr>
            """.formatted(
                escAttr(xuid),
                iconCopy(),
                escAttr(href),
                esc(xuid),
                esc(name),
                status,
                statusHint,
                esc(server),
                playtimeSeconds, esc(formatSeconds(playtimeSeconds)),
                kills, kills,
                deaths, deaths,
                escAttr(lastSeen),
                escAttr(lastSeen),
                escAttr(statsUpdated),
                escAttr(statsUpdated)
        );
    }

    static String banRow(long banId, String xuid, String reason, String createdAtIso, String expiresAtIsoOrNull, String revokedAtIsoOrNull, String updatedAtIso, boolean active) {
        String href = "/admin/player?xuid=" + urlEncode(xuid);

        String created = createdAtIso == null ? "" : createdAtIso;
        String expires = expiresAtIsoOrNull == null ? "" : expiresAtIsoOrNull;
        String revoked = revokedAtIsoOrNull == null ? "" : revokedAtIsoOrNull;
        String updated = updatedAtIso == null ? "" : updatedAtIso;

        return """
            <tr>
              <td class="mono" data-sort="%d">%d</td>
              <td class="mono"><a class="mono" href="%s">%s</a></td>
              <td>%s</td>
              <td class="mono" data-iso="%s" data-sort="%s"></td>
              <td class="mono" data-iso="%s" data-sort="%s"></td>
              <td class="mono" data-iso="%s" data-sort="%s"></td>
              <td class="mono">%s <span class="mono" data-iso="%s" data-sort="%s"></span></td>
            </tr>
            """.formatted(
                banId, banId,
                escAttr(href),
                esc(xuid),
                esc(reason),
                escAttr(created), escAttr(created),
                escAttr(expires), escAttr(expires),
                escAttr(revoked), escAttr(revoked),
                active ? "<span class='pill pill-warn'>active</span>" : "<span class='pill pill-ok'>inactive</span>",
                escAttr(updated), escAttr(updated)
        );
    }
}