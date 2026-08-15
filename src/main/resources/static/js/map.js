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
  // Vue 响应式状态：Canvas 每帧直接读取，侧栏组件自动跟随变化
  const state = Vue.reactive({
    me: null,
    snapshot: null,
    incidents: [],
    alerts: [],                       // {x, y, until, color}
    cam: { cx: 0, cy: 0, scale: 22 },
    sseOk: false,
    selectedKeyId: null,              // 选中的成员（keyId），其余成员淡出
    tickClock: { tick: null, windowMs: 15000, remainingMs: 0, anchoredAt: 0 },
    manual: {                         // 人工接管
      enabled: false,
      accounts: [],                   // 可操控账号（托管运行中的自己的 key）
      keyId: null,                    // 当前操控账号
      panelObject: null,              // 操作面板对象详情
      targeting: null,                // {type, label, targets:[...]} 选格模式
      hoverTarget: null,
      effect: null,                   // 已被服务器接受的短暂指令预览
      stackCell: null,
      submitting: false,
      controlledIds: new Set()        // 本 Tick 已被人工接管的对象
    }
  });
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

    // 敌方目击（红色发光，按目击时效淡出；深浅同样区分角色；有成员选中时整体降权）
    const selId = state.selectedKeyId;
    for (const e of snap.enemies || []) {
      if (!e.pos || !inView(e.pos[0], e.pos[1])) continue;
      const age = Math.max(0, (snap.tick || 0) - (e.tick || 0));
      const alpha = Math.max(0.25, 1 - age * 0.12) * (selId != null ? 0.35 : 1);
      drawEntity(e.pos[0], e.pos[1], e.kind, e.type, roleColor('#ff5062', e.type), alpha, true);
      if (s >= 14 && e.kind === 'CORE' && e.owner && selId == null) {
        ctx.fillStyle = 'rgba(255,80,98,' + alpha + ')';
        ctx.font = '11px sans-serif';
        ctx.textAlign = 'center';
        ctx.fillText('@' + e.owner, w2sX(e.pos[0]) + s / 2, w2sY(e.pos[1]) - 4);
      }
    }

    // 联盟成员：选中者全亮 + 光环 + 选中环，其余淡出
    (snap.members || []).forEach((m) => {
      const color = memberColor(m.keyId);
      const isSel = selId != null && m.keyId === selId;
      const baseAlpha = selId == null ? (m.online ? 1 : 0.45)
        : (isSel ? 1 : 0.15);
      for (const u of m.units || []) {
        if (u.pos && inView(u.pos[0], u.pos[1])) {
          if (isSel) drawHalo(u.pos[0], u.pos[1], color);
          drawEntity(u.pos[0], u.pos[1], 'UNIT', u.type, roleColor(color, u.type), baseAlpha, false, u.cargo);
        }
      }
      if (m.core && m.core.pos && inView(m.core.pos[0], m.core.pos[1])) {
        drawCore(m, color, baseAlpha, isSel, now);
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

    // 人工接管：移动与攻击使用不同目标色和指令路径
    const targeting = state.manual.targeting;
    if (targeting) {
      const blink = 0.55 + 0.45 * Math.sin((now || 0) / 220);
      const attack = targeting.type === 'SHOOT' || targeting.type === 'SWEEP';
      for (const t of targeting.targets) {
        const [tx, ty] = t.pos;
        if (!inView(tx, ty)) continue;
        const x = w2sX(tx), y = w2sY(ty);
        const color = t.ally ? '#ff5c8a' : (t.blocked ? '#6b7280'
          : (attack ? (t.enemy ? '#ff6b6b' : '#ff8cab') : '#22d3ee'));
        ctx.globalAlpha = t.blocked || t.ally ? 0.3 : 0.24 * blink + 0.14;
        ctx.fillStyle = color;
        ctx.fillRect(x, y, s, s);
        ctx.globalAlpha = t.blocked || t.ally ? 0.65 : 0.95;
        ctx.strokeStyle = color;
        ctx.lineWidth = Math.max(1.5, s * 0.06);
        ctx.strokeRect(x + 1, y + 1, s - 2, s - 2);
        if (!t.blocked && !t.ally && s >= 10) {
          ctx.fillStyle = color;
          ctx.beginPath();
          ctx.arc(x + s / 2, y + s / 2, Math.max(1.5, s * 0.07), 0, Math.PI * 2);
          ctx.fill();
        }
        if (t.ally && s >= 12) {
          ctx.fillStyle = '#ff5c8a';
          ctx.font = 'bold ' + Math.max(10, s * 0.5) + 'px sans-serif';
          ctx.textAlign = 'center';
          ctx.textBaseline = 'middle';
          ctx.fillText('✕', x + s / 2, y + s / 2);
          ctx.textBaseline = 'alphabetic';
        }
        ctx.globalAlpha = 1;
      }
      if (state.manual.hoverTarget) {
        drawCommandPath(targeting.object.pos, state.manual.hoverTarget.pos,
          targeting.type, now, 1);
      }
    }

    if (state.manual.effect) {
      if (state.manual.effect.until <= now) state.manual.effect = null;
      else {
        const alpha = Math.max(0, (state.manual.effect.until - now) / state.manual.effect.duration);
        drawCommandPath(state.manual.effect.from, state.manual.effect.to,
          state.manual.effect.type, now, alpha);
      }
    }

    // 人工接管：已被手动操控的对象加标记环
    if (state.manual.enabled && state.manual.controlledIds.size > 0) {
      const account = (snap.members || []).find(m => m.keyId === state.manual.keyId);
      if (account) {
        const marks = [];
        if (state.manual.controlledIds.has('CORE') && account.core && account.core.pos) {
          marks.push(account.core.pos);
        }
        for (const u of account.units || []) {
          if (u.pos && state.manual.controlledIds.has(u.id)) marks.push(u.pos);
        }
        ctx.strokeStyle = '#8b5cf6';
        ctx.lineWidth = 2;
        for (const p of marks) {
          if (!inView(p[0], p[1])) continue;
          ctx.beginPath();
          ctx.arc(w2sX(p[0]) + s / 2, w2sY(p[1]) + s / 2, Math.max(6, s * 0.5), 0, Math.PI * 2);
          ctx.stroke();
        }
      }
    }

    updateFloatingUi();
    updateTickWindow();
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

  function drawCore(m, color, alpha, selected, now) {
    const s = state.cam.scale;
    const pos = m.core.pos;
    const cx = w2sX(pos[0]) + s / 2, cy = w2sY(pos[1]) + s / 2;
    const r = Math.max(4, s * 0.42);
    ctx.globalAlpha = alpha;
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
    // 名字：选中时无视缩放常显；淡出状态不画避免文字堆叠
    if (selected || (alpha >= 0.4 && state.cam.scale >= 12)) {
      ctx.font = 'bold 12px sans-serif';
      ctx.textAlign = 'center';
      ctx.fillStyle = color;
      ctx.fillText('@' + (m.gameUsername || ('key-' + m.keyId)), cx, cy - r - (m.core.state === 'MOVING' ? 18 : 6));
    }
    // 选中环（旋转虚线）
    if (selected) {
      ctx.globalAlpha = 0.95;
      ctx.strokeStyle = '#ffffff';
      ctx.lineWidth = 1.5;
      ctx.setLineDash([6, 5]);
      ctx.lineDashOffset = -((now || 0) / 40) % 11;
      ctx.beginPath();
      ctx.arc(cx, cy, r + Math.max(6, s * 0.34), 0, Math.PI * 2);
      ctx.stroke();
      ctx.setLineDash([]);
    }
    ctx.globalAlpha = 1;
  }

  /** 选中成员单位脚下的高亮光环 */
  function drawHalo(wx, wy, color) {
    const s = state.cam.scale;
    const cx = w2sX(wx) + s / 2, cy = w2sY(wy) + s / 2;
    ctx.globalAlpha = 0.16;
    ctx.fillStyle = color;
    ctx.beginPath();
    ctx.arc(cx, cy, Math.max(5, s * 0.55), 0, Math.PI * 2);
    ctx.fill();
    ctx.globalAlpha = 1;
  }

  function drawCommandPath(from, to, type, now, alpha) {
    if (!from || !to) return;
    const s = state.cam.scale;
    const x1 = w2sX(from[0]) + s / 2, y1 = w2sY(from[1]) + s / 2;
    const x2 = w2sX(to[0]) + s / 2, y2 = w2sY(to[1]) + s / 2;
    const attack = type === 'SHOOT' || type === 'SWEEP';
    const color = attack ? '#ff6b8f' : '#22d3ee';
    ctx.save();
    ctx.globalAlpha = Math.max(.15, alpha);
    ctx.strokeStyle = color;
    ctx.fillStyle = color;
    ctx.lineWidth = Math.max(2, s * .08);
    ctx.setLineDash(attack ? [7, 5] : [4, 5]);
    ctx.lineDashOffset = -((now || 0) / 45) % 12;
    ctx.beginPath();
    ctx.moveTo(x1, y1);
    ctx.lineTo(x2, y2);
    ctx.stroke();
    ctx.setLineDash([]);
    const angle = Math.atan2(y2 - y1, x2 - x1);
    const arrow = Math.max(5, s * .22);
    ctx.beginPath();
    ctx.moveTo(x2, y2);
    ctx.lineTo(x2 - Math.cos(angle - .55) * arrow, y2 - Math.sin(angle - .55) * arrow);
    ctx.lineTo(x2 - Math.cos(angle + .55) * arrow, y2 - Math.sin(angle + .55) * arrow);
    ctx.closePath();
    ctx.fill();
    if (attack) {
      ctx.beginPath();
      ctx.arc(x2, y2, Math.max(5, s * (.22 + .05 * Math.sin((now || 0) / 90))), 0, Math.PI * 2);
      ctx.stroke();
    }
    ctx.restore();
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

  // ---------- 成员选中 ----------
  function findMember(keyId) {
    return ((state.snapshot && state.snapshot.members) || []).find(m => m.keyId === keyId);
  }

  function selectMember(keyId, center) {
    state.selectedKeyId = keyId;
    updateSelectedHud();
    if (keyId != null && center) {
      const m = findMember(keyId);
      if (m && m.core && m.core.pos) {
        centerOn(m.core.pos[0], m.core.pos[1], Math.max(state.cam.scale, 16));
      }
    }
  }

  function updateSelectedHud() {
    const el = $id('hud-selected');
    if (!el) return;
    const m = state.selectedKeyId != null ? findMember(state.selectedKeyId) : null;
    if (!m) {
      el.style.display = 'none';
      return;
    }
    el.style.display = '';
    el.innerHTML = '已选中 <b style="color:' + memberColor(m.keyId) + '">@'
      + esc(m.gameUsername || ('key-' + m.keyId)) + '</b> ✕';
  }

  /** 命中检测：优先成员 Core，再成员单位，最后敌人（供点选与悬停提示用） */
  function hitTest(mx, my) {
    const snap = state.snapshot;
    if (!snap) return null;
    const s = state.cam.scale;
    const hitR2 = Math.pow(Math.max(7, s * 0.55), 2);
    const near = (pos) => {
      const dx = mx - (w2sX(pos[0]) + s / 2), dy = my - (w2sY(pos[1]) + s / 2);
      return dx * dx + dy * dy <= hitR2;
    };
    for (const m of snap.members || []) {
      if (m.core && m.core.pos && near(m.core.pos)) return { kind: 'member-core', member: m };
    }
    for (const m of snap.members || []) {
      for (const u of m.units || []) {
        if (u.pos && near(u.pos)) return { kind: 'member-unit', member: m, unit: u };
      }
    }
    for (const e of snap.enemies || []) {
      if (e.pos && near(e.pos)) return { kind: 'enemy', enemy: e };
    }
    return null;
  }

  function tooltipHtml(hit) {
    if (hit.kind === 'member-core') {
      const m = hit.member, c = m.core;
      return '<b style="color:' + memberColor(m.keyId) + '">@' + esc(m.gameUsername || ('key-' + m.keyId)) + '</b>'
        + '<div class="sub">Core ' + (c.hp ?? '-') + 'HP / ' + (c.shield || 0) + '盾 · ['
        + c.pos[0] + ',' + c.pos[1] + ']' + (m.online ? '' : ' · 离线') + '</div>';
    }
    if (hit.kind === 'member-unit') {
      const m = hit.member, u = hit.unit;
      return '<b style="color:' + memberColor(m.keyId) + '">@' + esc(m.gameUsername || ('key-' + m.keyId)) + '</b>'
        + '<div class="sub">' + esc(u.type || '单位') + ' ' + (u.hp ?? '-') + 'HP · ['
        + u.pos[0] + ',' + u.pos[1] + ']' + (u.cargo > 0 ? ' · 载货' : '') + '</div>';
    }
    const e = hit.enemy;
    const what = e.kind === 'CORE' ? ('Core' + (e.owner ? ' @' + esc(e.owner) : '')) : esc(e.type || '单位');
    return '<b style="color:#ff5062">敌方 ' + what + '</b>'
      + '<div class="sub">' + (e.hp != null ? e.hp + 'HP · ' : '') + '[' + e.pos[0] + ',' + e.pos[1] + ']</div>';
  }

  // ---------- 人工接管 ----------
  const manualBar = $id('manual-bar');
  const manualToggle = $id('manual-toggle');
  const manualAccount = $id('manual-account');
  const manualHint = $id('manual-hint');
  const panel = $id('action-panel');
  const targetingPrompt = $id('targeting-prompt');
  const objectPicker = $id('object-picker');

  async function refreshControllable() {
    try {
      const accounts = await api('/api/hosting/controllable');
      state.manual.accounts = accounts;
      manualBar.style.display = accounts.length ? '' : 'none';
      if (!accounts.length) {
        setManualEnabled(false);
        return;
      }
      // 保持已选账号，否则默认第一个
      if (!accounts.some(a => a.keyId === state.manual.keyId)) {
        state.manual.keyId = accounts[0].keyId;
      }
      manualAccount.style.display = accounts.length > 1 ? '' : 'none';
      manualAccount.innerHTML = accounts.map(a =>
        `<option value="${a.keyId}" ${a.keyId === state.manual.keyId ? 'selected' : ''}>@${esc(a.gameUsername || ('key-' + a.keyId))}</option>`
      ).join('');
      const current = accounts.find(a => a.keyId === state.manual.keyId);
      state.manual.controlledIds = new Set((current && current.manualObjectIds) || []);
      updateManualHint();
    } catch (e) {
      manualBar.style.display = 'none';
    }
  }

  function updateManualHint() {
    if (!state.manual.enabled) {
      manualHint.textContent = '开启后可点击自己的单位下达指令';
      return;
    }
    const count = state.manual.controlledIds.size;
    manualHint.textContent = state.manual.targeting
      ? '在地图上点击高亮格执行'
      : (count > 0 ? `本回合已手动指挥 ${count} 个对象，其余仍由指挥官接管` : '点击自己的核心或单位下达指令');
  }

  function setManualEnabled(on) {
    state.manual.enabled = on;
    manualToggle.checked = on;
    manualBar.classList.toggle('active', on);
    if (!on) {
      closePanel();
    }
    updateManualHint();
  }

  manualToggle.onchange = () => {
    setManualEnabled(manualToggle.checked);
    if (manualToggle.checked) refreshControllable();
  };
  manualAccount.onchange = () => {
    state.manual.keyId = Number(manualAccount.value);
    closePanel();
    refreshControllable();
  };

  function closePanel() {
    state.manual.panelObject = null;
    state.manual.targeting = null;
    state.manual.hoverTarget = null;
    state.manual.stackCell = null;
    panel.style.display = 'none';
    panel.classList.remove('submitting');
    targetingPrompt.style.display = 'none';
    objectPicker.style.display = 'none';
    $id('ap-targeting').style.display = 'none';
    updateManualHint();
  }
  $id('ap-close').onclick = closePanel;
  function cancelTargeting() {
    state.manual.targeting = null;
    state.manual.hoverTarget = null;
    $id('ap-targeting').style.display = 'none';
    targetingPrompt.style.display = 'none';
    if (state.manual.panelObject) panel.style.display = '';
    updateManualHint();
  }
  $id('ap-cancel-target').onclick = cancelTargeting;
  $id('targeting-cancel').onclick = cancelTargeting;

  /** 打开某个自己对象的操作面板 */
  async function openActionPanel(objectId) {
    if (tickRemaining() <= 0) {
      toast('本 Tick 指令窗口已结束，请等待下一 Tick', true);
      return;
    }
    const requestedTick = state.snapshot && state.snapshot.tick;
    try {
      const data = await api(`/api/hosting/${state.manual.keyId}/objects/${encodeURIComponent(objectId)}/actions`);
      if (!state.snapshot || data.tick !== state.snapshot.tick || requestedTick !== data.tick) {
        throw Object.assign(new Error('Tick 已更新，请重新选择单位'), {status: 409});
      }
      state.manual.panelObject = data;
      state.manual.targeting = null;
      state.manual.stackCell = null;
      objectPicker.style.display = 'none';
      targetingPrompt.style.display = 'none';
      $id('ap-targeting').style.display = 'none';

      const isCore = data.kind === 'CORE';
      $id('ap-title').textContent = isCore ? '核心' : (data.unitType || '单位');
      const meta = [];
      if (data.pos) meta.push(`位置 [${data.pos[0]},${data.pos[1]}]`);
      if (data.hp != null) meta.push(`HP ${data.hp}`);
      if (data.shield != null) meta.push(`盾 ${data.shield}`);
      if (data.cargo != null && data.cargo > 0) meta.push(`载货 ${data.cargo}`);
      let metaHtml = meta.join(' · ');
      if (isCore && data.coreState === 'MOVING') {
        metaHtml += `<div class="warn-line">⚠ 迁移中（共需 ${data.moveRequiredTicks} 个 Tick），期间无法生产/治疗/接收上缴</div>`;
      }
      $id('ap-meta').innerHTML = metaHtml;

      const ordinary = data.actions.map((a, i) => ({a, i})).filter(x => x.a.type !== 'SPAWN');
      const spawn = data.actions.find(a => a.type === 'SPAWN');
      let actionHtml = ordinary.map(({a, i}) => `
        <div class="ap-act ${a.enabled ? '' : 'disabled'} ${a.dangerous ? 'danger' : ''}" data-idx="${i}" title="${esc(a.note || '')}">
          <span class="ap-name">${esc(a.label)}${a.needsTarget ? ' ▸' : ''}</span>
          ${a.note ? `<span class="ap-note">${esc(a.note)}</span>` : ''}
        </div>`).join('');
      if (spawn) {
        actionHtml += `<div class="ap-spawn-group">
          <div class="ap-spawn-head"><span>生产单位</span><span>当前资源 ${spawn.resources ?? '-'}</span></div>
          <div class="ap-spawn">${(spawn.options || []).map(o => `
            <div class="ap-act ${o.enabled ? '' : 'disabled'}" data-spawn="${esc(o.unitType)}" title="${esc(o.note || '')}">
              <span class="ap-name">${esc(o.label)}</span><span class="ap-note">${o.cost} 资源</span>
            </div>`).join('')}</div></div>`;
      }
      $id('ap-actions').innerHTML = actionHtml;

      $id('ap-actions').querySelectorAll('.ap-act').forEach(el => {
        el.onclick = () => {
          if (el.dataset.spawn) {
            if (el.classList.contains('disabled')) {
              toast(el.title || '当前无法生产该单位', true);
              return;
            }
            execute(data.objectId, {type: 'SPAWN', unit_type: el.dataset.spawn});
            return;
          }
          const action = data.actions[Number(el.dataset.idx)];
          if (!action) return;
          if (!action.enabled) {
            if (action.note) toast(action.note, true);
            return;
          }
          chooseAction(data, action);
        };
      });
      panel.style.display = '';
      requestAnimationFrame(positionActionPanel);
      updateManualHint();
    } catch (e) {
      toast(e.message, true);
      if (e.status === 409 || e.status === 422) closePanel();
    }
  }

  /** 选择动作：需要目标则收起卡片，在地图上进入选格模式 */
  function chooseAction(object, action) {
    if (action.dangerous && !confirm(`确定执行「${action.label}」？${action.note || ''}`)) {
      return;
    }
    if (action.needsTarget) {
      state.manual.targeting = { type: action.type, label: action.label, targets: action.targets, object };
      state.manual.hoverTarget = null;
      $id('targeting-text').textContent = `选择「${action.label}」的高亮目标格`;
      panel.style.display = 'none';
      targetingPrompt.style.display = '';
      updateManualHint();
      return;
    }
    execute(object.objectId, { type: action.type });
  }

  /** 点击目标格后按动作类型组装协议字段 */
  function executeTargeted(targeting, cell) {
    const target = targeting.targets.find(t => t.pos[0] === cell.x && t.pos[1] === cell.y);
    if (!target) {
      toast('该格不是合法目标', true);
      return;
    }
    if (target.ally) {
      toast(target.note || '联盟规则禁止攻击盟友', true);
      return;
    }
    if (target.blocked) {
      toast(target.note || '该格不可选', true);
      return;
    }
    let action;
    if (targeting.type === 'SHOOT') {
      action = { type: 'SHOOT', expected_cell: [cell.x, cell.y] };
    } else {
      action = { type: targeting.type, direction: target.direction };
    }
    execute(targeting.object.objectId, action, target.pos);
  }

  async function execute(objectId, action, targetPos) {
    const operation = state.manual.panelObject;
    if (!operation || state.manual.submitting) return;
    state.manual.submitting = true;
    panel.classList.add('submitting');
    try {
      const accepted = await api(`/api/hosting/${state.manual.keyId}/action`, {
        method: 'POST',
        body: JSON.stringify({ expectedTick: operation.tick, targetId: objectId, action })
      });
      if (state.snapshot && accepted.tick === state.snapshot.tick) {
        state.manual.controlledIds.add(operation.controlId || objectId);
      }
      if (targetPos && operation.pos) {
        state.manual.effect = {
          from: operation.pos, to: targetPos, type: action.type,
          until: performance.now() + 650, duration: 650
        };
      }
      toast('计划已被游戏服务器接受，等待本 Tick 结算');
      state.manual.targeting = null;
      closePanel();
      refreshControllable();
    } catch (e) {
      toast(e.message, true);
      if ([0, 409, 422, 504].includes(e.status)) {
        closePanel();
        refreshControllable();
      }
    } finally {
      state.manual.submitting = false;
      panel.classList.remove('submitting');
    }
  }

  function tickRemaining() {
    const clock = state.tickClock;
    return Math.max(0, clock.remainingMs - (performance.now() - clock.anchoredAt));
  }

  function syncTickClock(snapshot) {
    const previous = state.tickClock.tick;
    state.tickClock.tick = snapshot.tick;
    state.tickClock.windowMs = snapshot.commandWindowMs || 15000;
    state.tickClock.remainingMs = Math.max(0, snapshot.tickRemainingMs || 0);
    state.tickClock.anchoredAt = performance.now();
    if (previous != null && previous !== snapshot.tick) {
      closePanel();
      state.manual.controlledIds = new Set();
    }
  }

  function updateTickWindow() {
    const remaining = tickRemaining();
    const total = Math.max(1, state.tickClock.windowMs);
    const box = $id('tick-window');
    $id('tw-label').textContent = `Tick ${state.tickClock.tick ?? '-'} · 指令窗口${state.snapshot && state.snapshot.tickEstimated ? '（约）' : ''}`;
    $id('tw-remaining').textContent = remaining > 0 ? (remaining / 1000).toFixed(1) + 's' : '等待下一 Tick';
    $id('tw-progress').style.width = Math.min(100, remaining / total * 100) + '%';
    box.classList.toggle('warn', remaining > 2000 && remaining <= 5000);
    box.classList.toggle('danger', remaining <= 2000);
    box.classList.toggle('offline', !state.sseOk);
  }

  function overlaps(a, b) {
    return a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top;
  }

  function reservedRects() {
    const wrap = canvas.parentElement.getBoundingClientRect();
    return ['tick-window', 'targeting-prompt', 'legend', 'manual-bar'].map(id => $id(id))
      .filter(el => el && el.style.display !== 'none')
      .map(el => {
        const r = el.getBoundingClientRect();
        return {left: r.left - wrap.left - 6, top: r.top - wrap.top - 6,
          right: r.right - wrap.left + 6, bottom: r.bottom - wrap.top + 6};
      });
  }

  function positionActionPanel() {
    const object = state.manual.panelObject;
    if (!object || panel.style.display === 'none' || !object.pos) return;
    const s = state.cam.scale;
    const ax = w2sX(object.pos[0]) + s / 2, ay = w2sY(object.pos[1]) + s / 2;
    const width = panel.offsetWidth, height = panel.offsetHeight;
    const maxW = canvas.clientWidth, maxH = canvas.clientHeight, gap = Math.max(10, s * .45);
    if (ax < -s || ay < -s || ax > maxW + s || ay > maxH + s) {
      closePanel();
      return;
    }
    const candidates = [
      {side: 'right', left: ax + gap, top: ay - height / 2},
      {side: 'left', left: ax - gap - width, top: ay - height / 2},
      {side: 'bottom', left: ax - width / 2, top: ay + gap},
      {side: 'top', left: ax - width / 2, top: ay - gap - height}
    ];
    const reserved = reservedRects();
    let chosen = candidates.find(c => c.left >= 10 && c.top >= 10
      && c.left + width <= maxW - 10 && c.top + height <= maxH - 10
      && !reserved.some(r => overlaps({left: c.left, top: c.top, right: c.left + width, bottom: c.top + height}, r)));
    if (!chosen) chosen = candidates[0];
    panel.style.left = Math.max(10, Math.min(maxW - width - 10, chosen.left)) + 'px';
    panel.style.top = Math.max(10, Math.min(maxH - height - 10, chosen.top)) + 'px';
    panel.dataset.side = chosen.side;
  }

  function positionObjectPicker() {
    const cell = state.manual.stackCell;
    if (!cell || objectPicker.style.display === 'none') return;
    const s = state.cam.scale;
    const x = w2sX(cell[0]) + s / 2;
    const y = w2sY(cell[1]) - objectPicker.offsetHeight - 10;
    objectPicker.style.left = Math.max(8, Math.min(canvas.clientWidth - objectPicker.offsetWidth - 8,
      x - objectPicker.offsetWidth / 2)) + 'px';
    objectPicker.style.top = Math.max(8, y) + 'px';
  }

  function updateFloatingUi() {
    positionActionPanel();
    positionObjectPicker();
  }

  function manualObjectsAt(cell) {
    const account = ((state.snapshot && state.snapshot.members) || [])
      .find(m => m.keyId === state.manual.keyId);
    if (!account) return [];
    const objects = [];
    if (account.core && account.core.pos && account.core.pos[0] === cell.x && account.core.pos[1] === cell.y) {
      objects.push({id: account.core.id, label: 'Core'});
    }
    for (const unit of account.units || []) {
      if (unit.pos && unit.pos[0] === cell.x && unit.pos[1] === cell.y) {
        objects.push({id: unit.id, label: unit.type || '单位'});
      }
    }
    return objects;
  }

  function showObjectPicker(objects, cell) {
    closePanel();
    state.manual.stackCell = [cell.x, cell.y];
    objectPicker.innerHTML = objects.map((o, i) => `<button class="btn sm" data-index="${i}">${esc(o.label)}</button>`).join('');
    objectPicker.querySelectorAll('button').forEach(button => {
      button.onclick = () => openActionPanel(objects[Number(button.dataset.index)].id);
    });
    objectPicker.style.display = '';
    requestAnimationFrame(positionObjectPicker);
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
    const mx = e.clientX - rect.left, my = e.clientY - rect.top;
    const wx = Math.floor(s2wX(mx));
    const wy = Math.floor(s2wY(my));
    $id('hud-pos').textContent = '[' + wx + ', ' + wy + ']';

    if (state.manual.targeting && e.target === canvas) {
      state.manual.hoverTarget = state.manual.targeting.targets.find(t =>
        t.pos[0] === wx && t.pos[1] === wy && !t.blocked && !t.ally) || null;
    } else {
      state.manual.hoverTarget = null;
    }

    // 悬停提示（仅在画布上且未拖拽时）
    const tip = $id('map-tooltip');
    const hit = (!dragging && e.target === canvas) ? hitTest(mx, my) : null;
    if (tip) {
      if (hit) {
        tip.innerHTML = tooltipHtml(hit);
        tip.style.left = (mx + 14) + 'px';
        tip.style.top = (my + 14) + 'px';
        tip.style.display = 'block';
      } else {
        tip.style.display = 'none';
      }
    }
    canvas.style.cursor = state.manual.targeting ? 'crosshair' : (hit && hit.kind !== 'enemy' ? 'pointer' : '');

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
  // 点选：人工接管模式优先（选格执行 / 点自己单位开面板），否则为成员选中
  canvas.addEventListener('click', e => {
    if (moved >= 5) return;
    const rect = canvas.getBoundingClientRect();
    const mx = e.clientX - rect.left, my = e.clientY - rect.top;
    const clickedCell = {x: Math.floor(s2wX(mx)), y: Math.floor(s2wY(my))};

    if (state.manual.enabled && tickRemaining() <= 0) {
      toast('本 Tick 指令窗口已结束，请等待下一 Tick', true);
      return;
    }

    // ① 选格模式：点击高亮格执行动作
    if (state.manual.enabled && state.manual.targeting) {
      executeTargeted(state.manual.targeting, {
        x: clickedCell.x, y: clickedCell.y
      });
      return;
    }

    if (state.manual.enabled) {
      const ownObjects = manualObjectsAt(clickedCell);
      if (ownObjects.length > 1) {
        showObjectPicker(ownObjects, clickedCell);
        return;
      }
      if (ownObjects.length === 1) {
        openActionPanel(ownObjects[0].id);
        return;
      }
      objectPicker.style.display = 'none';
      state.manual.stackCell = null;
    }

    const hit = hitTest(mx, my);
    // ② 人工接管：点自己账号的对象 → 打开操作面板
    if (state.manual.enabled && hit && (hit.kind === 'member-core' || hit.kind === 'member-unit')
        && hit.member.keyId === state.manual.keyId) {
      openActionPanel(hit.kind === 'member-core' ? hit.member.core.id : hit.unit.id);
      return;
    }

    if (hit && (hit.kind === 'member-core' || hit.kind === 'member-unit')) {
      selectMember(hit.member.keyId === state.selectedKeyId ? null : hit.member.keyId, false);
    } else {
      selectMember(null, false);
    }
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

  window.addEventListener('keydown', e => {
    if (e.key !== 'Escape') return;
    if (state.manual.targeting) cancelTargeting();
    else closePanel();
  });

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
  $id('hud-selected').onclick = () => selectMember(null, false);

  // ---------- 侧栏（Vue 组件，直接绑定响应式 state） ----------
  Vue.createApp({
    setup() {
      const tab = Vue.ref('members');
      const members = Vue.computed(() => {
        const list = (state.snapshot && state.snapshot.members) || [];
        return [...list].sort((a, b) => (a.gameUsername || '').localeCompare(b.gameUsername || ''));
      });
      return {
        tab,
        members,
        incidents: Vue.computed(() => state.incidents),
        feedItems: Vue.computed(() => state.incidents.slice(0, 120)),
        selectedKeyId: Vue.computed(() => state.selectedKeyId),
        color: m => memberColor(m.keyId),
        troops: m => troopSummary(m.units),
        coreText(m) {
          if (m.core) return (m.core.hp ?? '-') + 'HP/' + (m.core.shield || 0) + '盾';
          return m.status === 'RESPAWNING' ? '重生中' : '-';
        },
        hpPercent: m => m.core ? Math.min(100, (m.core.hp || 0) * 20) : 0,
        hostingTitle(h) {
          if (h.status === 'RUNNING') return '联盟托管中 · ' + h.mode;
          if (h.status === 'WAITING_STATE') return '正在等待新的游戏状态';
          if (h.status === 'DISCONNECTED') return '游戏连接已断开，正在重连';
          return '托管冲突暂停';
        },
        toggleSelect: m => selectMember(m.keyId === state.selectedKeyId ? null : m.keyId, true),
        feedLabel: i => TYPE_LABEL[i.type] || i.type,
        feedClass: i => TYPE_CLASS[i.type] || '',
        feedWho(i) {
          const who = [];
          if (i.attackerName) who.push('攻击者 @' + i.attackerName);
          if (i.victimName) who.push('目标 @' + i.victimName);
          return who.join(' → ');
        },
        time: fmtTime
      };
    }
  }).mount('#side-app');

  function pushIncident(incident) {
    state.incidents.unshift(incident);
    if (state.incidents.length > 300) state.incidents.length = 300;
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
        const nextSnapshot = JSON.parse(e.data);
        const tickChanged = state.snapshot && state.snapshot.tick !== nextSnapshot.tick;
        syncTickClock(nextSnapshot);
        state.snapshot = nextSnapshot;
        if (tickChanged) refreshControllable();
        setAllianceName(state.snapshot.allianceName);
        // 选中的成员已不在快照中（被踢/停用）→ 自动取消选中
        if (state.selectedKeyId != null && !findMember(state.selectedKeyId)) {
          state.selectedKeyId = null;
        }
        updateSelectedHud();
        updateHud();
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

  // 自动化/无障碍检查钩子：只输出当前可见和可交互状态，不保留历史迷雾数据。
  window.render_game_to_text = () => {
    const snapshot = state.snapshot;
    const members = ((snapshot && snapshot.members) || []).map(member => ({
      keyId: member.keyId,
      core: member.core ? {id: member.core.id, pos: member.core.pos} : null,
      units: (member.units || []).map(unit => ({id: unit.id, type: unit.type, pos: unit.pos}))
    }));
    return JSON.stringify({
      mode: 'alliance-map',
      coordinateSystem: 'world grid [x,y], origin [0,0], x right, y down',
      tick: snapshot ? snapshot.tick : null,
      tickRemainingMs: Math.round(tickRemaining()),
      connected: state.sseOk,
      camera: state.cam,
      selectedKeyId: state.selectedKeyId,
      manual: {
        enabled: state.manual.enabled,
        keyId: state.manual.keyId,
        panelObjectId: state.manual.panelObject && state.manual.panelObject.objectId,
        targeting: state.manual.targeting && state.manual.targeting.type,
        targetCount: state.manual.targeting ? state.manual.targeting.targets.length : 0
      },
      members,
      enemies: ((snapshot && snapshot.enemies) || []).map(enemy => ({
        id: enemy.id, kind: enemy.kind, type: enemy.type, pos: enemy.pos
      })),
      beacon: snapshot && snapshot.beacon ? snapshot.beacon.pos : null
    });
  };

  window.advanceTime = ms => {
    const amount = Number.isFinite(ms) ? Math.max(0, ms) : 0;
    state.tickClock.anchoredAt -= amount;
    if (state.manual.effect) state.manual.effect.until -= amount;
    for (const alert of state.alerts) alert.until -= amount;
    updateTickWindow();
  };

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
      syncTickClock(state.snapshot);
      setAllianceName(state.snapshot.allianceName);
      // 初始视角：第一个有 Core 的成员，否则信标
      const withCore = (state.snapshot.members || []).find(m => m.core && m.core.pos);
      if (withCore) centerOn(withCore.core.pos[0], withCore.core.pos[1]);
      else if (state.snapshot.beacon && state.snapshot.beacon.pos) {
        centerOn(state.snapshot.beacon.pos[0], state.snapshot.beacon.pos[1]);
      }
      updateHud();
    } catch (e) {
      toast(e.message, true);
    }
    try {
      state.incidents = await api('/api/map/incidents?limit=50');
    } catch (e) { }
    connectSse();
    requestAnimationFrame(render);

    // 人工接管：探测可操控账号（仅托管运行中的自己的 key），并随 Tick 刷新
    refreshControllable();
    setInterval(() => {
      if (manualBar.style.display !== 'none' || state.manual.accounts.length === 0) {
        refreshControllable();
      }
    }, 5000);
  })();
})();
