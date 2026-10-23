/* ══════════════════════════════════════════
   JEE 99%ile Q-Bank · app.js  v2
   Storage: Termux server → phone file
   Fallback: localStorage
   ══════════════════════════════════════════ */

const LS_KEY    = 'jee_qbank_v2';
const API       = 'http://localhost:3000/api';
const SUBJECTS  = ['Physics', 'Chemistry', 'Mathematics'];
const OPT_LABELS = ['A','B','C','D','E'];

// ─── STATE ───────────────────────────────────────────────────────────────────
let state = {
  subject: 'Physics',
  chapterIdx: null,
  filter: 'all',
  theme: 'dark',
  searchQuery: '',
  searchMode: false,
  showProgress: false,
  serverOnline: false,

  // Persisted data
  answers: {},
  bookmarks: [],
  chapterStats: {},

  // Timer/Mock
  mockActive: false,
  mockConfig: null,
  mockStartTs: null,
  mockInterval: null,
  mockQids: [],
};

// ─── STORAGE LAYER ────────────────────────────────────────────────────────────
// Tries server first, falls back to localStorage transparently

let saveTimer = null;
let lastSaveHash = '';

async function loadFromStorage() {
  // Try server
  try {
    const res  = await fetch(API + '/progress', { signal: AbortSignal.timeout(3000) });
    const json = await res.json();
    if (json.ok && json.data) {
      state.answers      = json.data.answers      || {};
      state.bookmarks    = json.data.bookmarks    || [];
      state.chapterStats = json.data.chapterStats || {};
      state.theme        = json.data.theme        || 'dark';
      console.log('[Storage] Loaded from server file');
      // Also sync to localStorage as cache
      localStorage.setItem(LS_KEY, JSON.stringify(getPersistedData()));
      return;
    }
  } catch(e) {
    console.log('[Storage] Server unavailable, using localStorage');
  }
  // Fallback localStorage
  try {
    const saved = localStorage.getItem(LS_KEY);
    if (saved) {
      const d = JSON.parse(saved);
      state.answers      = d.answers      || {};
      state.bookmarks    = d.bookmarks    || [];
      state.chapterStats = d.chapterStats || {};
      state.theme        = d.theme        || 'dark';
    }
  } catch(e) {}
}

function getPersistedData() {
  return {
    answers:      state.answers,
    bookmarks:    state.bookmarks,
    chapterStats: state.chapterStats,
    theme:        state.theme,
  };
}

function scheduleSave() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(doSave, 1500); // debounce 1.5s
}

async function doSave() {
  const data = getPersistedData();
  const hash = JSON.stringify(data).length; // lightweight change check
  if (hash === lastSaveHash) return;
  lastSaveHash = hash;

  showSaveIndicator('saving');

  // Always save to localStorage immediately
  try { localStorage.setItem(LS_KEY, JSON.stringify(data)); } catch(e) {}

  // Try saving to server (phone file)
  if (state.serverOnline) {
    try {
      const res = await fetch(API + '/progress', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
        signal: AbortSignal.timeout(5000),
      });
      const json = await res.json();
      if (json.ok) {
        showSaveIndicator('saved', '✓ Saved to phone');
        return;
      }
    } catch(e) {
      console.warn('[Storage] Server save failed, kept in localStorage');
      state.serverOnline = false;
      updateServerStatus(false);
    }
  }
  showSaveIndicator('saved', '✓ Saved locally');
}

// ─── SAVE INDICATOR ───────────────────────────────────────────────────────────
let saveHideTimer;
function showSaveIndicator(type, text) {
  const el   = document.getElementById('save-indicator');
  const dot  = document.getElementById('save-dot');
  const txt  = document.getElementById('save-text');
  if (!el) return;
  el.className = 'save-indicator show ' + type;
  dot.className = 'save-dot' + (type === 'saving' ? ' spin' : '');
  txt.textContent = text || (type === 'saving' ? 'Saving…' : '✓ Saved');
  clearTimeout(saveHideTimer);
  if (type !== 'saving') {
    saveHideTimer = setTimeout(() => el.classList.remove('show'), 2500);
  }
}

// ─── SERVER STATUS ────────────────────────────────────────────────────────────
async function checkServer() {
  const dot  = document.getElementById('server-dot');
  const text = document.getElementById('server-status-text');
  const pathEl = document.getElementById('server-path');
  if (dot) dot.className = 'server-dot checking';
  if (text) text.textContent = 'Connecting…';

  try {
    const res  = await fetch(API + '/status', { signal: AbortSignal.timeout(3000) });
    const json = await res.json();
    if (json.ok) {
      state.serverOnline = true;
      updateServerStatus(true, json);
      return true;
    }
  } catch(e) {}
  state.serverOnline = false;
  updateServerStatus(false);
  return false;
}

function updateServerStatus(online, info) {
  const dot    = document.getElementById('server-dot');
  const text   = document.getElementById('server-status-text');
  const pathEl = document.getElementById('server-path');
  const backupBtn  = document.getElementById('backup-btn');
  const backupsBtn = document.getElementById('backups-btn');

  if (online && info) {
    if (dot)  dot.className = 'server-dot online';
    if (text) text.textContent = `Server online · ${info.answeredCount} answers saved`;
    if (pathEl) pathEl.textContent = info.storageDir;
    if (backupBtn)  backupBtn.style.display = '';
    if (backupsBtn) backupsBtn.style.display = '';
  } else if (online) {
    if (dot)  dot.className = 'server-dot online';
    if (text) text.textContent = 'Server online';
  } else {
    if (dot)  dot.className = 'server-dot offline';
    if (text) text.textContent = 'Server offline — saving to browser only';
    if (pathEl) pathEl.textContent = '';
    if (backupBtn)  backupBtn.style.display  = 'none';
    if (backupsBtn) backupsBtn.style.display = 'none';
  }
}

async function manualBackup() {
  try {
    const res  = await fetch(API + '/backup-now', { method: 'POST', signal: AbortSignal.timeout(3000) });
    const json = await res.json();
    if (json.ok) toast('📦 Backup saved to phone!');
  } catch(e) { toast('Backup failed — server offline'); }
}

