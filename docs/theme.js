// Light or dark site theme. Follows the device until the visitor picks one
// with the Dark/Light switch under the home screenshot.
(function () {
  var root = document.documentElement;
  var media = window.matchMedia ? matchMedia('(prefers-color-scheme: light)') : null;
  var saved = null;
  try { saved = localStorage.getItem('theme'); } catch (e) {}
  if (saved === 'light' || saved === 'dark') root.dataset.theme = saved;

  function current() {
    return root.dataset.theme || (media && media.matches ? 'light' : 'dark');
  }
  function announce() {
    document.dispatchEvent(new CustomEvent('themechange', { detail: current() }));
  }
  window.plumeTheme = current;
  window.plumeSetTheme = function (theme) {
    root.dataset.theme = theme;
    try { localStorage.setItem('theme', theme); } catch (e) {}
    announce();
  };

  if (media && media.addEventListener) media.addEventListener('change', function () { if (!root.dataset.theme) announce(); });
  document.addEventListener('DOMContentLoaded', announce);
})();
