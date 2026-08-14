/* 统一 API 封装：ApiResponse{success,message,data}，401 跳登录页 */
async function api(path, opts = {}) {
  const options = Object.assign({ headers: { 'Content-Type': 'application/json' } }, opts);
  let res;
  try {
    res = await fetch(path, options);
  } catch (e) {
    throw new Error('网络错误');
  }
  if (res.status === 401) {
    location.href = '/login.html';
    throw new Error('未登录');
  }
  let body;
  try {
    body = await res.json();
  } catch (e) {
    throw new Error('响应格式异常');
  }
  if (!body.success) {
    throw new Error(body.message || ('请求失败(' + res.status + ')'));
  }
  return body.data;
}

async function ensureLogin() {
  const me = await api('/api/auth/me');
  if (!me.loggedIn) {
    location.href = '/login.html';
    throw new Error('未登录');
  }
  return me;
}

function $id(id) {
  return document.getElementById(id);
}

function esc(s) {
  if (s === null || s === undefined) return '';
  return String(s).replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  })[c]);
}

let _toastTimer = null;
function toast(message, isError) {
  let el = document.querySelector('.toast');
  if (el) el.remove();
  el = document.createElement('div');
  el.className = 'toast' + (isError ? ' err' : '');
  el.textContent = message;
  document.body.appendChild(el);
  clearTimeout(_toastTimer);
  _toastTimer = setTimeout(() => el.remove(), 3200);
}

function fmtTime(iso) {
  if (!iso) return '';
  try {
    const d = new Date(iso);
    const p = n => String(n).padStart(2, '0');
    return p(d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds());
  } catch (e) {
    return iso;
  }
}
