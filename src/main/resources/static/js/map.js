/* 联盟地图：Canvas 渲染 + SSE 实时更新（风格参照游戏战场视图） */
(function () {
  'use strict';

  const PALETTE = ['#4da3ff', '#37d67a', '#ffb020', '#b78bff', '#39c5cf', '#ff8ab3',
    '#9acd32', '#ff9d5c', '#5cd6b3', '#d8b4fe', '#7fb0ff', '#ffd166'];
  const TYPE_LABEL = {
    INTERNAL_ATTACK_BLOCKED: '🛡 已拦截内部攻击',
    INTERNAL_ATTACK_DETECTED: '👁 检测到内部攻击',
    INTERNAL_ATTACK_MANUAL: '⚠ 手操内部攻击(无法拦截)',
    OVERRIDE_LIMIT: '⛔ 覆盖次数达上限',
    INTERNAL_CASUALTY: '💥 内部攻击伤亡',
    WARNING: '⚠ 警告',
    KICK: '🚫 踢出联盟',
    RESTORE: '↩ 恢复成员',
    EXTERNAL_ATTACK: '🔥 外部攻击',
    KEY_INVALID: '🔑 key 失效',
    KEY_DUPLICATE: '🔑 key 重复',
    INFO: 'ℹ 信息'
  };
  const TYPE_CLASS = {
    INTERNAL_ATTACK_BLOCKED: 'blocked', INTERNAL_ATTACK_DETECTED: 'blocked',
    INTERNAL_ATTACK_MANUAL: 'casualty', OVERRIDE_LIMIT: 'external',
    INTERNAL_CASUALTY: 'casualty', WARNING: 'blocked', KICK: 'kick',
    RESTORE: 'ok', EXTERNAL_ATTACK: 'external', KEY_INVALID: '', KEY_DUPLICATE: '', INFO: ''
  };

  const canvas = $id('map');
  const ctx = canvas.getContext('2d');
  const state = {
    me: null,
    snapshot: null,
    incidents: [],
    alerts: [],                       // {x, y, until, color}
    cam: { cx: 0, cy: 0, scale: 22 },
    sseOk: false,
    activeTab: 'members'
  };
  const colorCache = {};

  /* 每个成员（游戏账号/keyId）一种颜色；按 keyId 取模保证跨刷新稳定 */
  function memberColor(keyId) {
    if (!(keyId in colorCache)) {
      colorCache[keyId] = PALETTE[Math.abs(Number(keyId) - 1) % PALETTE.length];
    }
    return colorCache[keyId];
  }

  /* 颜色区分成员，深浅区分角色：Worker 浅、Vanguard 标准、Ranger 深 */
  function shade(hex, f) {
    const n = parseInt(hex.slice(1), 16);
    let r = (n >> 16) & 255, g = (n >> 8) & 255, b = n & 255;
    if (f >= 0) { r += (255 - r) * f; g += (255 - g) * f; b += (255 - b) * f; }
    else { r *= 1 + f; g *= 1 + f; b *= 1 + f; }
    return 'rgb(' + (r | 0) + ',' + (g | 0) + ',' + (b | 0) + ')';
  }

  function roleColor(base, type) {
    if (type === 'WORKER') return shade(base, 0.45);
    if (type === 'RANGER') return shade(base, -0.32);
    return base;
  }

  function troopSummary(units) {
    let w = 0, v = 0, r = 0;
    for (const u of units || []) {
      if (u.type === 'WORKER') w++;
      else if (u.type === 'VANGUARD') v++;
      else if (u.type === 'RANGER') r++;
    }
    return w + 'W ' + v + 'V ' + r + 'R';
  }

  // ---------- 渲染 ----------
  function resize() {
    const dpr = window.devicePixelRatio || 1;
    const rect = canvas.parentElement.getBoundingClientRect();
    canvas.width = rect.width * dpr;
    canvas.height = rect.height * dpr;
    canvas.style.width = rect.width + 'px';
    canvas.style.height = rect.height + 'px';
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  }

  function w2sX(wx) { return (wx - state.cam.cx) * state.cam.scale + canvas.clientWidth / 2; }
  function w2sY(wy) { return (wy - state.cam.cy) * state.cam.scale + canvas.clientHeight / 2; }
  function s2wX(sx) { return (sx - canvas.clientWidth / 2) / state.cam.scale + state.cam.cx; }
  function s2wY(sy) { return (sy - canvas.clientHeight / 2) / state.cam.scale + state.cam.cy; }

  function render(now) {
    const w = canvas.clientWidth, h = canvas.clientHeight, s = state.cam.scale;
    ctx.clearRect(0, 0, w, h);
    ctx.fillStyle = '#0b0e14';
    ctx.fillRect(0, 0, w, h);

    const x0 = Math.floor(s2wX(0)) - 1, x1 = Math.ceil(s2wX(w)) + 1;
    const y0 = Math.floor(s2wY(0)) - 1, y1 = Math.ceil(s2wY(h)) + 1;

    // 网格
    const step = s >= 9 ? 1 : (s >= 3 ? 8 : 32);
    ctx.strokeStyle = s >= 9 ? 'rgba(255,255,255,0.045)' : 'rgba(255,255,255,0.06)';
    ctx.lineWidth = 1;
    ctx.beginPath();
    for (let gx = Math.floor(x0 / step) * step; gx <= x1; gx += step) {
      ctx.moveTo(w2sX(gx), 0); ctx.lineTo(w2sX(gx), h);
    }
    for (let gy = Math.floor(y0 / step) * step; gy <= y1; gy += step) {
      ctx.moveTo(0, w2sY(gy)); ctx.lineTo(w, w2sY(gy));
    }
    ctx.stroke();

    // 坐标轴原点
    ctx.strokeStyle = 'rgba(77,163,255,0.25)';
    ctx.beginPath();
    ctx.moveTo(w2sX(0), 0); ctx.lineTo(w2sX(0), h);
    ctx.moveTo(0, w2sY(0)); ctx.lineTo(w, w2sY(0));
    ctx.stroke();

    const snap = state.snapshot;
    if (!snap) { requestAnimationFrame(render); return; }
    const inView = (x, y) => x >= x0 && x <= x1 && y >= y0 && y <= y1;

    // 障碍（永久地形记忆）
    ctx.fillStyle = '#242c3b';
    for (const o of snap.obstacles || []) {
      if (inView(o[0], o[1])) ctx.fillRect(w2sX(o[0]), w2sY(o[1]), s, s);
    }

    // 资源点
    ctx.fillStyle = '#2f9e5f';
    for (const r of snap.resources || []) {
      if (inView(r[0], r[1])) {
        const cx = w2sX(r[0]) + s / 2, cy = w2sY(r[1]) + s / 2;
        ctx.beginPath();
        ctx.arc(cx, cy, Math.max(2, s * 0.16), 0, Math.PI * 2);
        ctx.fill();
      }
    }

    // 冠军信标
    if (snap.beacon && snap.beacon.pos) {
      const bx = w2sX(snap.beacon.pos[0]) + s / 2, by = w2sY(snap.beacon.pos[1]) + s / 2;
      drawStar(bx, by, Math.max(5, s * 0.42), '#ffd166');
      if (s >= 10) {
        ctx.fillStyle = '#ffd166';
        ctx.font = '11px sans-serif';
        ctx.textAlign = 'center';
        ctx.fillText(snap.beacon.status === 'CARRIED' ? '信标·被携带' : '信标', bx, by - s * 0.7);
      }
    }

    // 敌方目击（红色发光，按目击时效淡出；深浅同样区分角色）
    for (const e of snap.enemies || []) {
      if (!e.pos || !inView(e.pos[0], e.pos[1])) continue;
      const age = Math.max(0, (snap.tick || 0) - (e.tick || 0));
      const alpha = Math.max(0.25, 1 - age * 0.12);
      drawEntity(e.pos[0], e.pos[1], e.kind, e.type, roleColor('#ff5062', e.type), alpha, true);
      if (s >= 14 && e.kind === 'CORE' && e.owner) {
        ctx.fillStyle = 'rgba(255,80,98,' + alpha + ')';
        ctx.font = '11px sans-serif';
        ctx.textAlign = 'center';
        ctx.fillText('@' + e.owner, w2sX(e.pos[0]) + s / 2, w2sY(e.pos[1]) - 4);
      }
    }

    // 联盟成员
    (snap.members || []).forEach((m) => {
      const color = memberColor(m.keyId);
      for (const u of m.units || []) {
        if (u.pos && inView(u.pos[0], u.pos[1])) {
          drawEntity(u.pos[0], u.pos[1], 'UNIT', u.type, roleColor(color, u.type), m.online ? 1 : 0.45, false, u.cargo);
        }
      }
      if (m.core && m.core.pos && inView(m.core.pos[0], m.core.pos[1])) {
        drawCore(m, color);
      }
    });

    // 告警脉冲
    state.alerts = state.alerts.filter(a => a.until > now);
    for (const a of state.alerts) {
      const t = 1 - (a.until - now) / a.dur;
      const pulse = (t * 3) % 1;
      const cx = w2sX(a.x) + s / 2, cy = w2sY(a.y) + s / 2;
      ctx.strokeStyle = a.color;
      ctx.globalAlpha = 1 - pulse;
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.arc(cx, cy, s * (0.5 + pulse * 1.6), 0, Math.PI * 2);
      ctx.stroke();
      ctx.globalAlpha = 1;
    }

    requestAnimationFrame(render);
  }

  function drawEntity(wx, wy, kind, type, color, alpha, glow, cargo) {
    const s = state.cam.scale;
    const cx = w2sX(wx) + s / 2, cy = w2sY(wy) + s / 2;
    ctx.globalAlpha = alpha;
    ctx.fillStyle = color;
    ctx.strokeStyle = 'rgba(0,0,0,0.55)';
    ctx.lineWidth = Math.max(1, s * 0.05);
    if (glow) {
      ctx.shadowColor = '#ff5062';
      ctx.shadowBlur = Math.max(4, s * 0.3);
    }
    if (kind === 'CORE') {
      const r = Math.max(3, s * 0.38);
      roundRect(cx - r, cy - r, r * 2, r * 2, Math.max(1.5, s * 0.1));
      ctx.fill();
      ctx.stroke();
      if (s >= 8) {
        ctx.shadowBlur = 0;
        ctx.fillStyle = 'rgba(0,0,0,0.28)';
        roundRect(cx - r * 0.55, cy - r * 0.55, r * 1.1, r * 1.1, r * 0.18);
        ctx.fill();
        ctx.fillStyle = 'rgba(255,255,255,0.9)';
        ctx.beginPath();
        ctx.arc(cx, cy, Math.max(1.2, r * 0.24), 0, Math.PI * 2);
        ctx.fill();
      }
    } else if (type === 'VANGUARD') {
      // 盾形
      const r = Math.max(3, s * 0.34);
      ctx.beginPath();
      ctx.moveTo(cx - r, cy - r * 0.55);
      ctx.quadraticCurveTo(cx, cy - r * 1.05, cx + r, cy - r * 0.55);
      ctx.lineTo(cx + r * 0.82, cy + r * 0.25);
      ctx.quadraticCurveTo(cx + r * 0.45, cy + r * 0.8, cx, cy + r);
      ctx.quadraticCurveTo(cx - r * 0.45, cy + r * 0.8, cx - r * 0.82, cy + r * 0.25);
      ctx.closePath();
      ctx.fill();
      ctx.stroke();
      if (s >= 12) {
        ctx.shadowBlur = 0;
        ctx.strokeStyle = 'rgba(255,255,255,0.55)';
        ctx.lineWidth = Math.max(1, s * 0.05);
        ctx.beginPath();
        ctx.moveTo(cx, cy - r * 0.5);
        ctx.lineTo(cx, cy + r * 0.55);
        ctx.stroke();
      }
    } else if (type === 'RANGER') {
      // 菱形准星
      const r = Math.max(3, s * 0.34);
      ctx.beginPath();
      ctx.moveTo(cx, cy - r);
      ctx.lineTo(cx + r, cy);
      ctx.lineTo(cx, cy + r);
      ctx.lineTo(cx - r, cy);
      ctx.closePath();
      ctx.fill();
      ctx.stroke();
      if (s >= 10) {
        ctx.shadowBlur = 0;
        ctx.fillStyle = 'rgba(255,255,255,0.92)';
        ctx.beginPath();
        ctx.arc(cx, cy, Math.max(1, r * 0.22), 0, Math.PI * 2);
        ctx.fill();
      }
    } else {
      // Worker 圆形，携货时右上角金色小点
      const r = Math.max(2, s * 0.24);
      ctx.beginPath();
      ctx.arc(cx, cy, r, 0, Math.PI * 2);
      ctx.fill();
      ctx.stroke();
      if (cargo > 0 && s >= 10) {
        ctx.shadowBlur = 0;
        ctx.fillStyle = '#ffd166';
        ctx.beginPath();
        ctx.arc(cx + r * 0.75, cy - r * 0.75, Math.max(1.2, r * 0.45), 0, Math.PI * 2);
        ctx.fill();
        ctx.strokeStyle = 'rgba(0,0,0,0.4)';
        ctx.stroke();
      }
    }
    ctx.shadowBlur = 0;
    ctx.globalAlpha = 1;
  }

  function drawCore(m, color) {
    const s = state.cam.scale;
    const pos = m.core.pos;
    const cx = w2sX(pos[0]) + s / 2, cy = w2sY(pos[1]) + s / 2;
    const r = Math.max(4, s * 0.42);
    ctx.globalAlpha = m.online ? 1 : 0.45;
    // 本体
    ctx.fillStyle = color;
    roundRect(cx - r, cy - r, r * 2, r * 2, Math.max(2, s * 0.12));
    ctx.fill();
    ctx.strokeStyle = 'rgba(255,255,255,0.55)';
    ctx.lineWidth = Math.max(1, s * 0.06);
    ctx.stroke();
    // 内芯
    if (s >= 8) {
      ctx.fillStyle = 'rgba(0,0,0,0.28)';
      roundRect(cx - r * 0.55, cy - r * 0.55, r * 1.1, r * 1.1, r * 0.18);
      ctx.fill();
      ctx.fillStyle = 'rgba(255,255,255,0.92)';
      ctx.beginPath();
      ctx.arc(cx, cy, Math.max(1.4, r * 0.24), 0, Math.PI * 2);
      ctx.fill();
    }
    // 护盾环
    const shield = m.core.shield || 0;
    if (shield > 0) {
      ctx.strokeStyle = 'rgba(120,200,255,0.9)';
      ctx.lineWidth = Math.max(1.5, s * 0.08);
      ctx.beginPath();
      ctx.arc(cx, cy, r + Math.max(2, s * 0.16), -Math.PI / 2, -Math.PI / 2 + Math.PI * 2 * Math.min(1, shield / 5));
      ctx.stroke();
    }
    // 血条（掉血才显示）
    if (s >= 10 && typeof m.core.hp === 'number' && m.core.hp < 5) {
      const frac = Math.max(0, Math.min(1, m.core.hp / 5));
      const bw = r * 2, bh = Math.max(2, s * 0.1);
      ctx.fillStyle = 'rgba(0,0,0,0.55)';
      ctx.fillRect(cx - r, cy + r + Math.max(3, s * 0.2), bw, bh);
      ctx.fillStyle = frac > 0.5 ? '#37d67a' : (frac > 0.25 ? '#ffb020' : '#ff5062');
      ctx.fillRect(cx - r, cy + r + Math.max(3, s * 0.2), bw * frac, bh);
    }
    // 迁移箭头
    if (m.core.state === 'MOVING') {
      ctx.fillStyle = '#fff';
      ctx.font = Math.max(9, s * 0.5) + 'px sans-serif';
      ctx.textAlign = 'center';
      ctx.fillText('⇢', cx, cy - r - 4);
    }
    // 名字
    if (state.cam.scale >= 12) {
      ctx.font = 'bold 12px sans-serif';
      ctx.textAlign = 'center';
      ctx.fillStyle = color;
      ctx.fillText('@' + (m.gameUsername || ('key-' + m.keyId)), cx, cy - r - (m.core.state === 'MOVING' ? 18 : 6));
    }
    ctx.globalAlpha = 1;
  }

  function drawStar(cx, cy, r, color) {
    ctx.fillStyle = color;
    ctx.beginPath();
    for (let i = 0; i < 10; i++) {
      const ang = -Math.PI / 2 + i * Math.PI / 5;
      const rr = i % 2 === 0 ? r : r * 0.45;
      ctx.lineTo(cx + Math.cos(ang) * rr, cy + Math.sin(ang) * rr);
    }
    ctx.closePath();
    ctx.fill();
  }

  function roundRect(x, y, w, h, r) {
    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.arcTo(x + w, y, x + w, y + h, r);
    ctx.arcTo(x + w, y + h, x, y + h, r);
    ctx.arcTo(x, y + h, x, y, r);
    ctx.arcTo(x, y, x + w, y, r);
    ctx.closePath();
  }

  // ---------- 交互 ----------
  let dragging = false, lastX = 0, lastY = 0, moved = 0;
  canvas.addEventListener('mousedown', e => {
    dragging = true; moved = 0;
    lastX = e.clientX; lastY = e.clientY;
    canvas.classList.add('dragging');
  });
  window.addEventListener('mousemove', e => {
    const rect = canvas.getBoundingClientRect();
    const wx = Math.floor(s2wX(e.clientX - rect.left));
    const wy = Math.floor(s2wY(e.clientY - rect.top));
    $id('hud-pos').textContent = '[' + wx + ', ' + wy + ']';
    if (!dragging) return;
    const dx = e.clientX - lastX, dy = e.clientY - lastY;
    moved += Math.abs(dx) + Math.abs(dy);
    state.cam.cx -= dx / state.cam.scale;
    state.cam.cy -= dy / state.cam.scale;
    lastX = e.clientX; lastY = e.clientY;
  });
  window.addEventListener('mouseup', () => {
    dragging = false;
    canvas.classList.remove('dragging');
  });
  canvas.addEventListener('wheel', e => {
    e.preventDefault();
    const rect = canvas.getBoundingClientRect();
    const mx = e.clientX - rect.left, my = e.clientY - rect.top;
    const wx = s2wX(mx), wy = s2wY(my);
    const factor = e.deltaY < 0 ? 1.18 : 1 / 1.18;
    state.cam.scale = Math.min(64, Math.max(1.2, state.cam.scale * factor));
    state.cam.cx = wx - (mx - canvas.clientWidth / 2) / state.cam.scale;
    state.cam.cy = wy - (my - canvas.clientHeight / 2) / state.cam.scale;
  }, { passive: false });

  function centerOn(x, y, scale) {
    state.cam.cx = x + 0.5;
    state.cam.cy = y + 0.5;
    if (scale) state.cam.scale = scale;
  }
  $id('btn-center').onclick = () => centerOn(0, 0);
  $id('btn-beacon').onclick = () => {
    const b = state.snapshot && state.snapshot.beacon;
    if (b && b.pos) centerOn(b.pos[0], b.pos[1], Math.max(state.cam.scale, 18));
  };
  $id('legend-toggle').onclick = () => {
    const lg = $id('legend');
    lg.classList.toggle('collapsed');
    $id('legend-arrow').textContent = lg.classList.contains('collapsed') ? '▸' : '▾';
  };

  // ---------- 侧栏 ----------
  const TABS = ['members', 'feed'];
  TABS.forEach(t => $id('tab-' + t).onclick = () => switchTab(t));
  function switchTab(tab) {
    state.activeTab = tab;
    for (const t of TABS) {
      $id('tab-' + t).classList.toggle('active', t === tab);
      $id('panel-' + t).style.display = t === tab ? '' : 'none';
    }
  }

  // 公约内容已迁移至独立页面 rules.html（联盟规则）；此构建函数暂留备用
  function covenantHtml(r) {
    const punish = b => b ? '<span class="kick-tag">立即踢出</span>'
      : (r.warnOnShieldDamage ? '<span class="warn-tag">记警告</span>' : '仅记录');
    return `
      <h4>一、成员身份判定</h4>
      <p>登录本平台（LinuxDo 或账号密码）并在「我的 Key」上传游戏 apikey 后，平台接入游戏识别该 key 的游戏用户名——<span class="hl">该游戏账号即成为受保护的联盟成员</span>。
      地图上带成员色标识的都是联盟单位，<span class="hl" style="color:var(--danger)">红色发光的均为非联盟单位（敌对）</span>。
      被踢出者全部 key 停用、移出地图、不再受任何保护。</p>

      <h4>二、互不侵犯</h4>
      <p>禁止对任何联盟成员的单位或 Core 发起攻击（SWEEP 扫击指向成员所在格、SHOOT 射击成员所在格/锁定成员对象，均判定为攻击行为）。同一玩家的多个游戏账号之间不受此限。</p>

      <h4>三、攻击抑制机制${r.interventionEnabled ? '' : '（当前已停用）'}</h4>
      <div class="cov-box">
      <p>平台实时监审每位成员提交的作战计划：</p>
      <ul>
        <li>发现攻击成员的动作 → <span class="hl">立即用攻击者本人的 key 提交净化计划覆盖</span>（仅攻击动作改为 WAIT，采集/移动等原样保留）；</li>
        <li>攻击方 agent 反复重提 → 平台持续覆盖，同一 Tick 内最多 <span class="hl">${r.maxOverridesPerTick} 次</span>；</li>
        <li>网页手操（MANUAL）优先级高于 AGENT，<span class="hl">无法覆盖</span>——将记录告警并进入伤亡裁决。</li>
      </ul>
      </div>

      <h4>四、伤亡裁决与处罚</h4>
      <p>每个 Tick 结算后，平台通过攻守双方战斗事件交叉归因（Core 摧毁者名单 / 命中目标 / 扫击格位）。对内攻击造成以下后果时：</p>
      <ul>
        <li>致成员单位死亡 → ${punish(r.kickOnUnitDeath)}</li>
        <li>致成员 Core 掉血 → ${punish(r.kickOnCoreDamage)}</li>
        <li>致成员 Core 被摧毁 → ${punish(r.kickOnCoreDestroyed)}</li>
        <li>仅打掉护盾 / 单位受伤未死 → ${r.warnOnShieldDamage
        ? '<span class="warn-tag">记警告</span>，累计 <span class="hl">' + r.kickAfterWarnings + '</span> 次自动踢出' : '仅记录'}</li>
      </ul>
      <p>全部拦截、警告、踢出记录在「事件流」对全员公开，可作申诉依据；管理员可复核并恢复成员资格。</p>

      <h4>五、外部威胁</h4>
      <p>${r.externalAlert
        ? '非联盟玩家攻击成员时，地图打红色脉冲标记并在事件流通报。'
        : '外部攻击告警当前已关闭。'}平台没有外部玩家的 key，<span class="hl">无法代为拦截外部攻击</span>，请各自维持防线并按告警互相支援。</p>

      <h4>六、其他约定</h4>
      <ul>
        <li>key 失效（改密/撤销）会失去保护并触发告警，请及时更换；</li>
        <li>同一游戏账号上传多个 key 时只保留一条连接；</li>
        <li>新成员注册${r.registrationOpen ? '开放中' : '已关闭'}${r.minTrustLevel > 0 ? '（LinuxDo 信任等级 ≥ ' + r.minTrustLevel + '）' : ''}。</li>
      </ul>`;
  }

  function renderMembers() {
    const snap = state.snapshot;
    const panel = $id('panel-members');
    if (!snap || !snap.members || snap.members.length === 0) {
      panel.innerHTML = '<div style="color:var(--dim);padding:30px 10px;text-align:center;line-height:1.8">' +
        '暂无成员数据<br>到「我的 Key」上传游戏 apikey 加入联盟</div>';
      return;
    }
    const members = [...snap.members].sort((a, b) => (a.gameUsername || '').localeCompare(b.gameUsername || ''));
    panel.innerHTML = members.map((m) => {
      const color = memberColor(m.keyId);
      const core = m.core;
      const hp = core ? core.hp : 0;
      const warn = m.warnings > 0 ? `<span class="tag warn">警告×${m.warnings}</span>` : '';
      const kicked = m.userStatus === 'KICKED' ? '<span class="tag danger">已踢出</span>' : '';
      return `
      <div class="member ${m.online ? '' : 'offline'}" data-key="${m.keyId}">
        <div class="row1">
          <span class="cdot" style="background:${color}"></span>
          <span class="gname">@${esc(m.gameUsername || ('key-' + m.keyId))}</span>
          <span class="dot ${m.online ? 'on' : 'off'}" title="${m.online ? '在线' : '离线'}"></span>
          <span style="flex:1"></span>
          ${warn}${kicked}
          <span class="lname">${esc(m.memberName || '')}</span>
        </div>
        <div class="row2">
          <span>人口 <b>${m.population ?? '-'}</b></span>
          <span>兵力 <b>${troopSummary(m.units)}</b></span>
          <span>资源 <b>${m.resources ?? '-'}</b></span>
        </div>
        <div class="row2">
          <span>Core <b>${core ? hp + 'HP/' + (core.shield || 0) + '盾' : (m.status === 'RESPAWNING' ? '重生中' : '-')}</b></span>
          <span style="flex:1"></span>
          <span>${core && core.pos ? '[' + core.pos[0] + ',' + core.pos[1] + ']' : ''}</span>
        </div>
        <div class="hpbar"><i style="width:${core ? Math.min(100, hp * 20) : 0}%"></i></div>
      </div>`;
    }).join('');
    panel.querySelectorAll('.member').forEach(el => {
      el.onclick = () => {
        const m = snap.members.find(x => String(x.keyId) === el.dataset.key);
        if (m && m.core && m.core.pos) centerOn(m.core.pos[0], m.core.pos[1], Math.max(state.cam.scale, 16));
      };
    });
  }

  function renderFeed() {
    const panel = $id('panel-feed');
    if (state.incidents.length === 0) {
      panel.innerHTML = '<div style="color:var(--dim);padding:30px 10px;text-align:center">暂无事件</div>';
      return;
    }
    panel.innerHTML = state.incidents.slice(0, 120).map(i => {
      const label = TYPE_LABEL[i.type] || i.type;
      const cls = TYPE_CLASS[i.type] || '';
      const who = [];
      if (i.attackerName) who.push('攻击者 @' + esc(i.attackerName));
      if (i.victimName) who.push('目标 @' + esc(i.victimName));
      return `<div class="incident ${cls}">
        <div><b>${label}</b> ${who.join(' → ')}</div>
        ${i.detail ? '<div>' + esc(i.detail) + '</div>' : ''}
        <div class="meta">Tick ${i.tick ?? '-'} · ${fmtTime(i.createdAt)}</div>
      </div>`;
    }).join('');
  }

  function pushIncident(incident) {
    state.incidents.unshift(incident);
    if (state.incidents.length > 300) state.incidents.length = 300;
    renderFeed();
    // 在受害者 Core 位置打告警脉冲
    const dangerTypes = ['INTERNAL_CASUALTY', 'EXTERNAL_ATTACK', 'INTERNAL_ATTACK_MANUAL', 'KICK'];
    const warnTypes = ['INTERNAL_ATTACK_BLOCKED', 'WARNING', 'INTERNAL_ATTACK_DETECTED'];
    let color = null;
    if (dangerTypes.includes(incident.type)) color = '#ff5062';
    else if (warnTypes.includes(incident.type)) color = '#ffb020';
    if (color && state.snapshot) {
      const victim = (state.snapshot.members || []).find(m => m.userId === incident.victimUserId);
      if (victim && victim.core && victim.core.pos) {
        state.alerts.push({
          x: victim.core.pos[0], y: victim.core.pos[1],
          until: performance.now() + 12000, dur: 12000, color
        });
      }
    }
  }

  // ---------- 数据 ----------
  function connectSse() {
    const es = new EventSource('/api/map/stream');
    es.addEventListener('snapshot', e => {
      try {
        state.snapshot = JSON.parse(e.data);
        setAllianceName(state.snapshot.allianceName);
        updateHud();
        renderMembers();
      } catch (err) { }
    });
    es.addEventListener('incident', e => {
      try { pushIncident(JSON.parse(e.data)); } catch (err) { }
    });
    es.onopen = () => { state.sseOk = true; updateHud(); };
    es.onerror = () => {
      state.sseOk = false;
      updateHud();
      es.close();
      setTimeout(connectSse, 3000);
    };
  }

  function updateHud() {
    const tickEl = $id('hud-tick');
    if (tickEl) tickEl.innerHTML = 'Tick <b>' + (state.snapshot ? state.snapshot.tick : '-') + '</b>';
    const sseEl = $id('hud-sse');
    if (sseEl) sseEl.innerHTML = '<span class="dot ' + (state.sseOk ? 'on' : 'off') + '"></span>' +
      (state.sseOk ? '实时同步' : '连接断开');
  }

  // ---------- 启动 ----------
  (async function init() {
    try {
      state.me = await ensureLogin();
    } catch (e) { return; }
    if (!state.me.hasGameKey) {
      location.replace('/keys.html?required=map');
      return;
    }
    renderTopbar('map', state.me,
      '<span class="badge" id="hud-tick">Tick <b>-</b></span><span class="badge" id="hud-sse"><span class="dot"></span>连接中</span>');
    if (state.me.status === 'KICKED') {
      document.querySelector('.map-wrap').innerHTML =
        '<div style="display:flex;height:100%;align-items:center;justify-content:center;color:var(--dim);font-size:15px">' +
        '你已被移出联盟，无法查看联盟地图。如有异议请联系管理员。</div>';
      return;
    }
    resize();
    window.addEventListener('resize', resize);
    try {
      state.snapshot = await api('/api/map/snapshot');
      setAllianceName(state.snapshot.allianceName);
      // 初始视角：第一个有 Core 的成员，否则信标
      const withCore = (state.snapshot.members || []).find(m => m.core && m.core.pos);
      if (withCore) centerOn(withCore.core.pos[0], withCore.core.pos[1]);
      else if (state.snapshot.beacon && state.snapshot.beacon.pos) {
        centerOn(state.snapshot.beacon.pos[0], state.snapshot.beacon.pos[1]);
      }
      renderMembers();
      updateHud();
    } catch (e) {
      toast(e.message, true);
    }
    try {
      state.incidents = await api('/api/map/incidents?limit=50');
      renderFeed();
    } catch (e) { }
    connectSse();
    requestAnimationFrame(render);
  })();
})();
