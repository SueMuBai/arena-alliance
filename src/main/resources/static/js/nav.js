/* 共享顶栏：nav.js 渲染到 #topbar；active 为当前页标识 */
function renderTopbar(active, me, extraHtml) {
  const el = $id('topbar');
  if (!el) return;
  const adminLink = me && me.role === 'ADMIN'
    ? `<a href="/admin.html" class="${active === 'admin' ? 'active' : ''}">管理后台</a>` : '';
  el.innerHTML = `
    <div class="logo">⚔ <span id="nav-alliance">Arena 联盟</span></div>
    ${extraHtml || ''}
    <div class="nav">
      <a href="/" class="${active === 'map' ? 'active' : ''}">联盟地图</a>
      <a href="/keys.html" class="${active === 'keys' ? 'active' : ''}">我的 Key</a>
      <a href="/rules.html" class="${active === 'rules' ? 'active' : ''}">联盟规则</a>
      ${adminLink}
    </div>
    <div class="spacer"></div>
    <div class="user">
      <b>${esc(me ? me.username : '')}</b>
      ${me && me.status === 'KICKED' ? '<span class="status-pill err">已被移出联盟</span>' : ''}
      &nbsp;<a href="javascript:void(0)" id="nav-logout">退出</a>
    </div>`;
  const logout = $id('nav-logout');
  if (logout) {
    logout.onclick = async () => {
      try { await api('/api/auth/logout', { method: 'POST' }); } catch (e) { }
      location.href = '/login.html';
    };
  }
}

function setAllianceName(name) {
  const el = $id('nav-alliance');
  if (el && name) el.textContent = name;
  if (name) document.title = name;
}
