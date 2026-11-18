(function () {
  if (window.__coqueHook || !window.AndroidBridge) return;
  window.__coqueHook = true;
  var CH = 3 * 1024 * 1024;

  // Enregistrement des fichiers « téléchargés » (blob: / data:) dans le dossier AppsHTML/Enregistrements
  function saveBlob(name, blob) {
    var token = AndroidBridge.beginSave(name || 'fichier');
    var pos = 0;
    function next() {
      if (pos >= blob.size) { AndroidBridge.endSave(token); return; }
      var fr = new FileReader();
      fr.onload = function () {
        var s = String(fr.result || ''), i = s.indexOf(',');
        AndroidBridge.appendSave(token, i >= 0 ? s.slice(i + 1) : '');
        pos += CH; next();
      };
      fr.onerror = function () { AndroidBridge.endSave(token); };
      fr.readAsDataURL(blob.slice(pos, pos + CH));
    }
    next();
  }
  var origClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    var h = this.href || '';
    if (this.hasAttribute('download') && (h.indexOf('blob:') === 0 || h.indexOf('data:') === 0)) {
      var nm = this.getAttribute('download') || 'fichier';
      fetch(h).then(function (r) { return r.blob(); }).then(function (b) { saveBlob(nm, b); });
      return;
    }
    return origClick.apply(this, arguments);
  };

  // Impression : boîte d'impression d'Android (permet aussi « Enregistrer au format PDF »)
  window.print = function () { AndroidBridge.print(); };


  // Visionneuse PDF : la WebView n'affiche pas les PDF. CoquePdf.open(source) -> Promise<doc>
  //   source = ArrayBuffer, Uint8Array, Blob ou adresse (URL). doc = { n, size(i) -> {w,h}, page(i, largeurPx) -> Promise<dataURL>, close() }
  function toBlob(src) {
    if (src instanceof Blob) return Promise.resolve(src);
    if (src instanceof ArrayBuffer || ArrayBuffer.isView(src)) return Promise.resolve(new Blob([src]));
    return fetch(String(src)).then(function (r) { return r.blob(); });
  }
  function readChunk(blob, pos) {
    return new Promise(function (ok, ko) {
      var fr = new FileReader();
      fr.onload = function () { var s = String(fr.result || ''), i = s.indexOf(','); ok(i >= 0 ? s.slice(i + 1) : ''); };
      fr.onerror = function () { ko(fr.error); };
      fr.readAsDataURL(blob.slice(pos, pos + CH));
    });
  }
  window.CoquePdf = {
    open: function (src) {
      return toBlob(src).then(function (blob) {
        var id = AndroidBridge.pdfBegin();
        if (!id) throw new Error('PDF : fichier temporaire impossible');
        var pos = 0;
        function next() {
          if (pos >= blob.size) return Promise.resolve();
          return readChunk(blob, pos).then(function (b64) { AndroidBridge.pdfAppend(id, b64); pos += CH; return next(); });
        }
        return next().then(function () {
          var n = AndroidBridge.pdfOpen(id);
          if (n < 0) { AndroidBridge.pdfClose(id); throw new Error('PDF illisible (fichier protégé ou endommagé)'); }
          var sizes = {};
          return {
            n: n,
            size: function (i) {
              if (!sizes[i]) { var t = String(AndroidBridge.pdfSize(id, i)).split(','); sizes[i] = { w: +t[0] || 595, h: +t[1] || 842 }; }
              return sizes[i];
            },
            page: function (i, w) {
              return new Promise(function (ok, ko) {
                setTimeout(function () {
                  var b = AndroidBridge.pdfPage(id, i, Math.round(w));
                  b ? ok('data:image/jpeg;base64,' + b) : ko(new Error('page ' + (i + 1)));
                }, 0);
              });
            },
            close: function () { AndroidBridge.pdfClose(id); }
          };
        });
      });
    }
  };

  // L'application occupe déjà tout l'écran : pas de bouton « plein écran » dans les pages
  try { Element.prototype.requestFullscreen = undefined; Element.prototype.webkitRequestFullscreen = undefined; } catch (e) {}
})();