async function showBackups() {
  try {
    const res  = await fetch(API + '/backups', { signal: AbortSignal.timeout(3000) });
    const json = await res.json();
    const list = document.getElementById('backups-list');
    if (!json.ok || json.backups.length === 0) {
      list.innerHTML = '<div style="color:var(--text3);font-size:0.85rem;padding:12px 0">No backups yet.</div>';
    } else {
      list.innerHTML = json.backups.map(b => {
        const kb = (b.size / 1024).toFixed(1);
        const name = b.name.replace('progress_','').replace('.json','').replace(/-/g,' ');
        return `<div style="display:flex;align-items:center;gap:10px;padding:10px 0;border-bottom:1px solid var(--border)">
          <div style="flex:1">
            <div style="font-size:0.82rem;color:var(--text)">${name}</div>
            <div style="font-size:0.72rem;color:var(--text3)">${kb} KB</div>
          </div>
          <button class="btn btn-xs" onclick="restoreBackup('${b.name}')">Restore</button>
        </div>`;
      }).join('');
    }
    document.getElementById('backups-modal').classList.add('open');
  } catch(e) { toast('Cannot reach server'); }
}

async function restoreBackup(filename) {
  if (!confirm(`Restore from "${filename}"?\nThis will replace your current progress.`)) return;
  try {
    const res  = await fetch(API + '/restore/' + filename, { method:'POST', signal: AbortSignal.timeout(3000) });
    const json = await res.json();
    if (json.ok) {
      await loadFromStorage();
      document.getElementById('backups-modal').classList.remove('open');
      buildChapterList(state.subject);
      updateSidebarFooter();
      if (state.chapterIdx !== null) renderChapter(); else showWelcome();
      toast('✓ Progress restored!');
    }
  } catch(e) { toast('Restore failed'); }
}

// ─── BOOT ────────────────────────────────────────────────────────────────────
async function boot() {
  // Check server in background
  checkServer().then(online => {
    if (!online) {
      // Retry once after 3s (server might still be starting)
      setTimeout(() => checkServer(), 3000);
    }
  });

  await loadFromStorage();
  applyTheme();
  buildChapterList('Physics');
  updateSidebarFooter();
  showWelcome();
  setupSwipeGesture();
  registerSW();
  setupInstallPrompt();
  document.getElementById('global-search').addEventListener('input', e => handleSearch(e.target.value));
  document.addEventListener('keydown', onKeyDown);
  window.addEventListener('resize', onResize);
  onResize();

  // Re-check server every 30s
  setInterval(() => { if (!state.serverOnline) checkServer(); }, 30000);
}

// ─── THEME ───────────────────────────────────────────────────────────────────
function applyTheme() {
  document.documentElement.setAttribute('data-theme', state.theme);
  const btn = document.getElementById('theme-btn');
  if (btn) btn.textContent = state.theme === 'dark' ? '☀' : '🌙';
}
function toggleTheme() {
  state.theme = state.theme === 'dark' ? 'light' : 'dark';
  applyTheme(); scheduleSave();
}

// ─── CHAPTER LIST ─────────────────────────────────────────────────────────────
function buildChapterList(subject) {
  state.subject = subject;
  document.querySelectorAll('.stab').forEach(t =>
    t.classList.toggle('active', t.dataset.sub === subject)
  );
  const list  = document.getElementById('chapter-list');
  const query = (document.getElementById('sidebar-search')?.value || '').toLowerCase();
  list.innerHTML = '';

  const subFillMap = { Physics:'fill-p', Chemistry:'fill-c', Mathematics:'fill-m' };

  JEE_DATA[subject].forEach((ch, i) => {
    if (query && !ch.name.toLowerCase().includes(query)) return;
    const total    = ch.qs.length;
    const attempted= ch.qs.filter(q => state.answers[q.id]).length;
    const pct      = total > 0 ? Math.round(attempted / total * 100) : 0;

    const el = document.createElement('div');
    el.className = 'chapter-item' + (state.chapterIdx === i && state.subject === subject ? ' active' : '');
    el.dataset.sub = subject;
    el.innerHTML = `
      <div class="ch-info">
        <div class="ch-name">${ch.name}</div>
        <div class="ch-progress-row">
          <div class="ch-progress-mini">
            <div class="ch-progress-fill ${subFillMap[subject]}" style="width:${pct}%"></div>
          </div>
          <span class="ch-pct">${pct}%</span>
        </div>
      </div>
      <span class="ch-badge">${ch.qs.length}</span>`;
    el.addEventListener('click', () => loadChapter(subject, i));
    list.appendChild(el);
  });
}

function switchSubject(sub) {
  state.subject = sub; state.chapterIdx = null;
  buildChapterList(sub);
}

// ─── LOAD CHAPTER ─────────────────────────────────────────────────────────────
function loadChapter(subject, idx) {
  stopChapterTimer();
  state.subject    = subject;
  state.chapterIdx = idx;
  state.filter     = 'all';
  state.searchMode = false;
  state.showProgress = false;
  document.getElementById('global-search').value = '';
  document.querySelectorAll('.stab').forEach(t =>
    t.classList.toggle('active', t.dataset.sub === subject)
  );
  buildChapterList(subject);
  renderChapter();
  if (window.innerWidth <= 768) closeSidebar();
}

