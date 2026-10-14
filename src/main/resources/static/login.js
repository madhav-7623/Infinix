(() => {
  const $ = id => document.getElementById(id);
  const queryRole = new URLSearchParams(location.search).get('role');
  const oauthErrors = {
    provider: 'The provider sign-in did not complete. Check the provider settings and try again.',
    failed: 'ChargeSync could not finish this sign-in. Please try again.',
    'choose-role': 'Choose Driver or Station operator before starting provider sign-in.',
    role: 'This provider account is linked to a different account type. Choose the matching role.',
    unregistered: 'No account is linked to this provider yet. Choose Create account to continue.',
    email: 'The provider did not verify this email, so it could not be linked to an existing account.',
    'email-missing': 'The provider did not share an email address. Allow email access and try again.',
    'station-details': 'Enter your station name and area before creating a station account.'
  };
  let role = queryRole === 'station' ? 'STATION' : 'DRIVER';
  let registering = false;
  const roles = [...document.querySelectorAll('[data-role-choice]')];
  const modes = [...document.querySelectorAll('[data-mode-choice]')];
  const isFilePreview = location.protocol === 'file:';

  function clearError() {
    $('authError').textContent = '';
    $('authError').classList.add('hidden');
  }
  function paint() {
    roles.forEach(button => {
      const active = button.dataset.roleChoice === role;
      button.classList.toggle('active', active);
      button.setAttribute('aria-pressed', String(active));
    });
    modes.forEach(button => {
      const active = (button.dataset.modeChoice === 'register') === registering;
      button.classList.toggle('active', active);
      button.setAttribute('aria-pressed', String(active));
    });
    $('stationFields').classList.toggle('hidden', !registering || role !== 'STATION');
    $('authTitle').textContent = registering ? (role === 'STATION' ? 'Create a station account' : 'Create your account') : 'Welcome back';
    $('authSubtitle').textContent = role === 'STATION' ? 'Manage your station and its published details.' : 'Choose your account type to continue.';
    $('submitAuth').textContent = registering ? 'Create account' : 'Sign in';
    $('password').autocomplete = registering ? 'new-password' : 'current-password';
    $('demoLogin').classList.toggle('hidden', registering);
  }
  function showError(message) {
    $('authError').textContent = message;
    $('authError').classList.remove('hidden');
  }

  roles.forEach(button => button.addEventListener('click', () => { role = button.dataset.roleChoice; clearError(); paint(); }));
  modes.forEach(button => button.addEventListener('click', () => { registering = button.dataset.modeChoice === 'register'; clearError(); paint(); }));

  $('authForm').addEventListener('submit', async event => {
    event.preventDefault();
    clearError();
    if (isFilePreview) { showError('This page is a visual preview. Run ChargeSync with run-windows.bat to sign in.'); return; }
    const payload = {email:$('email').value.trim(),password:$('password').value,role};
    if (registering && role === 'STATION') {
      payload.stationName = $('stationName').value.trim();
      payload.stationArea = $('stationArea').value.trim();
      payload.maxOutputKw = Number($('stationKw').value);
      if (!payload.stationName || !payload.stationArea) { showError('Enter your station name and area.'); return; }
    }
    $('submitAuth').disabled = true;
    $('submitAuth').textContent = registering ? 'Creating account…' : 'Signing in…';
    try {
      const response = await fetch('./api/auth/' + (registering ? 'register' : 'login'), {method:'POST',headers:{'Content-Type':'application/json'},credentials:'same-origin',body:JSON.stringify(payload)});
      const body = await response.json();
      if (!response.ok) throw new Error(body.detail || body.message || 'Could not sign in.');
      location.href = body.role === 'STATION' ? './station.html' : './';
    } catch(error) {
      showError(error.message);
    } finally {
      $('submitAuth').disabled = false;
      paint();
    }
  });

  async function beginOAuth(provider, button) {
    clearError();
    if (isFilePreview) { showError('Start ChargeSync with run-windows.bat before using provider sign-in.'); return; }
    const payload = {provider,role,registering};
    if (registering && role === 'STATION') {
      payload.stationName = $('stationName').value.trim();
      payload.stationArea = $('stationArea').value.trim();
      payload.maxOutputKw = Number($('stationKw').value);
      if (!payload.stationName || !payload.stationArea) { showError('Enter your station name and area.'); return; }
    }
    document.querySelectorAll('[data-provider]').forEach(item => item.disabled = true);
    button.textContent = 'Connecting…';
    try {
      const response = await fetch('./api/auth/oauth/start', {method:'POST',headers:{'Content-Type':'application/json'},credentials:'same-origin',body:JSON.stringify(payload)});
      const body = await response.json();
      if (!response.ok) throw new Error(body.detail || body.message || 'Provider sign-in is not available.');
      location.href = body.authorizationUrl;
    } catch(error) {
      showError(error.message);
      document.querySelectorAll('[data-provider]').forEach(item => item.disabled = false);
      document.querySelectorAll('[data-provider]').forEach(item => item.textContent = item.dataset.provider[0].toUpperCase() + item.dataset.provider.slice(1));
    }
  }
  document.querySelectorAll('[data-provider]').forEach(button => button.addEventListener('click', () => beginOAuth(button.dataset.provider, button)));

  const oauthError = new URLSearchParams(location.search).get('oauthError');
  if (oauthError) showError(oauthErrors[oauthError] || oauthErrors.failed);
  if (isFilePreview) $('localPreviewNote').classList.remove('hidden');
  fetch('./api/auth/providers', {credentials:'same-origin'}).then(response => response.ok ? response.json() : {providers:[]}).then(data => {
    const providers = new Set(data.providers || []);
    document.querySelectorAll('[data-provider]').forEach(button => button.classList.toggle('hidden', !providers.has(button.dataset.provider)));
    $('socialSection').classList.toggle('hidden', providers.size === 0);
  }).catch(() => {});
  paint();
})();
