/* NCT Compteur — interface (ES2015, compatible WebView Android 8) */
var NCT = (function () {
  var $ = function (id) { return document.getElementById(id); };
  var app = $('app');
  var pages = [].slice.call(document.querySelectorAll('.page'));
  var cur = 0, state = 'idle', lastZone = -1, native = !!window.Android;

  /* ---------- arc géométrie ---------- */
  var CX = 240, CY = 196, R = 150, A0 = 135, SWEEP = 270, SCALE = 1.6;
  var ARC_LEN = R * SWEEP * Math.PI / 180;
  function pt(deg, r) { var a = deg * Math.PI / 180; return [CX + r * Math.cos(a), CY + r * Math.sin(a)]; }
  function arcPath(r) {
    var s = pt(A0, r), e = pt(A0 + SWEEP, r);
    return 'M' + s[0].toFixed(1) + ' ' + s[1].toFixed(1) + ' A' + r + ' ' + r + ' 0 1 1 ' + e[0].toFixed(1) + ' ' + e[1].toFixed(1);
  }
  ['arcTrack', 'arcVal', 'arcGlow'].forEach(function (id) { $(id).setAttribute('d', arcPath(R)); });
  $('arcVal').style.strokeDasharray = ARC_LEN + ' ' + ARC_LEN;
  $('arcGlow').style.strokeDasharray = ARC_LEN + ' ' + ARC_LEN;

  function drawTicks() {
    var g = $('zoneTicks'), h = '';
    Metrics.ZONES.forEach(function (z, i) {
      if (i === 6) return;
      var deg = A0 + SWEEP * Math.min(z.max / SCALE, 1);
      var a = pt(deg, R + 18), b = pt(deg, R + 30);
      h += '<line class="ztick" x1="' + a[0] + '" y1="' + a[1] + '" x2="' + b[0] + '" y2="' + b[1] + '"/>';
      var prevMax = i ? Metrics.ZONES[i - 1].max : 0;
      var mid = A0 + SWEEP * ((prevMax + z.max) / 2 / SCALE);
      var l = pt(mid, R + 38);
      h += '<text class="zlab" x="' + l[0] + '" y="' + (l[1] + 5) + '">' + (i + 1) + '</text>';
    });
    g.innerHTML = h;
  }
  drawTicks();

  var arcShown = 0;
  function setArc(ratio) {
    ratio = Math.max(0, Math.min(1, ratio));
    arcShown += (ratio - arcShown) * 0.35;
    var off = ARC_LEN * (1 - arcShown);
    $('arcVal').style.strokeDashoffset = off;
    $('arcGlow').style.strokeDashoffset = off;
    var h = pt(A0 + SWEEP * arcShown, R);
    $('arcHead').setAttribute('cx', h[0]); $('arcHead').setAttribute('cy', h[1]);
  }

  /* ---------- nombres animés ---------- */
  var tweens = {};
  function num(id, v, dec) {
    dec = dec || 0;
    var t = tweens[id] || (tweens[id] = { from: v, to: v, start: 0, dec: dec });
    t.from = t.cur === undefined ? v : t.cur; t.to = v; t.start = performance.now(); t.dec = dec;
  }
  function tick(now) {
    for (var id in tweens) {
      var t = tweens[id], k = Math.min(1, (now - t.start) / 450);
      k = 1 - Math.pow(1 - k, 3);
      t.cur = t.from + (t.to - t.from) * k;
      var s = t.cur.toFixed(t.dec);
      if (t.last !== s) { $(id).textContent = s; t.last = s; }
    }
    setArc(targetArc);
    requestAnimationFrame(tick);
  }
  var targetArc = 0;
  requestAnimationFrame(tick);

  function txt(id, s) { var e = $(id); if (e.textContent !== s) e.textContent = s; }
  function hms(s) {
    s = Math.floor(s); var h = Math.floor(s / 3600), m = Math.floor(s / 60) % 60, x = s % 60;
    return h + ':' + (m < 10 ? '0' : '') + m + ':' + (x < 10 ? '0' : '') + x;
  }
  function ms(s) {
    s = Math.floor(s); var h = Math.floor(s / 3600), m = Math.floor(s / 60) % 60, x = s % 60;
    return (h ? h + ':' + (m < 10 ? '0' : '') : '') + m + ':' + (x < 10 ? '0' : '') + x;
  }

  /* ---------- pages ---------- */
  var dots = $('dots');
  pages.forEach(function () { dots.appendChild(document.createElement('i')); });
  function showPage(i, dir) {
    i = (i + pages.length) % pages.length;
    var np = pages[i], old = pages[cur];
    // la page entrante part du bon côté
    np.style.transition = 'none';
    np.style.transform = 'translateX(' + (dir > 0 ? 100 : -100) + '%)'; np.style.opacity = 0;
    np.offsetWidth; // reflow
    np.style.transition = ''; np.style.transform = 'translateX(0)'; np.style.opacity = 1;
    if (old !== np) { old.style.transform = 'translateX(' + (dir > 0 ? -100 : 100) + '%)'; old.style.opacity = 0; }
    cur = i;
    if (i === 2) drawProfile(Metrics.snapshot().prof);
    txt('pageName', np.getAttribute('data-name'));
    [].forEach.call(dots.children, function (d, k) { d.className = k === i ? 'on' : ''; });
  }

  /* ---------- toast / tour ---------- */
  var toastT;
  function toast(s, col) {
    var t = $('toast'); t.textContent = s; t.style.background = col || '';
    t.classList.add('show'); clearTimeout(toastT); toastT = setTimeout(function () { t.classList.remove('show'); }, 1800);
  }
  function lapFlash(prev, n) {
    txt('lfN', String(n));
    txt('lfS', prev ? ms(prev.t) + '  ·  ' + Math.round(prev.ap) + ' W  ·  ' + prev.spd.toFixed(1) + ' km/h' : '');
    $('lapFlash').classList.add('show');
    setTimeout(function () { $('lapFlash').classList.remove('show'); }, 2200);
  }

  /* ---------- boutons ---------- */
  var confirmUntil = 0, confirmKind = '';
  function confirmBox(kind, title, sub) {
    confirmKind = kind; confirmUntil = Date.now() + 4000;
    txt('cfT', title); $('cfS').innerHTML = sub; $('confirm').classList.add('show');
    setTimeout(function () { if (Date.now() >= confirmUntil) $('confirm').classList.remove('show'); }, 4100);
  }
  function confirmed(kind) {
    if (confirmKind === kind && Date.now() < confirmUntil) { confirmUntil = 0; $('confirm').classList.remove('show'); return true; }
    return false;
  }

  function button(b) {
    if (learnStep >= 0) return;
    if (b === 'next') showPage(cur + 1, 1);
    else if (b === 'prev') showPage(cur - 1, -1);
    else if (b === 'startstop') {
      $('confirm').classList.remove('show'); confirmUntil = 0;
      if (native) Android.startStop(); else Sim.startStop();
    } else if (b === 'lap') {
      if (state === 'rec') {
        var prev = Metrics.newLap();
        if (native) Android.lap();
        lapFlash(prev, prev.n + 1);
      } else if (state === 'pause') {
        if (confirmed('finish')) {
          var f = native ? Android.finish() : '';
          Metrics.reset(); lastZone = -1;
          toast(f ? 'SORTIE ENREGISTRÉE ✓' : 'SORTIE TERMINÉE');
        } else confirmBox('finish', 'TERMINER LA SORTIE ?', 'Appuie encore sur TOUR pour enregistrer le fichier .FIT<br>START pour reprendre');
      } else {
        if (confirmed('learn')) startLearn();
        else confirmBox('learn', 'RÉGLER LES BOUTONS ?', 'Appuie encore sur TOUR pour reconfigurer les 4 boutons');
      }
    }
  }

  /* ---------- apprentissage des boutons ---------- */
  var LEARN = [['next', 'PAGE SUIVANTE'], ['prev', 'PAGE PRÉCÉDENTE'], ['startstop', 'START / PAUSE'], ['lap', 'TOUR']];
  var learnStep = -1, learnMap = {};
  function startLearn() {
    learnStep = 0; learnMap = {};
    if (native) Android.setLearning(true);
    $('learn').classList.add('show'); learnShow();
  }
  function learnShow() {
    txt('lnStep', (learnStep + 1) + ' / 4');
    $('lnT').innerHTML = 'Appuie sur le bouton<br><b>' + LEARN[learnStep][1] + '</b>';
    txt('lnS', '');
  }
  function onKey(code) {
    if (learnStep < 0) { toast('TOUCHE ' + code + ' NON ASSIGNÉE', '#ffb02e'); return; }
    if (learnMap[code]) { txt('lnS', 'Déjà utilisé — choisis un autre bouton'); return; }
    learnMap[code] = LEARN[learnStep][0];
    learnStep++;
    if (learnStep >= LEARN.length) {
      learnStep = -1;
      if (native) { Android.saveKeyMap(JSON.stringify(learnMap)); Android.setLearning(false); }
      $('learn').classList.remove('show');
      toast('BOUTONS ENREGISTRÉS ✓');
    } else learnShow();
  }
  function nativeReady(map) {
    if (!map) setTimeout(startLearn, 2800); // premier lancement : on apprend les boutons
  }
  function onEvent(kind, s) {
    if (kind === 'sensor') toast(s, s.charAt(0) === '✕' ? '#e85022' : '');
    else toast(s);
  }
  document.addEventListener('keydown', function (e) {
    var k = e.key;
    if (k === 'ArrowRight' || k === 'PageDown') button('next');
    else if (k === 'ArrowLeft' || k === 'PageUp') button('prev');
    else if (k === ' ' || k === 'Enter') button('startstop');
    else if (k === 'l' || k === 'L') button('lap');
  });
  // swipe tactile
  var sx = null;
  document.addEventListener('touchstart', function (e) { sx = e.touches[0].clientX; }, { passive: true });
  document.addEventListener('touchend', function (e) {
    if (sx === null) return; var dx = e.changedTouches[0].clientX - sx; sx = null;
    if (Math.abs(dx) > 60) button(dx < 0 ? 'next' : 'prev');
  }, { passive: true });

  /* ---------- construction dynamique ---------- */
  var zb = $('zoneBars'), zh = '';
  Metrics.ZONES.forEach(function (z, i) {
    zh += '<div class="zb" id="zb' + i + '"><div class="zn" style="color:' + z.col + '">' + z.n + '</div>' +
      '<div class="zt"><i id="zbi' + i + '" style="background:' + z.col + '"></i></div><div class="zv" id="zbv' + i + '">0:00</div></div>';
  });
  zb.innerHTML = zh;
  var wb = $('wbar'); for (var i = 0; i < 24; i++) wb.appendChild(document.createElement('i'));

  /* ---------- statut ---------- */
  function setState(s) {
    if (s === state) return;
    var was = state; state = s;
    var p = $('recPill'); p.className = 'pill ' + s;
    p.querySelector('span').textContent = s === 'rec' ? 'REC' : s === 'pause' ? 'PAUSE' : 'PRÊT';
    if (s === 'rec' && !/ff=/.test(location.search)) toast(was === 'pause' ? 'REPRISE' : "C'EST PARTI !");
    if (s === 'pause') toast('PAUSE', '#ffb02e');
  }

  function wbalColor(p) { return p > 60 ? '#00e5c8' : p > 30 ? '#ffb02e' : '#e85022'; }
  function wbalMsg(p) {
    if (p > 90) return 'Réservoir plein — tu peux attaquer.';
    if (p > 60) return 'Encore de la marge pour une relance.';
    if (p > 30) return 'Ça puise. Gère avant la prochaine attaque.';
    if (p > 10) return 'Dans le rouge — récupère sous ta FTP.';
    return 'Réservoir vide. Plus rien à sortir.';
  }
  function gradeColor(g) { return g < 3 ? '#00e5c8' : g < 6 ? '#ffd23e' : g < 9 ? '#ff8a2e' : '#e85022'; }
  function delta(id, a, b, dec, unit) {
    var e = $(id);
    if (!b || !a) { e.textContent = ''; e.className = 'delta'; return; }
    var d = a - b; var s = (d >= 0 ? '▲ +' : '▼ ') + d.toFixed(dec) + (unit || '') + ' vs tour préc.';
    e.textContent = s; e.className = 'delta ' + (d >= 0 ? 'up' : 'down');
  }

  function drawProfile(prof) {
    if (prof.length < 2) return;
    var W = 440, H = 200, pad = 14;
    var d0 = prof[0].d, d1 = prof[prof.length - 1].d, span = Math.max(1000, d1 - d0);
    var mn = 1e9, mx = -1e9;
    prof.forEach(function (p) { if (p.a < mn) mn = p.a; if (p.a > mx) mx = p.a; });
    if (mx - mn < 30) { var m = (mx + mn) / 2; mn = m - 15; mx = m + 15; }
    var x = function (d) { return W - (d1 - d) / span * W; };
    var y = function (a) { return H - pad - (a - mn) / (mx - mn) * (H - 2 * pad); };
    var path = '';
    prof.forEach(function (p, i) { path += (i ? 'L' : 'M') + x(p.d).toFixed(1) + ' ' + y(p.a).toFixed(1); });
    $('profLine').setAttribute('d', path);
    $('profFill').setAttribute('d', path + 'L' + W + ' ' + H + 'L' + x(prof[0].d).toFixed(1) + ' ' + H + 'Z');
    var last = prof[prof.length - 1];
    $('profDot').setAttribute('cx', x(last.d) - 4); $('profDot').setAttribute('cy', y(last.a));
  }

  /* ---------- réception des données (1 Hz) ---------- */
  function onSample(x) {
    if (typeof x === 'string') x = JSON.parse(x);
    setState(x.state || 'idle');
    Metrics.push({ p: x.p, hr: x.hr, cad: x.cad, spd: x.spd, alt: x.alt, dist: x.dist, rec: x.state === 'rec' });
    var m = Metrics.snapshot(), cfg = Metrics.cfg, Z = Metrics.ZONES[m.zone];

    // LIVE
    num('vPower', m.p3); targetArc = m.p3 / (cfg.ftp * SCALE);
    num('vWkg', m.p3 / cfg.weight, 1); num('vPftp', 100 * m.p3 / cfg.ftp);
    txt('vZone', Z.n + ' · ' + Z.name);
    if (m.zone !== lastZone) {
      app.style.setProperty('--zc', Z.col);
      if (lastZone >= 0) { app.classList.add('zflash'); setTimeout(function () { app.classList.remove('zflash'); }, 700); }
      lastZone = m.zone;
    }
    if (x.hr > 0) {
      num('vHr', x.hr);
      var hz = Math.min(4, Math.max(0, Math.floor((x.hr / cfg.hrMax - 0.5) / 0.1)));
      [].forEach.call($('hrBar').children, function (s, k) { s.className = k <= hz ? 'on' : ''; });
    } else txt('vHr', '--');
    if (x.cad >= 0 && x.sensors && x.sensors.pwr) num('vCad', x.cad); else txt('vCad', '--');
    txt('vCadAvg', 'moy ' + (m.cadAvg ? Math.round(m.cadAvg) : '--'));
    num('vSpd', x.spd || 0, 1); txt('vSpdAvg', 'moy ' + m.spdAvg.toFixed(1));
    num('vDist', m.dist / 1000, 2); txt('vDplus', 'D+ ' + Math.round(m.dplus) + ' m');

    // EFFORT
    num('vNp', m.np); num('vIf', m.IF, 2); num('vTss', m.tss);
    num('vWbal', m.wbalPct);
    $('vWbal').style.color = wbalColor(m.wbalPct);
    txt('vWbalKj', (m.wbal / 1000).toFixed(1) + ' / ' + (cfg.wPrime / 1000).toFixed(1) + ' kJ');
    txt('vWmsg', wbalMsg(m.wbalPct));
    var on = Math.round(m.wbalPct / 100 * 24);
    [].forEach.call(wb.children, function (s, k) { s.style.background = k < on ? wbalColor(100 * k / 24 + 4) : ''; });
    var zmax = Math.max.apply(null, m.zoneSec) || 1;
    m.zoneSec.forEach(function (s, k) {
      $('zbi' + k).style.width = (100 * s / zmax) + '%';
      txt('zbv' + k, ms(s));
      $('zb' + k).className = 'zb' + (k === m.zone ? ' on' : '');
    });

    // GRIMPÉE
    num('vGrade', m.grade, 1); $('gradeBox').style.setProperty('--gfs', (m.grade < 0 || m.grade >= 10) ? '118px' : '150px'); $('gradeBox').style.setProperty('--zg', gradeColor(m.grade));
    num('vVam', m.vam); num('vAlt', x.alt || 0); num('vDplus2', m.dplus); num('vP30', m.p30);
    if (cur === 2) drawProfile(m.prof);

    // TOUR
    var L = m.lap, P = m.prevLap;
    txt('vLapN', String(L.n)); txt('vLapTime', ms(L.t)); txt('vLapDist', (L.dist / 1000).toFixed(2) + ' km');
    num('vLapP', L.ap); num('vLapNp', L.np); num('vLapS', L.spd, 1); num('vLapWkg', L.wkg, 1);
    if (L.hr) num('vLapHr', L.hr); else txt('vLapHr', '--');
    if (L.cad) num('vLapC', L.cad); else txt('vLapC', '--');
    delta('dLapP', L.ap, P && P.ap, 0, ' W'); delta('dLapNp', L.np, P && P.np, 0, ' W');
    delta('dLapHr', L.hr, P && P.hr, 0); delta('dLapS', L.spd, P && P.spd, 1);
    delta('dLapC', L.cad, P && P.cad, 0); delta('dLapWkg', L.wkg, P && P.wkg, 1);

    // pied de page / statut
    txt('vTime', hms(x.elapsed || 0));
    var d = new Date(); txt('vClock', (d.getHours() < 10 ? '0' : '') + d.getHours() + ':' + (d.getMinutes() < 10 ? '0' : '') + d.getMinutes());
    var sn = x.sensors || {};
    $('icPwr').setAttribute('class', 'ic' + (sn.pwr ? ' on' : ' blink'));
    $('icHr').setAttribute('class', 'ic' + (sn.hr ? ' on' : ' blink'));
    $('icGps').setAttribute('class', 'ic' + (sn.gps ? ' on' : ' blink'));
    if (x.bat >= 0) { var bs = $('batt').querySelector('span'), bt = Math.round(x.bat) + '%'; if (bs.textContent !== bt) bs.textContent = bt; }
  }

  showPage(0, 1);

  // splash
  setTimeout(function () { $('splash').classList.add('gone'); app.classList.remove('booting'); }, /nosplash/.test(location.search) ? 0 : 2600);

  return { onSample: onSample, onButton: button, onKey: onKey, onEvent: onEvent, nativeReady: nativeReady, showPage: showPage, toast: toast, setCfg: Metrics.setCfg };
})();
