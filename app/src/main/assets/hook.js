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

  // L'application occupe déjà tout l'écran : pas de bouton « plein écran » dans les pages
  try { Element.prototype.requestFullscreen = undefined; Element.prototype.webkitRequestFullscreen = undefined; } catch (e) {}
})();
