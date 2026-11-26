/* TVMate Pro - kumanda / klavye / dokunmatik katmanı.
   tvmate-pro-player.html'deki ana script'ten SONRA yüklenir (state, videoEl, playStream... global'lerini kullanır).
   Android'de tuşlar MainActivity'den window.onRemoteKey(ad, tekrar) ile gelir; tarayıcıda klavye aynı yola bağlanır. */
(function () {
  "use strict";
  var N = window.TVMateNative || null;
  var IS_TV = !!(N && N.isTv && N.isTv());
  var lastBack = 0, prevFocus = {};
  if (IS_TV) { document.documentElement.classList.add("is-tv"); videoEl.removeAttribute("controls"); }

  /* ---------- yardımcılar ---------- */
  function isText(el) {
    if (!el) return false;
    if (el.tagName === "TEXTAREA") return true;
    return el.tagName === "INPUT" && !/^(button|checkbox|radio|file|submit|range)$/.test(el.type);
  }
  function isOpen(el) { return el.classList.contains("open"); }
  function visible(el) {
    var r = el.getBoundingClientRect();
    if (r.width <= 0 || r.height <= 0 || el.disabled) return false;
    var cs = getComputedStyle(el);
    return cs.visibility !== "hidden" && cs.display !== "none";
  }
  function focusEl(el) {
    if (!el) return;
    el.focus({ preventScroll: true });
    el.scrollIntoView({ block: "nearest", inline: "nearest" });
  }
  function remoteMode(on) { document.body.classList.toggle("remote-mode", on); }
  window.addEventListener("pointerdown", function () { remoteMode(false); }, true);

  /* Uygulama içinde tam ekranı her zaman CSS ile yap (WebView'de element fullscreen güvenilmez) */
  if (N) {
    window.enterFullscreen = function () { videoBox.classList.add("pseudo-fullscreen"); };
    new MutationObserver(function () {
      var fs = isFullscreenActive();
      if (N.setFullscreen) N.setFullscreen(fs);
      if (!fs) { fsDrawer.classList.remove("open"); hideFsHud(); }
    }).observe(videoBox, { attributes: true, attributeFilter: ["class"] });
  }

  /* ---------- odak kapsamı ---------- */
  function scope() {
    if (isOpen(playlistModal)) return playlistModal;
    if (isOpen(vpnModal)) return vpnModal;
    if (isFullscreenActive()) {
      if (isOpen(fsDrawer)) return fsDrawer;
      return videoBox.classList.contains("fs-hud-visible") ? fsHudWrapper : null;
    }
    return document.body;
  }
  function items(sc) {
    return Array.prototype.filter.call(sc.querySelectorAll(".focusable,input,textarea,select,button"), function (el) {
      if (sc === document.body) {
        if (el.closest(".modal-backdrop") || el.closest("#fsChannelDrawer")) return false;
      }
      return visible(el);
    });
  }
  function pick(sc, list) {
    var q = sc === fsDrawer ? ".fs-drawer-item.active"
          : sc === fsHudWrapper ? "#fsPlayPauseBtn"
          : sc === document.body ? ".list-item.active, .vod-card" : null;
    var el = q ? Array.prototype.filter.call(sc.querySelectorAll(q), visible)[0] : null;
    return el || (list || items(sc))[0];
  }

  /* ---------- yön tuşları (beam / uzamsal gezinme) ---------- */
  function move(dir) {
    var sc = scope();
    if (!sc) return false;
    var list = items(sc), a = document.activeElement;
    if (!a || a === document.body || list.indexOf(a) < 0) { focusEl(pick(sc, list)); return true; }
    var r = a.getBoundingClientRect(), horiz = dir === "LEFT" || dir === "RIGHT", best = null, bs = 1e9;
    list.forEach(function (el) {
      if (el === a) return;
      var q = el.getBoundingClientRect();
      var dx = q.left + q.width / 2 - (r.left + r.width / 2), dy = q.top + q.height / 2 - (r.top + r.height / 2);
      var d = horiz ? dx : dy;
      if ((dir === "RIGHT" || dir === "DOWN") ? d <= 4 : d >= -4) return;
      var gap = horiz ? (dx > 0 ? q.left - r.right : r.left - q.right) : (dy > 0 ? q.top - r.bottom : r.top - q.bottom);
      var cross = Math.abs(horiz ? dy : dx);
      var ov = horiz ? (q.top < r.bottom && q.bottom > r.top) : (q.left < r.right && q.right > r.left);
      var s = Math.max(0, gap) + cross * (ov ? 0.3 : 3);
      if (s < bs) { bs = s; best = el; }
    });
    if (best) { focusEl(best); return true; }
    return false;
  }

  function refocusSoon() {
    setTimeout(function () {
      var a = document.activeElement, sc = scope();
      if ((!a || a === document.body) && sc) focusEl(pick(sc));
    }, 80);
  }

  /* ---------- OK ---------- */
  function ok() {
    var a = document.activeElement, sc = scope();
    if (isFullscreenActive() && !sc) {            // HUD kapalı: aç ve oynat/duraklat düğmesine odaklan
      showFsHud(6000);
      setTimeout(function () { focusEl(document.getElementById("fsPlayPauseBtn")); }, 40);
      return true;
    }
    if (!a || a === document.body) { if (sc) focusEl(pick(sc)); return true; }
    if (isText(a)) { a.click(); return true; }
    a.click();
    refocusSoon();
    return true;
  }

  /* ---------- Geri ---------- */
  function back() {
    var a = document.activeElement;
    if (isText(a)) { a.blur(); return true; }
    if (isOpen(playlistModal)) { playlistModal.classList.remove("open"); return true; }
    if (isOpen(vpnModal)) { vpnModal.classList.remove("open"); return true; }
    if (isFullscreenActive()) {
      if (isOpen(fsDrawer)) { fsDrawer.classList.remove("open"); return true; }
      if (videoBox.classList.contains("fs-hud-visible")) { hideFsHud(); if (a && a !== document.body) a.blur(); return true; }
      exitFullscreen();
      refocusSoon();
      return true;
    }
    var inList = a && a.closest && a.closest("#groupList,#channelList,#vodGroupList,#vodGrid");
    if (a && a !== document.body && !inList) { focusEl(pick(document.body)); return true; }
    if (state.activeTab !== "live") { switchSection("live"); refocusSoon(); return true; }
    var now = Date.now();
    if (now - lastBack < 2000) return false;      // ikinci Geri -> uygulamadan çık
    lastBack = now;
    showToast("Çıkmak için tekrar Geri tuşuna basın", "info");
    return true;
  }
  window.onRemoteBack = function () { try { return back(); } catch (e) { return false; } };

  /* ---------- kanal numarası ---------- */
  function dial(d) {
    var el = document.getElementById("numDialOsd");
    state.dialBuffer += d;
    el.textContent = "CH " + state.dialBuffer;
    el.style.display = "block";
    clearTimeout(state.dialTimer);
    state.dialTimer = setTimeout(function () {
      var i = parseInt(state.dialBuffer, 10) - 1, list = getFilteredChannels();
      state.dialBuffer = ""; el.style.display = "none";
      if (i >= 0 && i < list.length) playStream(list[i].url, list[i].name, "Grup: " + list[i].category, list[i]);
    }, 1200);
  }

  function zapOk() { return state.activeTab === "live" && getFilteredChannels().length > 0; }
  function togglePlay() { if (videoEl.paused) videoEl.play().catch(function () {}); else videoEl.pause(); if (isFullscreenActive()) showFsHud(4500); }

  /* ---------- ana dağıtıcı ---------- */
  function press(name, rep) {
    remoteMode(true);
    var fs = isFullscreenActive(), sc = scope();
    if (name.indexOf("NUM_") === 0) { if (!rep) dial(name.slice(4)); return true; }
    switch (name) {
      case "OK": return rep ? true : ok();
      case "BACK": return back();
      case "MENU":
        if (fs) { fsDrawer.classList.toggle("open"); if (isOpen(fsDrawer)) setTimeout(function () { focusEl(pick(fsDrawer)); }, 80); }
        else focusEl(document.getElementById("openPlaylistBtn"));
        return true;
      case "FULLSCREEN": toggleFullscreen(); return true;
      case "MUTE": videoEl.muted = !videoEl.muted; updateFsHudInfo(); showToast(videoEl.muted ? "Ses Kapatıldı" : "Ses Açıldı", "info"); return true;
      case "PLAY_PAUSE": togglePlay(); return true;
      case "PLAY": videoEl.play().catch(function () {}); return true;
      case "PAUSE": case "STOP": videoEl.pause(); return true;
      case "FF": stepRelativeSeconds(30); return true;
      case "REW": stepRelativeSeconds(-30); return true;
      case "CH_UP": if (zapOk()) stepLiveChannelBy(1); return true;
      case "CH_DOWN": if (zapOk()) stepLiveChannelBy(-1); return true;
    }
    // UP / DOWN / LEFT / RIGHT
    if (fs && !sc) {                               // tam ekran, HUD kapalı: doğrudan kontrol
      if (name === "UP" || name === "DOWN") { if (zapOk()) stepLiveChannelBy(name === "DOWN" ? 1 : -1); else showFsHud(4500); }
      else { stepRelativeSeconds(name === "RIGHT" ? 15 : -15); showFsHud(2500); }
      return true;
    }
    if (fs && sc === fsHudWrapper) showFsHud(6000);
    var moved = move(name);
    if (!moved && sc === fsHudWrapper && name === "UP") hideFsHud();
    return true;
  }

  window.onRemoteKey = function (name, rep) {
    if (rep > 0 && !/^(UP|DOWN|LEFT|RIGHT|FF|REW|CH_UP|CH_DOWN)$/.test(name)) return;
    try { press(name, rep > 0); } catch (e) { console.error(e); }
  };

  /* ---------- tarayıcı/BT klavye aynı yola bağlanır ---------- */
  var MAP = { ArrowUp: "UP", ArrowDown: "DOWN", ArrowLeft: "LEFT", ArrowRight: "RIGHT", Enter: "OK", NumpadEnter: "OK", Select: "OK",
    Escape: "BACK", Backspace: "BACK", BrowserBack: "BACK", GoBack: "BACK", ChannelUp: "CH_UP", ChannelDown: "CH_DOWN",
    MediaPlayPause: "PLAY_PAUSE", MediaPlay: "PLAY", MediaPause: "PAUSE", MediaFastForward: "FF", MediaRewind: "REW",
    ContextMenu: "MENU", c: "MENU", C: "MENU", f: "FULLSCREEN", F: "FULLSCREEN", m: "MUTE", M: "MUTE", " ": "PLAY_PAUSE" };
  window.addEventListener("keydown", function (e) {
    var inText = isText(e.target), name = MAP[e.key];
    if (!inText && /^[0-9]$/.test(e.key)) name = "NUM_" + e.key;
    if (!name) return;
    if (inText && name !== "UP" && name !== "DOWN" && e.key !== "Escape") return;
    e.preventDefault(); e.stopImmediatePropagation();   // eski keydown dinleyicisini devre dışı bırakır
    if (e.repeat && !/^(UP|DOWN|LEFT|RIGHT)$/.test(name)) return;
    press(name, e.repeat);
  }, true);

  /* ---------- metin girişi bildirimi + modal odak yönetimi ---------- */
  document.addEventListener("focusin", function (e) { if (N && N.setTextInput) N.setTextInput(isText(e.target)); });
  document.addEventListener("focusout", function () { if (N && N.setTextInput) N.setTextInput(false); });

  [playlistModal, vpnModal].forEach(function (m) {
    var was = false;
    new MutationObserver(function () {
      var now = isOpen(m);
      if (now && !was) { prevFocus[m.id] = document.activeElement; setTimeout(function () { focusEl(items(m)[0]); }, 60); }
      if (!now && was) { var p = prevFocus[m.id]; if (p && document.contains(p)) focusEl(p); else refocusSoon(); }
      was = now;
    }).observe(m, { attributes: true, attributeFilter: ["class"] });
  });

  /* ilk açılışta odak listede başlasın */
  window.addEventListener("load", function () { if (IS_TV) setTimeout(function () { focusEl(pick(document.body)); }, 400); });
})();
