/* Native Media3 oynatıcı köprüsü: <video> elemanını "sanal" hale getirir.
   Ana script'in videoEl.src / play() / pause() / currentTime vb. çağrıları Android Media3'e gider;
   native durum 500 ms'de bir window.__np() ile geri gelir ve standart video olaylarına çevrilir. */
(function () {
  "use strict";
  var N = window.TVMateNative;
  if (!N || !N.nativePlayer) return;
  var v = videoEl, root = document.documentElement;
  var S = { url: "", paused: true, pos: 0, dur: NaN, buf: 0, muted: false, vol: 1, st: 1, ready: false, vw: 0, vh: 0, err: false };
  var MODES = ["fit", "fill", "zoom"], LABELS = ["Sığdır", "Doldur", "Yakınlaştır"], ai = 0, lastRect = "", active = false;

  function fire(t) { v.dispatchEvent(new Event(t)); }
  function def(p, g, s) { Object.defineProperty(v, p, { configurable: true, get: g, set: s || function () {} }); }
  function range(e) { return { length: 1, start: function () { return 0; }, end: function () { return e; } }; }
  function clearBg() { for (var e = v.parentElement; e; e = e.parentElement) e.classList.add("np-clear"); }

  function sync() {
    if (!active) return;
    var r = v.getBoundingClientRect(), d = window.devicePixelRatio || 1;
    var k = (r.width > 2 && r.height > 2)
      ? [r.left, r.top, r.width, r.height].map(function (n) { return Math.round(n * d); }).join(",") : "0,0,0,0";
    if (k === lastRect) return;
    lastRect = k;
    var a = k.split(",").map(Number);
    N.setRect(a[0], a[1], a[2], a[3]);
  }
  function stopNative() { S.url = ""; S.ready = false; S.st = 1; N.stop(); lastRect = ""; }

  def("src", function () { return S.url; }, function (u) {
    if (!u) { stopNative(); return; }
    S.url = u; S.ready = false; S.err = false; S.pos = 0; S.dur = NaN; S.paused = false;
    active = true; root.classList.add("native-player"); clearBg();
    N.load(u); lastRect = ""; sync();
  });
  def("currentSrc", function () { return S.url; });
  def("paused", function () { return S.paused; });
  def("ended", function () { return S.st === 4; });
  def("readyState", function () { return S.ready ? 4 : 0; });
  def("duration", function () { return S.dur; });
  def("currentTime", function () { return S.pos; }, function (t) { S.pos = t; N.seekTo(Math.round(t * 1000)); });
  def("buffered", function () { return range(S.buf); });
  def("seekable", function () { return range(S.dur > 0 ? S.dur : S.pos); });
  def("videoWidth", function () { return S.vw; });
  def("videoHeight", function () { return S.vh; });
  def("muted", function () { return S.muted; }, function (m) { S.muted = !!m; N.setMuted(S.muted); });
  def("volume", function () { return S.vol; }, function (x) { S.vol = x; N.setVolume(x); });

  v.load = function () {};
  v.play = function () { S.paused = false; N.play(); fire("play"); return Promise.resolve(); };
  v.pause = function () { S.paused = true; N.pause(); fire("pause"); };
  var ra = v.removeAttribute.bind(v);
  v.removeAttribute = function (n) { if (n === "src") { stopNative(); return; } return ra(n); };

  window.startBufferGuard = function () {};   // web tampon temizleyicisi native'de gereksiz

  window.__np = function (d) {
    var prevSt = S.st, wasPaused = S.paused;
    S.pos = d.pos / 1000; S.dur = d.dur < 0 ? NaN : d.dur / 1000; S.buf = d.buf / 1000;
    S.paused = !d.pwr; S.vw = d.vw || 0; S.vh = d.vh || 0; S.st = d.st;
    if (d.err && !S.err) { S.err = true; fire("error"); showToast("Oynatma hatası: " + d.err, "error"); return; }
    if (d.st === 2 && prevSt !== 2) fire("waiting");
    if (d.st === 3) {
      if (!S.ready) { S.ready = true; fire("loadedmetadata"); fire("canplay"); }
      if (prevSt !== 3 && d.pwr) fire("playing");
    }
    if (d.st === 4 && prevSt !== 4) fire("ended");
    if (wasPaused !== S.paused) fire(S.paused ? "pause" : "play");
    fire("timeupdate");
    sync();
  };

  /* En-boy oranı: kumandada SARI tuş (Java'da ASPECT) veya klavyede A */
  function cycle() { ai = (ai + 1) % 3; N.setResize(MODES[ai]); showToast("Görüntü: " + LABELS[ai], "info"); }
  var prevKey = window.onRemoteKey;
  window.onRemoteKey = function (n, r) { if (n === "ASPECT") { if (!r) cycle(); return; } prevKey(n, r); };
  window.addEventListener("keydown", function (e) {
    if ((e.key === "a" || e.key === "A") && !/INPUT|TEXTAREA/.test(e.target.tagName)) { e.preventDefault(); cycle(); }
  }, true);

  setInterval(sync, 300);
  window.addEventListener("resize", sync);
  if (window.ResizeObserver) new ResizeObserver(sync).observe(v);
})();
