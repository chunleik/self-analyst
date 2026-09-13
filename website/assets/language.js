(() => {
  const key = 'selfanalyst.website.language';
  const supported = ['zh-CN', 'en'];
  const explicit = document.documentElement.dataset.language;
  if (supported.includes(explicit)) {
    try { localStorage.setItem(key, explicit); } catch { /* 浏览器可能禁止存储。 */ }
    return;
  }
  let saved;
  try { saved = localStorage.getItem(key); } catch { /* 使用浏览器语言回退。 */ }
  const preferred = navigator.languages?.[0] || navigator.language || 'en';
  const language = supported.includes(saved) ? saved : /^zh(?:-|$)/i.test(preferred) ? 'zh-CN' : 'en';
  location.replace(new URL(`./${language}/`, location.href).href);
})();
