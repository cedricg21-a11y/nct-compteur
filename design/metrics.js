/* NCT Compteur — moteur de métriques (ES2015, compatible WebView Android 8) */
var Metrics = (function () {
  var cfg = { ftp: 250, hrMax: 185, weight: 72, wPrime: 20000 };

  var ZONES = [
    { n: 'Z1', name: 'RÉCUP', max: 0.55, col: '#5f7a94' },
    { n: 'Z2', name: 'ENDURANCE', max: 0.75, col: '#00e5c8' },
    { n: 'Z3', name: 'TEMPO', max: 0.90, col: '#9be36a' },
    { n: 'Z4', name: 'SEUIL', max: 1.05, col: '#ffd23e' },
    { n: 'Z5', name: 'PMA', max: 1.20, col: '#ffb02e' },
    { n: 'Z6', name: 'ANAÉROBIE', max: 1.50, col: '#ff7a2e' },
    { n: 'Z7', name: 'SPRINT', max: 99, col: '#e85022' }
  ];

  function blankLap() {
    return { n: 1, t: 0, dist0: 0, dist: 0, pSum: 0, pN: 0, p4Sum: 0, p4N: 0, hrSum: 0, hrN: 0, cSum: 0, cN: 0, roll: [] };
  }

  var S;
  function reset() {
    S = {
      t: 0, pwrBuf: [], roll30: [], p4Sum: 0, p4N: 0, kj: 0, wbal: cfg.wPrime,
      zoneSec: [0, 0, 0, 0, 0, 0, 0], cadSum: 0, cadN: 0, dist: 0, dplus: 0,
      altHist: [], prof: [], lastAlt: null, grade: 0, moving: 0,
      lap: blankLap(), prevLap: null
    };
  }
  reset();

  function zoneOf(p) {
    var r = p / cfg.ftp;
    for (var i = 0; i < ZONES.length; i++) if (r < ZONES[i].max) return i;
    return 6;
  }
  function avg(a, n) {
    var k = Math.min(n, a.length); if (!k) return 0;
    var s = 0; for (var i = a.length - k; i < a.length; i++) s += a[i]; return s / k;
  }

  /* sample: {p, hr, cad, spd (km/h), alt (m), dist (m), rec:bool} */
  function push(x) {
    var p = x.p || 0;
    S.pwrBuf.push(p); if (S.pwrBuf.length > 60) S.pwrBuf.shift();
    if (!x.rec) return;

    S.t++;
    if (x.spd > 2) S.moving++;
    S.kj += p / 1000;
    S.zoneSec[zoneOf(p)]++;
    if (x.cad > 0) { S.cadSum += x.cad; S.cadN++; }

    // NP : moyenne glissante 30 s puis puissance 4
    S.roll30.push(p); if (S.roll30.length > 30) S.roll30.shift();
    if (S.roll30.length === 30) { S.p4Sum += Math.pow(avg(S.roll30, 30), 4); S.p4N++; }

    // W' balance (modèle différentiel Skiba / Froncioni)
    var cp = cfg.ftp, W = cfg.wPrime;
    if (p < cp) S.wbal += (cp - p) * (W - S.wbal) / W; else S.wbal -= (p - cp);
    if (S.wbal > W) S.wbal = W; if (S.wbal < 0) S.wbal = 0;

    // distance, dénivelé, pente
    if (typeof x.dist === 'number') S.dist = x.dist;
    if (typeof x.alt === 'number') {
      if (S.lastAlt === null) S.lastAlt = x.alt;
      var d = x.alt - S.lastAlt;
      if (Math.abs(d) >= 1) { if (d > 0) S.dplus += d; S.lastAlt = x.alt; }
      S.altHist.push({ t: S.t, d: S.dist, a: x.alt });
      if (S.altHist.length > 600) S.altHist.shift();
      // pente sur ~60 m
      for (var i = S.altHist.length - 1; i >= 0; i--) {
        var dd = S.dist - S.altHist[i].d;
        if (dd >= 60) { S.grade = 100 * (x.alt - S.altHist[i].a) / dd; break; }
      }
      var lp = S.prof[S.prof.length - 1];
      if (!lp || S.dist - lp.d >= 20) S.prof.push({ d: S.dist, a: x.alt });
      while (S.prof.length && S.dist - S.prof[0].d > 5000) S.prof.shift();
    }

    // tour
    var L = S.lap;
    L.t++; L.dist = S.dist - L.dist0;
    L.pSum += p; L.pN++;
    L.roll.push(p); if (L.roll.length > 30) L.roll.shift();
    if (L.roll.length === 30) { L.p4Sum += Math.pow(avg(L.roll, 30), 4); L.p4N++; }
    if (x.hr > 0) { L.hrSum += x.hr; L.hrN++; }
    if (x.cad > 0) { L.cSum += x.cad; L.cN++; }
  }

  function lapSummary(L) {
    if (!L) return null;
    var np = L.p4N ? Math.pow(L.p4Sum / L.p4N, 0.25) : (L.pN ? L.pSum / L.pN : 0);
    var ap = L.pN ? L.pSum / L.pN : 0;
    return {
      n: L.n, t: L.t, dist: L.dist, ap: ap, np: np,
      hr: L.hrN ? L.hrSum / L.hrN : 0, cad: L.cN ? L.cSum / L.cN : 0,
      spd: L.t ? (L.dist / L.t) * 3.6 : 0, wkg: ap / cfg.weight
    };
  }

  function newLap() {
    S.prevLap = lapSummary(S.lap);
    var n = S.lap.n + 1;
    S.lap = blankLap(); S.lap.n = n; S.lap.dist0 = S.dist;
    return S.prevLap;
  }

  function vam() {
    // ascension sur les 5 dernières minutes, ramenée à l'heure
    var h = S.altHist; if (h.length < 20) return 0;
    var win = Math.min(300, h.length - 1), a0 = h[h.length - 1 - win].a, up = 0, prev = a0;
    for (var i = h.length - win; i < h.length; i++) { var d = h[i].a - prev; if (d > 0) up += d; prev = h[i].a; }
    return up * 3600 / win;
  }

  function snapshot() {
    var np = S.p4N ? Math.pow(S.p4Sum / S.p4N, 0.25) : avg(S.roll30, 30);
    var IF = np / cfg.ftp;
    var tss = (S.t * np * IF) / (cfg.ftp * 3600) * 100;
    var p3 = avg(S.pwrBuf, 3);
    return {
      p3: p3, p30: avg(S.pwrBuf, 30), zone: zoneOf(p3), np: np, IF: IF, tss: tss,
      kj: S.kj, wbal: S.wbal, wbalPct: 100 * S.wbal / cfg.wPrime,
      zoneSec: S.zoneSec, cadAvg: S.cadN ? S.cadSum / S.cadN : 0,
      spdAvg: S.moving ? (S.dist / S.moving) * 3.6 : 0,
      dist: S.dist, dplus: S.dplus, grade: S.grade, vam: vam(), prof: S.prof,
      lap: lapSummary(S.lap), prevLap: S.prevLap, t: S.t
    };
  }

  return {
    cfg: cfg, ZONES: ZONES, push: push, snapshot: snapshot, newLap: newLap, reset: reset, zoneOf: zoneOf,
    setCfg: function (o) { for (var k in o) if (o.hasOwnProperty(k)) cfg[k] = o[k]; }
  };
})();