// ─── RENDER CHAPTER ───────────────────────────────────────────────────────────
function renderChapter() {
  if (state.chapterIdx === null) return;
  const ch       = JEE_DATA[state.subject][state.chapterIdx];
  const key      = state.subject + '/' + ch.name;
  const qs       = applyFilter(ch.qs);
  const total    = ch.qs.length;
  const attempted= ch.qs.filter(q => state.answers[q.id]).length;
  const correct  = ch.qs.filter(q => state.answers[q.id]?.correct).length;
  const cs       = state.chapterStats[key] || {};
  const timeStr  = fmtTime(Math.round((cs.time_ms || 0) / 1000));

  let html = `
  <div class="ch-header">
    <div class="ch-header-left">
      <div class="ch-header-title">${ch.name}</div>
      <div class="ch-header-meta" id="ch-meta">${state.subject} · ${total} Qs · ${attempted} attempted · ${correct} correct · ⏱ ${timeStr}</div>
    </div>
    <div class="ch-header-actions">
      <button class="btn btn-sm" onclick="resetChapter()">↺ Reset</button>
      <button class="btn btn-sm" onclick="revealAll()">Show All</button>
    </div>
  </div>

  <div class="filter-bar">
    ${['all','mcq','numerical','bookmarked','unanswered'].map(f =>
      `<button class="filter-chip${state.filter===f?' active':''}" onclick="setFilter('${f}')">${
        {all:'All',mcq:'MCQ',numerical:'Numerical',bookmarked:'★ Saved',unanswered:'Unattempted'}[f]
      }</button>`
    ).join('')}
  </div>

  <div class="stats-strip">
    <div class="sstat blue"><div class="sstat-val">${total}</div><div class="sstat-lbl">Total</div></div>
    <div class="sstat green"><div class="sstat-val" id="ss-correct">${correct}</div><div class="sstat-lbl">Correct</div></div>
    <div class="sstat red"><div class="sstat-val" id="ss-wrong">${attempted-correct}</div><div class="sstat-lbl">Wrong</div></div>
    <div class="sstat gold"><div class="sstat-val" id="ss-remain">${total-attempted}</div><div class="sstat-lbl">Left</div></div>
    <div class="sstat"><div class="sstat-val" id="ss-acc">${attempted>0?Math.round(correct/attempted*100):0}%</div><div class="sstat-lbl">Accuracy</div></div>
  </div>

  <div class="questions-wrap fade-in" id="questions-wrap">
  ${qs.length === 0
    ? `<div style="text-align:center;color:var(--text3);padding:40px;font-size:0.9rem">No questions match this filter.</div>`
    : qs.map((q, i) => buildQuestionCard(q, i + 1, state.subject)).join('')
  }
  </div>`;

  const main = document.getElementById('main-panel');
  main.innerHTML = html;
  main.scrollTop = 0;
  typesetMath(main);
  startChapterTimer();
}

