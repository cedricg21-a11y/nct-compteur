/* Simulateur de sortie — utilisé uniquement hors appli native (aperçu navigateur / démo) */
var Sim = (function () {
  if (window.Android) return { startStop: function () {} };
  var q = {}; location.search.replace(/[?&](\w+)=([^&]*)/g, function (_, k, v) { q[k] = v; });
  var state = 'idle', t = 0, el = 0, dist = 0, alt = 62, hr = 95, wbalDemo = 0;

  function powerAt(s) {
    // échauffement, tempo, intervalles 3' PMA, grimpée, sprint
    var base = s < 600 ? 150 + s / 10 : 205;
    var ph = s % 900;
    if (s > 900 && ph < 180) base = 318;          // 3' PMA
    else if (s > 900 && ph < 360) base = 160;     // récup
    if (s % 1700 > 1640) base = 680;              // sprint
    return Math.max(0, base + 18 * Math.sin(s / 7) + (Math.random() - 0.5) * 30);
  }
  function gradeAt(d) { var k = (d / 1000) % 9; return k > 5 ? 6.5 + 2.5 * Math.sin(d / 400) : 0.6 * Math.sin(d / 900); }

  function step(push) {
    var p = 0, cad = 0, spd = 0;
    if (state === 'rec') {
      t++; el++;
      p = powerAt(t);
      var g = gradeAt(dist);
      spd = Math.max(8, 34 - g * 2.6 + (p - 200) / 25);
      cad = p > 20 ? 86 + (p - 220) / 30 + (Math.random() - 0.5) * 4 : 0;
      dist += spd / 3.6; alt += g / 100 * spd / 3.6;
      var target = 95 + (p / 260) * 70; hr += (target - hr) * 0.04;
    }
    var x = {
      p: Math.round(p), hr: Math.round(hr), cad: Math.round(cad), spd: spd, alt: alt, dist: dist,
      state: state, elapsed: el, bat: 87, sensors: { pwr: true, hr: true, gps: true }
    };
    if (push) NCT.onSample(x); else Metrics.push({ p: x.p, hr: x.hr, cad: x.cad, spd: x.spd, alt: x.alt, dist: x.dist, rec: state === 'rec' });
    return x;
  }

  if (q.nosplash) window.NCT_NOSPLASH = 1;
  if (q.demo) state = 'rec';
  // avance rapide pour les captures : ?demo=1&ff=2400&lap=1800
  var ff = +q.ff || 0;
  for (var i = 0; i < ff; i++) {
    step(false);
    if (q.lap && t === +q.lap) Metrics.newLap();
  }
  setInterval(function () { step(true); }, 1000);
  step(true);
  if (q.page) NCT.showPage(+q.page, 1);

  return {
    startStop: function () { state = state === 'rec' ? 'pause' : 'rec'; step(true); }
  };
})();
