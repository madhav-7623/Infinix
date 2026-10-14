(() => {
  const expected = document.body.dataset.role;
  if (!expected || expected === 'public') return;
  fetch('/api/auth/session', {credentials:'same-origin'})
    .then(response => response.ok ? response.json() : {authenticated:false})
    .then(session => {
      if (!session.authenticated || (expected !== 'any' && session.role.toLowerCase() !== expected)) {
        location.replace(`/login.html?role=${encodeURIComponent(expected)}`);
        return;
      }
      const portalReturn = document.getElementById('portalReturn');
      if (portalReturn) portalReturn.href = session.role === 'STATION' ? '/station.html' : '/';
      const nav = document.querySelector('.nav') || document.querySelector('header');
      if (!nav) return;
      if (!document.getElementById('auth-ui-style')) {
        const style = document.createElement('style'); style.id = 'auth-ui-style';
        style.textContent = '.account-actions{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.account-chip{font-size:11px;color:#60736d;padding:8px 10px;background:#edf6f1;border-radius:999px}.account-logout{font-size:11px!important;padding:8px 11px!important}';
        document.head.append(style);
      }
      const account = document.createElement('div');
      account.className = 'account-actions';
      account.innerHTML = `<span class="account-chip">${session.role === 'STATION' ? 'Station' : 'Driver'} · ${session.email}</span><button type="button" class="btn ghost account-logout">Sign out</button>`;
      nav.append(account);
      account.querySelector('button').addEventListener('click', async () => {
        await fetch('/api/auth/logout', {method:'POST',credentials:'same-origin'});
        location.href = `/login.html?role=${expected}`;
      });
    })
    .catch(() => location.replace(`/login.html?role=${encodeURIComponent(expected)}`));
})();