// ─── QUESTION CARD ────────────────────────────────────────────────────────────
function buildQuestionCard(q, num, subject) {
  const ans         = state.answers[q.id];
  const isBookmarked= state.bookmarks.includes(q.id);
  const cardClass   = ans ? (ans.correct ? 'answered-correct' : 'answered-wrong') : '';
  const typeCls     = q.type === 'singleCorrect' ? 'mcq' : 'num';
  const typeLabel   = q.type === 'singleCorrect' ? 'MCQ' : 'Numerical';
  const statusIcon  = ans
    ? (ans.correct ? '<span class="q-status-icon">✓</span>' : '<span class="q-status-icon" style="color:var(--red)">✗</span>')
    : '';

  // Find chapter name for this question
  let chName = '';
  for (const chapters of Object.values(JEE_DATA))
    for (const ch of chapters)
      if (ch.qs.some(x => x.id === q.id)) { chName = ch.name; break; }

  const esc = s => String(s).replace(/'/g,"&#39;").replace(/"/g,'&quot;');

  let optsHtml = '';
  if (q.type === 'singleCorrect' && q.opts?.length) {
    optsHtml = '<div class="opts-grid">';
    q.opts.forEach((opt, oi) => {
      let cls = '';
      if (ans) {
        if (opt.id === q.correct)    cls = 'correct locked';
        else if (opt.id === ans.sel) cls = 'wrong-sel locked';
        else cls = 'locked';
      }
      optsHtml += `<div class="opt-item ${cls}" onclick="selectOpt('${q.id}','${opt.id}','${q.correct}','${subject}','${esc(chName)}')">
        <span class="opt-key">${OPT_LABELS[oi]}</span>
        <span class="opt-text">${opt.text}</span>
      </div>`;
    });
    optsHtml += '</div>';
  } else if (q.type === 'numerical') {
    const numCls = ans ? (ans.correct ? 'correct' : 'wrong') : '';
    optsHtml = `<div class="num-wrap">
      <input class="num-input ${numCls}" id="num-${q.id}" type="text"
        placeholder="Enter answer…"
        ${ans ? `value="${esc(String(ans.sel))}" readonly` : ''}
        onkeydown="if(event.key==='Enter')submitNum('${q.id}','${esc(String(q.ans))}','${subject}','${esc(chName)}')"/>
      ${!ans ? `<button class="btn btn-sm btn-primary" onclick="submitNum('${q.id}','${esc(String(q.ans))}','${subject}','${esc(chName)}')">Submit</button>` : ''}
    </div>`;
  }

  let flashHtml = '';
  if (ans) {
    let correctText = String(q.ans);
    if (q.type === 'singleCorrect') {
      const co = q.opts?.find(o => o.id === q.correct);
      correctText = co ? co.text : String(q.ans);
    }
    flashHtml = `<div class="result-flash ${ans.correct ? 'show-correct' : 'show-wrong'}">
      ${ans.correct ? '✓ Correct!' : `✗ Wrong — Answer: ${correctText}`}
    </div>`;
  } else {
    flashHtml = `<div class="result-flash" id="flash-${q.id}"></div>`;
  }

  return `<div class="q-card ${cardClass}" id="qcard-${q.id}">
    <div class="q-card-top">
      <span class="q-num">Q${num}</span>
      <span class="q-type-pill ${typeCls}">${typeLabel}</span>
      ${statusIcon}
      <button class="q-bookmark-btn${isBookmarked?' bookmarked':''}" onclick="toggleBookmark('${q.id}')">${isBookmarked?'★':'☆'}</button>
    </div>
    <div class="q-body">
      <div class="q-text">${q.html}</div>
      ${optsHtml}
    </div>
    ${flashHtml}
    <div class="q-actions">
      <button class="btn btn-sm" onclick="toggleSol('${q.id}')">💡 Solution</button>
    </div>
    <div class="sol-box${ans?' open':''}" id="sol-${q.id}">
      <div class="sol-label">✦ Solution</div>
      <div class="sol-text">${q.sol || 'Solution not available.'}</div>
    </div>
  </div>`;
}

// ─── ANSWER LOGIC ─────────────────────────────────────────────────────────────
function selectOpt(qid, optId, correctId, subject, chName) {
  if (state.answers[qid]) return;
  const isCorrect = optId === correctId;
  recordAnswer(qid, optId, isCorrect, subject, chName);

  const card = document.getElementById('qcard-' + qid);
  if (!card) return;
  card.classList.add(isCorrect ? 'answered-correct' : 'answered-wrong');
  card.querySelectorAll('.opt-item').forEach(el => el.classList.add('locked'));

  const q = findQ(qid);
  if (q) {
    q.opts.forEach((opt, oi) => {
      const el = card.querySelectorAll('.opt-item')[oi];
      if (!el) return;
      if (opt.id === correctId)   el.classList.add('correct');
      else if (opt.id === optId)  el.classList.add('wrong-sel');
    });
  }
  const correctText = q?.opts?.find(o => o.id === q.correct)?.text || q?.ans || '';
  showFlash(card, isCorrect, correctText);
  openSol(qid);
  refreshStatsStrip(subject, chName);
  buildChapterList(subject);
  updateSidebarFooter();
  scheduleSave();
  if (state.mockActive) updateTimerScore();
}

function submitNum(qid, ans, subject, chName) {
  const inp = document.getElementById('num-' + qid);
  if (!inp || state.answers[qid]) return;
  const val = inp.value.trim();
  if (!val) { toast('Please enter an answer'); return; }
  const isCorrect = Math.abs(parseFloat(val) - parseFloat(ans)) < 0.01 || val === String(ans);
  recordAnswer(qid, val, isCorrect, subject, chName);

  inp.classList.add(isCorrect ? 'correct' : 'wrong');
  inp.readOnly = true;
  inp.nextElementSibling?.remove();

  const card = document.getElementById('qcard-' + qid);
  if (card) {
    card.classList.add(isCorrect ? 'answered-correct' : 'answered-wrong');
    showFlash(card, isCorrect, ans);
  }
  openSol(qid);
  refreshStatsStrip(subject, chName);
  buildChapterList(subject);
  updateSidebarFooter();
  scheduleSave();
  if (state.mockActive) updateTimerScore();
}

function recordAnswer(qid, sel, correct, subject, chName) {
  state.answers[qid] = { sel, correct, ts: Date.now() };
  const key = subject + '/' + chName;
  if (!state.chapterStats[key]) state.chapterStats[key] = { attempts:0, correct:0, time_ms:0 };
  state.chapterStats[key].attempts++;
  if (correct) state.chapterStats[key].correct++;
}

function showFlash(card, isCorrect, correctText) {
  const f = card.querySelector('.result-flash');
  if (!f) return;
  f.className = 'result-flash ' + (isCorrect ? 'show-correct' : 'show-wrong');
  f.innerHTML = isCorrect ? '✓ Correct!' : `✗ Wrong — Answer: ${correctText}`;
}

function openSol(qid) {
  const s = document.getElementById('sol-' + qid);
  if (s) { s.classList.add('open'); typesetMath(s); }
}

function toggleSol(qid) {
  const s = document.getElementById('sol-' + qid);
  if (!s) return;
  s.classList.toggle('open');
  if (s.classList.contains('open')) typesetMath(s);
}

function toggleBookmark(qid) {
  const idx = state.bookmarks.indexOf(qid);
  if (idx === -1) state.bookmarks.push(qid);
  else state.bookmarks.splice(idx, 1);
  const btn = document.querySelector(`#qcard-${qid} .q-bookmark-btn`);
  if (btn) {
    const on = state.bookmarks.includes(qid);
    btn.classList.toggle('bookmarked', on);
    btn.textContent = on ? '★' : '☆';
  }
  scheduleSave();
  toast(state.bookmarks.includes(qid) ? '★ Bookmarked' : 'Bookmark removed');
}

function findQ(qid) {
  for (const chapters of Object.values(JEE_DATA))
    for (const ch of chapters)
      for (const q of ch.qs)
        if (q.id === qid) return q;
  return null;
}

// ─── FILTER / RESET / REVEAL ──────────────────────────────────────────────────
function applyFilter(qs) {
  return qs.filter(q => {
    if (state.filter === 'mcq')        return q.type === 'singleCorrect';
    if (state.filter === 'numerical')  return q.type === 'numerical';
    if (state.filter === 'bookmarked') return state.bookmarks.includes(q.id);
    if (state.filter === 'unanswered') return !state.answers[q.id];
    return true;
  });
}
function setFilter(f) { state.filter = f; renderChapter(); }

function resetChapter() {
  if (state.chapterIdx === null) return;
  const ch = JEE_DATA[state.subject][state.chapterIdx];
  if (!confirm(`Reset progress for "${ch.name}"?`)) return;
  ch.qs.forEach(q => delete state.answers[q.id]);
  delete state.chapterStats[state.subject + '/' + ch.name];
  scheduleSave();
  buildChapterList(state.subject);
  updateSidebarFooter();
  renderChapter();
  toast('Chapter reset');
}

function revealAll() {
  if (state.chapterIdx === null) return;
  const ch = JEE_DATA[state.subject][state.chapterIdx];
  ch.qs.forEach(q => {
    if (!state.answers[q.id])
      state.answers[q.id] = { sel: q.correct || q.ans, correct: true, revealed: true, ts: Date.now() };
  });
  scheduleSave(); renderChapter();
}

// ─── STATS STRIP ──────────────────────────────────────────────────────────────
function refreshStatsStrip(subject, chName) {
  if (state.chapterIdx === null) return;
  const ch = JEE_DATA[subject || state.subject][state.chapterIdx];
  const total    = ch.qs.length;
  const attempted= ch.qs.filter(q => state.answers[q.id]).length;
  const correct  = ch.qs.filter(q => state.answers[q.id]?.correct).length;
  const set = (id, v) => { const el = document.getElementById(id); if (el) el.textContent = v; };
  set('ss-correct', correct);
  set('ss-wrong',   attempted - correct);
  set('ss-remain',  total - attempted);
  set('ss-acc',     attempted > 0 ? Math.round(correct/attempted*100) + '%' : '0%');
  const key = (subject||state.subject) + '/' + ch.name;
  const cs = state.chapterStats[key] || {};
  const meta = document.getElementById('ch-meta');
  if (meta) meta.textContent = `${subject||state.subject} · ${total} Qs · ${attempted} attempted · ${correct} correct · ⏱ ${fmtTime(Math.round((cs.time_ms||0)/1000))}`;
}

function updateSidebarFooter() {
  const allQs  = Object.values(JEE_DATA).flatMap(s => s.flatMap(c => c.qs));
  const total  = allQs.length;
  const ans    = allQs.filter(q => state.answers[q.id]).length;
  const pct    = Math.round(ans / total * 100);
  const fill   = document.getElementById('global-fill');
  const text   = document.getElementById('global-text');
  if (fill) fill.style.width = pct + '%';
  if (text) text.textContent = `${ans} / ${total} (${pct}%)`;
}

// ─── CHAPTER TIMER ────────────────────────────────────────────────────────────
let chTimerInterval = null, chTimerStart = null, curChKey = null;

function startChapterTimer() {
  stopChapterTimer();
  if (state.chapterIdx === null) return;
  const ch = JEE_DATA[state.subject][state.chapterIdx];
  curChKey = state.subject + '/' + ch.name;
  chTimerStart = Date.now();
}

function stopChapterTimer() {
  if (curChKey && chTimerStart) {
    const elapsed = Date.now() - chTimerStart;
    if (!state.chapterStats[curChKey]) state.chapterStats[curChKey] = { attempts:0, correct:0, time_ms:0 };
    state.chapterStats[curChKey].time_ms += elapsed;
    scheduleSave();
  }
  chTimerStart = null; curChKey = null;
}

window.addEventListener('beforeunload', stopChapterTimer);
document.addEventListener('visibilitychange', () => {
  if (document.hidden) stopChapterTimer();
  else if (state.chapterIdx !== null) startChapterTimer();
});

// ─── SEARCH ───────────────────────────────────────────────────────────────────
let searchTimeout;
function handleSearch(val) {
  clearTimeout(searchTimeout);
  const q = val.trim();
  if (!q) {
    state.searchMode = false;
    if (state.chapterIdx !== null && !state.showProgress) renderChapter();
    else if (state.showProgress) renderProgressPage();
    else showWelcome();
    return;
  }
  state.searchMode = true; state.searchQuery = q;
  searchTimeout = setTimeout(renderSearch, 220);
}

function renderSearch() {
  const q = state.searchQuery.toLowerCase();
  const results = [];
  outer: for (const [sub, chapters] of Object.entries(JEE_DATA))
    for (const ch of chapters)
      for (const question of ch.qs) {
        if (question.html.replace(/<[^>]*>/g,'').toLowerCase().includes(q))
          results.push({ sub, chName: ch.name, q: question });
        if (results.length >= 60) break outer;
      }

  const subColors = { Physics:'var(--accent-p)', Chemistry:'var(--accent-c)', Mathematics:'var(--accent-m)' };
  let html = `<div class="search-panel fade-in">
    <div class="search-header">${results.length === 0 ? 'No results' : `${results.length}${results.length===60?'+':''} results for "<strong>${state.searchQuery}</strong>"`}</div>`;

  results.forEach(r => {
    const preview = r.q.html.replace(/<[^>]*>/g,'').substring(0, 130);
    const ans = state.answers[r.q.id];
    html += `<div class="sr-item" onclick="gotoResult('${r.sub}','${r.chName.replace(/'/g,"&#39;")}','${r.q.id}')">
      <div class="sr-q">${ans?(ans.correct?'✓ ':'✗ '):''}${preview}${preview.length===130?'…':''}</div>
      <div class="sr-meta">
        <span class="sr-subject" style="color:${subColors[r.sub]}">${r.sub}</span>
        <span>·</span><span>${r.chName}</span>
        <span>·</span><span>${r.q.type==='singleCorrect'?'MCQ':'Numerical'}</span>
      </div>
    </div>`;
  });
  html += '</div>';
  document.getElementById('main-panel').innerHTML = html;
}

function gotoResult(sub, chName, qid) {
  document.getElementById('global-search').value = '';
  state.searchMode = false;
  const idx = JEE_DATA[sub].findIndex(c => c.name === chName);
  if (idx === -1) return;
  document.querySelectorAll('.stab').forEach(t => t.classList.toggle('active', t.dataset.sub === sub));
  buildChapterList(sub);
  loadChapter(sub, idx);
  setTimeout(() => document.getElementById('qcard-'+qid)?.scrollIntoView({behavior:'smooth',block:'center'}), 350);
}

// ─── MOCK TEST ─────────────────────────────────────────────────────────────────
function openMockModal() {
  document.getElementById('mock-modal').classList.add('open');
  document.getElementById('mock-subject').value = state.subject;
  populateMockChapters();
}
function closeMockModal() { document.getElementById('mock-modal').classList.remove('open'); }

function populateMockChapters() {
  const sub   = document.getElementById('mock-subject').value;
  const chSel = document.getElementById('mock-chapter');
  chSel.innerHTML = '<option value="all">All Chapters</option>';
  JEE_DATA[sub].forEach((ch, i) => { chSel.innerHTML += `<option value="${i}">${ch.name}</option>`; });
}

function startMock() {
  const sub      = document.getElementById('mock-subject').value;
  const chVal    = document.getElementById('mock-chapter').value;
  const duration = parseInt(document.getElementById('mock-duration').value) || 30;
  const count    = parseInt(document.getElementById('mock-count').value) || 20;

  let pool = chVal === 'all'
    ? JEE_DATA[sub].flatMap(ch => ch.qs.filter(q => !state.answers[q.id]))
    : JEE_DATA[sub][parseInt(chVal)].qs.filter(q => !state.answers[q.id]);

  if (pool.length === 0) { toast('No unattempted questions!'); return; }
  pool = shuffle(pool).slice(0, Math.min(count, pool.length));
  state.mockActive = true;
  state.mockQids   = pool.map(q => q.id);
  state.mockConfig = { sub, duration_min: duration, count: pool.length };
  state.mockStartTs = Date.now();
  closeMockModal();
  renderMock(sub, pool);
}

function renderMock(sub, questions) {
  const dur_ms = state.mockConfig.duration_min * 60 * 1000;
  let html = `<div class="timer-banner" id="timer-banner">
    <span class="timer-label">⏱ Mock Test</span>
    <span class="timer-display" id="timer-display">--:--</span>
    <span style="flex:1"></span>
    <span class="timer-score" id="timer-score">Score: <b>0</b> / ${questions.length}</span>
    <button class="btn btn-sm btn-danger" style="margin-left:8px" onclick="endMock()">End</button>
  </div><div class="questions-wrap fade-in">`;

  questions.forEach((q, i) => { html += buildQuestionCard(q, i+1, sub); });
  html += '</div>';
  const main = document.getElementById('main-panel');
  main.innerHTML = html; main.scrollTop = 0;
  typesetMath(main);

  if (state.mockInterval) clearInterval(state.mockInterval);
  state.mockInterval = setInterval(() => {
    const remaining = dur_ms - (Date.now() - state.mockStartTs);
    if (remaining <= 0) { endMock(true); return; }
    const mins = Math.floor(remaining / 60000);
    const secs = Math.floor((remaining % 60000) / 1000);
    const display = document.getElementById('timer-display');
    if (display) {
      display.textContent = `${String(mins).padStart(2,'0')}:${String(secs).padStart(2,'0')}`;
      display.className = 'timer-display' + (remaining < 60000 ? ' danger' : remaining < 300000 ? ' warning' : '');
    }
  }, 1000);
}

function updateTimerScore() {
  const score = state.mockQids.filter(id => state.answers[id]?.correct).length;
  const el = document.getElementById('timer-score');
  if (el) el.innerHTML = `Score: <b>${score}</b> / ${state.mockQids.length}`;
}

function endMock(timeout = false) {
  if (state.mockInterval) { clearInterval(state.mockInterval); state.mockInterval = null; }
  state.mockActive = false;
  const elapsed_s = Math.round((Date.now() - state.mockStartTs) / 1000);
  const score  = state.mockQids.filter(id => state.answers[id]?.correct).length;
  const total  = state.mockQids.length;
  const wrong  = state.mockQids.filter(id => state.answers[id] && !state.answers[id].correct).length;
  const skip   = state.mockQids.filter(id => !state.answers[id]).length;
  const mins   = Math.floor(elapsed_s/60), secs = elapsed_s%60;

  document.getElementById('main-panel').innerHTML = `<div class="welcome fade-in">
    <div style="font-size:2.5rem">${timeout?'⏰':'🎉'}</div>
    <div class="welcome-title">${timeout?"Time's Up!":'Mock Complete'}</div>
    <div class="welcome-sub">${state.mockConfig?.sub} · ${total} Questions · ${mins}m ${secs}s</div>
    <div style="display:grid;grid-template-columns:repeat(4,1fr);gap:10px;margin-top:20px;max-width:500px;width:100%">
      <div class="wc"><div class="wc-num" style="color:var(--green)">${score}</div><div class="wc-lbl">Correct</div></div>
      <div class="wc"><div class="wc-num" style="color:var(--red)">${wrong}</div><div class="wc-lbl">Wrong</div></div>
      <div class="wc"><div class="wc-num" style="color:var(--text3)">${skip}</div><div class="wc-lbl">Skipped</div></div>
      <div class="wc"><div class="wc-num" style="color:var(--gold)">${total>0?Math.round(score/total*100):0}%</div><div class="wc-lbl">Score</div></div>
    </div>
    <button class="btn btn-primary" style="margin-top:20px" onclick="showWelcome()">← Back</button>
  </div>`;
  scheduleSave();
}

// ─── PROGRESS PAGE ────────────────────────────────────────────────────────────
function renderProgressPage() {
  state.showProgress = true;
  stopChapterTimer();

  const rows = [];
  for (const [sub, chapters] of Object.entries(JEE_DATA)) {
    for (const ch of chapters) {
      const key      = sub + '/' + ch.name;
      const cs       = state.chapterStats[key] || {};
      const total    = ch.qs.length;
      const attempted= ch.qs.filter(q => state.answers[q.id]).length;
      const correct  = ch.qs.filter(q => state.answers[q.id]?.correct).length;
      const time_s   = Math.round((cs.time_ms||0)/1000);
      if (attempted === 0 && time_s === 0) continue;
      rows.push({ sub, chName:ch.name, total, attempted, correct, time_s });
    }
  }

  const allQs    = Object.values(JEE_DATA).flatMap(s => s.flatMap(c => c.qs));
  const gAns     = allQs.filter(q => state.answers[q.id]).length;
  const gCorrect = allQs.filter(q => state.answers[q.id]?.correct).length;
  const gTime    = Object.values(state.chapterStats).reduce((a,c) => a+(c.time_ms||0), 0);
  const subColors= { Physics:'var(--accent-p)', Chemistry:'var(--accent-c)', Mathematics:'var(--accent-m)' };

  const tableRows = rows.sort((a,b)=>b.attempted-a.attempted).map(r => {
    const pct = r.total>0?Math.round(r.attempted/r.total*100):0;
    const acc = r.attempted>0?Math.round(r.correct/r.attempted*100):0;
    const color = subColors[r.sub];
    return `<tr onclick="loadChapter('${r.sub}',${JEE_DATA[r.sub].findIndex(c=>c.name===r.chName)})" style="cursor:pointer">
      <td><span style="color:${color};font-weight:700;font-size:0.7rem">${r.sub.slice(0,4).toUpperCase()}</span></td>
      <td style="color:var(--text);font-weight:500">${r.chName}</td>
      <td><span style="font-family:var(--mono)">${r.attempted}/${r.total}</span>
        <div class="pg-mini-bar" style="margin-top:4px"><div class="pg-mini-fill" style="width:${pct}%;background:${color}"></div></div></td>
      <td style="font-family:var(--mono);font-weight:700;color:${acc>=70?'var(--green)':acc>=40?'var(--gold)':'var(--red)'}">${acc}%</td>
      <td style="font-family:var(--mono);color:var(--text3)">${fmtTime(r.time_s)}</td>
    </tr>`;
  }).join('');

  document.getElementById('main-panel').innerHTML = `<div class="progress-page fade-in">
    <div class="pg-title">📊 Your Progress</div>
    <div class="stats-strip" style="margin-bottom:16px;border-radius:var(--radius)">
      <div class="sstat blue"><div class="sstat-val">${gAns}</div><div class="sstat-lbl">Attempted</div></div>
      <div class="sstat green"><div class="sstat-val">${gCorrect}</div><div class="sstat-lbl">Correct</div></div>
      <div class="sstat red"><div class="sstat-val">${gAns-gCorrect}</div><div class="sstat-lbl">Wrong</div></div>
      <div class="sstat gold"><div class="sstat-val">${gAns>0?Math.round(gCorrect/gAns*100):0}%</div><div class="sstat-lbl">Accuracy</div></div>
      <div class="sstat"><div class="sstat-val">${fmtTime(Math.round(gTime/1000))}</div><div class="sstat-lbl">Time</div></div>
    </div>
    <div class="pg-actions">
      <button class="btn btn-primary" onclick="exportPDF()">📄 Export PDF</button>
      <button class="btn btn-danger" onclick="clearAllProgress()">🗑 Reset All</button>
    </div>
    <div class="pg-section">
      <div class="pg-section-title">Chapter Breakdown — ${rows.length} chapters active</div>
      ${rows.length===0
        ? '<div style="color:var(--text3);padding:20px 0;font-size:0.85rem">No activity yet. Start solving!</div>'
        : `<table class="pg-table">
            <thead><tr><th>Sub</th><th>Chapter</th><th>Progress</th><th>Accuracy</th><th>Time</th></tr></thead>
            <tbody>${tableRows}</tbody>
           </table>`
      }
    </div>
  </div>`;
}

function clearAllProgress() {
  if (!confirm('Reset ALL progress? This cannot be undone.')) return;
  state.answers = {}; state.bookmarks = []; state.chapterStats = {};
  scheduleSave();
  buildChapterList(state.subject);
  updateSidebarFooter();
  renderProgressPage();
  toast('Progress cleared');
}

// ─── PDF EXPORT ───────────────────────────────────────────────────────────────
function exportPDF() {
  const allQs  = Object.values(JEE_DATA).flatMap(s => s.flatMap(c => c.qs));
  const gAns   = allQs.filter(q => state.answers[q.id]).length;
  const gCor   = allQs.filter(q => state.answers[q.id]?.correct).length;
  const gTime  = Object.values(state.chapterStats).reduce((a,c)=>a+(c.time_ms||0),0);
  const date   = new Date().toLocaleDateString('en-IN',{day:'2-digit',month:'short',year:'numeric'});
  const rows   = [];
  for (const [sub,chapters] of Object.entries(JEE_DATA))
    for (const ch of chapters) {
      const key=sub+'/'+ch.name, cs=state.chapterStats[key]||{};
      const att=ch.qs.filter(q=>state.answers[q.id]).length;
      if (att===0) continue;
      const cor=ch.qs.filter(q=>state.answers[q.id]?.correct).length;
      rows.push({sub,chName:ch.name,total:ch.qs.length,att,cor,time_s:Math.round((cs.time_ms||0)/1000)});
    }

  const colors={Physics:'#6c8eff',Chemistry:'#ff6b9d',Mathematics:'#00d4aa'};
  const trs=rows.sort((a,b)=>b.att-a.att).map(r=>{
    const acc=r.att>0?Math.round(r.cor/r.att*100):0;
    return `<tr><td style="color:${colors[r.sub]};font-weight:700">${r.sub.slice(0,4)}</td><td>${r.chName}</td>
      <td>${r.att}/${r.total} (${Math.round(r.att/r.total*100)}%)</td>
      <td style="color:${acc>=70?'#00a870':acc>=40?'#e6952a':'#e05270'};font-weight:700">${acc}%</td>
      <td>${r.cor}</td><td>${r.att-r.cor}</td><td>${fmtTime(r.time_s)}</td></tr>`;
  }).join('');

  const win=window.open('','_blank');
  win.document.write(`<!DOCTYPE html><html><head><title>JEE Progress Report</title>
  <style>body{font-family:'Segoe UI',sans-serif;padding:32px;color:#1a1d2e;max-width:900px;margin:0 auto}
  h1{font-size:1.5rem}.meta{color:#666;font-size:.85rem;margin-bottom:24px}
  .summary{display:grid;grid-template-columns:repeat(5,1fr);gap:12px;margin-bottom:28px}
  .sc{background:#f4f6fb;border:1px solid #e0e3f0;border-radius:10px;padding:12px;text-align:center}
  .sv{font-size:1.5rem;font-weight:700;font-family:monospace}.sl{font-size:.7rem;color:#888;margin-top:3px}
  table{width:100%;border-collapse:collapse;font-size:.85rem}
  th{background:#f0f2fb;padding:9px 10px;text-align:left;border-bottom:2px solid #dde;font-size:.75rem;text-transform:uppercase}
  td{padding:8px 10px;border-bottom:1px solid #eef}tr:hover td{background:#f8f9fe}</style></head><body>
  <h1>🎯 JEE Main Q-Bank · Progress Report</h1>
  <div class="meta">Generated ${date} · ${rows.length} chapters</div>
  <div class="summary">
    <div class="sc"><div class="sv">${gAns}</div><div class="sl">Attempted</div></div>
    <div class="sc"><div class="sv" style="color:#00a870">${gCor}</div><div class="sl">Correct</div></div>
    <div class="sc"><div class="sv" style="color:#e05270">${gAns-gCor}</div><div class="sl">Wrong</div></div>
    <div class="sc"><div class="sv" style="color:#e6952a">${gAns>0?Math.round(gCor/gAns*100):0}%</div><div class="sl">Accuracy</div></div>
    <div class="sc"><div class="sv">${fmtTime(Math.round(gTime/1000))}</div><div class="sl">Time</div></div>
  </div>
  <table><thead><tr><th>Sub</th><th>Chapter</th><th>Progress</th><th>Accuracy</th><th>Correct</th><th>Wrong</th><th>Time</th></tr></thead>
  <tbody>${trs}</tbody></table>
  <script>window.print();<\/script></body></html>`);
  win.document.close();
}

// ─── WELCOME ──────────────────────────────────────────────────────────────────
function showWelcome() {
  state.searchMode=false; state.showProgress=false;
  stopChapterTimer();
  document.getElementById('global-search').value='';
  document.getElementById('main-panel').innerHTML=`
  <div class="welcome fade-in">
    <div class="welcome-icon">🎯</div>
    <div class="welcome-title">99 Percentile Q-Bank</div>
    <div class="welcome-sub">JEE Main — 3138 questions across Physics, Chemistry & Mathematics.<br>Select a chapter from the sidebar.</div>
    <div class="welcome-cards">
      <div class="wc"><div class="wc-num" style="color:var(--accent-p)">1140</div><div class="wc-lbl">Physics</div></div>
      <div class="wc"><div class="wc-num" style="color:var(--accent-c)">930</div><div class="wc-lbl">Chemistry</div></div>
      <div class="wc"><div class="wc-num" style="color:var(--accent-m)">1068</div><div class="wc-lbl">Mathematics</div></div>
    </div>
    <div class="welcome-tip">Press <kbd style="background:var(--surface);padding:2px 6px;border-radius:4px;font-family:var(--mono)">/</kbd> to search · <kbd style="background:var(--surface);padding:2px 6px;border-radius:4px;font-family:var(--mono)">P</kbd> for progress</div>
  </div>`;
}

// ─── SIDEBAR ──────────────────────────────────────────────────────────────────
function openSidebar()  { document.getElementById('sidebar').classList.add('open'); document.getElementById('sidebar-overlay').classList.add('visible'); }
function closeSidebar() { document.getElementById('sidebar').classList.remove('open'); document.getElementById('sidebar-overlay').classList.remove('visible'); }
function toggleSidebar(){ document.getElementById('sidebar').classList.contains('open') ? closeSidebar() : openSidebar(); }

// ─── HELPERS ──────────────────────────────────────────────────────────────────
function typesetMath(el) { if (window.MathJax?.typesetPromise) MathJax.typesetPromise([el]).catch(()=>{}); }

function fmtTime(s) {
  if (!s || s<=0) return '—';
  if (s<60)    return `${s}s`;
  if (s<3600)  return `${Math.floor(s/60)}m ${s%60}s`;
  return `${Math.floor(s/3600)}h ${Math.floor((s%3600)/60)}m`;
}

function shuffle(arr) {
  const a=[...arr];
  for(let i=a.length-1;i>0;i--){const j=Math.floor(Math.random()*(i+1));[a[i],a[j]]=[a[j],a[i]];}
  return a;
}

function onKeyDown(e) {
  if (['INPUT','TEXTAREA'].includes(document.activeElement.tagName)) return;
  if (e.key==='/')  { e.preventDefault(); document.getElementById('global-search').focus(); }
  if (e.key==='p'||e.key==='P') renderProgressPage();
  if (e.key==='Escape') { document.getElementById('global-search').blur(); closeMockModal(); document.getElementById('backups-modal')?.classList.remove('open'); }
}

function onResize() { if(window.innerWidth>768) closeSidebar(); }

let toastTimer;
function toast(msg) {
  const el=document.getElementById('toast');
  el.textContent=msg; el.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer=setTimeout(()=>el.classList.remove('show'),2400);
}

// ─── SW + INSTALL ──────────────────────────────────────────────────────────────
function registerSW() {
  if ('serviceWorker' in navigator)
    navigator.serviceWorker.register('./sw.js').catch(()=>{});
}
let deferredInstall=null;
function setupInstallPrompt() {
  window.addEventListener('beforeinstallprompt', e => {
    e.preventDefault(); deferredInstall=e;
    document.getElementById('install-banner')?.classList.add('visible');
  });
  window.addEventListener('appinstalled', () => {
    document.getElementById('install-banner')?.classList.remove('visible');
    toast('✓ App installed!');
  });
}
function installApp() {
  if (!deferredInstall) return;
  deferredInstall.prompt();
  deferredInstall.userChoice.then(r => { if(r.outcome==='accepted') toast('Installing…'); deferredInstall=null; });
}
function dismissInstall() { document.getElementById('install-banner')?.classList.remove('visible'); }

// ─── SWIPE ────────────────────────────────────────────────────────────────────
function setupSwipeGesture() {
  let sx=0,sy=0;
  document.addEventListener('touchstart',e=>{sx=e.touches[0].clientX;sy=e.touches[0].clientY;},{passive:true});
  document.addEventListener('touchend',e=>{
    const dx=e.changedTouches[0].clientX-sx, dy=e.changedTouches[0].clientY-sy;
    if(Math.abs(dx)<Math.abs(dy)*1.5||Math.abs(dx)<60) return;
    if(sx<40&&dx>60) openSidebar();
    else if(dx<-60&&document.getElementById('sidebar').classList.contains('open')) closeSidebar();
  },{passive:true});
}

// ─── START ────────────────────────────────────────────────────────────────────
boot();
