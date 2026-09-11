/* global window, document, Game */
(function (G) {
  'use strict';

  var Core = G.Core;

  // ====================================================================
  //  状态
  // ====================================================================
  var state = {
    chapters: [],   // 拉到的任务章节
    guide: null,    // 当前引导步骤
    pollTimer: null // 引导状态轮询定时器
  };

  // 玩家手动收起新手指引的本地状态 (按账号隔离).
  // 持久化到 localStorage, 避免刷新页面后又弹出来.
  // 引导步骤完成/换步时不会自动展开 — 必须由玩家点 edgeTab 才会出现.
  function _guideCollapsedKey() {
    var u = (G.API && G.API.getUsername && G.API.getUsername()) || 'guest';
    return 'wg.guide.collapsed.v1.' + u;
  }
  function isGuideCollapsed() {
    try { return localStorage.getItem(_guideCollapsedKey()) === '1'; }
    catch (e) { return false; }
  }
  function setGuideCollapsed(v) {
    try {
      if (v) localStorage.setItem(_guideCollapsedKey(), '1');
      else localStorage.removeItem(_guideCollapsedKey());
    } catch (e) { /* noop */ }
  }

  // ====================================================================
  //  API
  // ====================================================================
  function loadQuests() {
    return G.API.getQuestList().then(function (data) {
      state.chapters = data && data.chapters ? data.chapters : [];
      return state.chapters;
    });
  }

  function loadGuide() {
    return G.API.getGuide().then(function (g) {
      state.guide = g;
      maybeStartPolling();
      return g;
    });
  }

  function claimQuest(questId) {
    return G.API.claimQuest(questId);
  }

  function advanceGuide() {
    return G.API.advanceGuide().then(function (g) {
      // 兼容: 服务端失败时返回 { success:false, message, guide }
      if (g && g.guide) g = g.guide;
      state.guide = g;
      return g;
    });
  }

  function skipGuide() {
    return G.API.skipGuide().then(function (g) {
      state.guide = g;
      return g;
    });
  }

  // 毕业 toast 是否已经显示过，持久化到 localStorage 防止刷新页面后重复弹出。
  var GRADUATION_KEY = 'wargame_guide_graduation';
  function isGraduationShown() {
    return localStorage.getItem(GRADUATION_KEY) === '1';
  }
  function markGraduationShown() {
    localStorage.setItem(GRADUATION_KEY, '1');
  }

  // ====================================================================
  //  引导浮窗（带全屏遮罩，挡住背景所有点击）
  //  设计: 实时显示目标进度，提供"去完成"按钮直达目标位置。
  //  两种状态:
  //   - 阻塞态 (blocking): mask 开着, 玩家只能点引导自己的按钮
  //       适用: g_welcome (欢迎页) / 目标达成时 (强制点"下一步")
  //   - 放行态 (action):  mask 关着, bubble 保留但玩家可与建筑交互
  //       适用: g_upgrade_command / g_build_* / g_recruit_* / g_appoint_mayor
  // ====================================================================
  function isBlockingState(g) {
    if (!g || g.done) return false;
    if (g.id === 'g_welcome') return true;        // 欢迎页必须先点"开始训练"
    if (g.id === 'g_done') return false;         // 毕业 toast 不需要 mask
    var p = g.progress || {};
    if (p.complete) return true;                 // 目标达成, 强制点"下一步"
    return false;                                // 其余 (action 步骤) 放行
  }

  function renderGuideBubble() {
    // 移除旧的（包括遮罩和浮窗）
    var oldMask = document.getElementById('guideMask');
    if (oldMask) oldMask.parentNode.removeChild(oldMask);
    var oldBubble = document.getElementById('guideBubble');
    if (oldBubble) oldBubble.parentNode.removeChild(oldBubble);
    var oldEdge = document.getElementById('guideEdgeTab');
    if (oldEdge) oldEdge.parentNode.removeChild(oldEdge);

    var g = state.guide;
    if (!g || g.done) {
      stopPolling();
      return;
    }
    if (g.id === 'g_done') {
      // 毕业步骤: 仅在第一次返回时显示一次性祝贺气泡。
      // 玩家点击升级建筑等操作会再次触发 refresh()，此时不再重复弹窗。
      if (!isGraduationShown()) {
        markGraduationShown();
        renderGraduationToast(g);
      }
      stopPolling();
      return;
    }

    // 玩家手动收起了引导 -> 只显示右侧的 edgeTab, 浮窗不再渲染
    if (isGuideCollapsed()) {
      renderGuideEdgeTab(g);
      // 收起时也要清掉遮罩, 不挡玩家操作
      return;
    }

    var blocking = isBlockingState(g);

    // 阻塞态才挂遮罩; 放行态不挡, 让玩家去点建筑/征兵按钮
    if (blocking) {
      var oldMask = document.getElementById('guideMask');
      if (!oldMask) {
        var mask = document.createElement('div');
        mask.id = 'guideMask';
        mask.style.cssText =
          'position:fixed;inset:0;background:rgba(0,0,0,0.55);' +
          'z-index:997;cursor:not-allowed;';
        mask.addEventListener('click', function (e) {
          e.stopPropagation();
          e.preventDefault();
        }, true);
        document.body.appendChild(mask);
      }
    } else {
      // 放行态: 移除遮罩, 但保留浮窗
      var existingMask = document.getElementById('guideMask');
      if (existingMask && existingMask.parentNode) {
        existingMask.parentNode.removeChild(existingMask);
      }
    }

    var bubble = document.createElement('div');
    bubble.id = 'guideBubble';
    // 阻塞态: 居中靠下, 大气泡; 放行态: 右上角, 小气泡, 不挡建筑
    if (blocking) {
      bubble.style.cssText =
        'position:fixed;left:50%;bottom:80px;transform:translateX(-50%);' +
        'max-width:400px;width:calc(100vw - 32px);' +
        'background:linear-gradient(135deg,#3a2e1f,#2a1f12);' +
        'color:#f4e9d1;border:1px solid #8b6b3a;border-radius:10px;padding:14px 16px;' +
        'box-shadow:0 6px 20px rgba(0,0,0,0.6);z-index:998;font-size:13px;' +
        'animation:guideSlideUp 0.35s ease;';
    } else {
      // 放行态: 右下角小卡片, 不挡建筑/征兵按钮也不挡顶部导航
      bubble.style.cssText =
        'position:fixed;right:12px;bottom:12px;' +
        'max-width:280px;width:240px;' +
        'background:linear-gradient(135deg,#3a2e1f,#2a1f12);' +
        'color:#f4e9d1;border:1px solid #8b6b3a;border-radius:8px;padding:10px 12px;' +
        'box-shadow:0 4px 14px rgba(0,0,0,0.5);z-index:998;font-size:12px;' +
        'animation:guideSlideUp 0.35s ease;';
    }

    // 进度
    var prog = g.progress || { current: 0, target: 1, pct: 0, complete: false };

    var progress =
      '<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:6px;' +
      'font-size:11px;color:#c9a96a">' +
      '<span>📋 新手训练营 ' + (g.index || '?') + '/' + (g.total || '?') + '</span>' +
      '<span style="cursor:pointer;text-decoration:underline" id="guideSkip">跳过引导</span>' +
      '</div>';

    // 放行态顶部加一行操作提示
    var actionHint = '';
    if (!blocking && g.nextRoute) {
      actionHint =
        '<div style="background:rgba(255,217,122,0.12);border:1px dashed #ffd97a;' +
        'border-radius:4px;padding:4px 6px;margin-bottom:6px;font-size:11px;color:#ffd97a">' +
          '👉 请在 ' + getRouteLabel(g.nextRoute) + ' 中完成下方目标' +
        '</div>';
    }

    var titleHtml =
      '<div style="font-weight:bold;font-size:' + (blocking ? '15px' : '13px') + ';' +
      'margin-bottom:4px;color:#ffd97a">' +
        escapeHtml(g.title || '新手引导') +
      '</div>';

    // 操作阶段同样必须显示具体做法；仅按状态调整字号与间距。
    // 否则玩家会在目标完成前看不到该如何执行。
    var bodyHtml = '<div style="line-height:' + (blocking ? '1.6' : '1.45') + ';' +
      'white-space:pre-wrap;margin-bottom:' + (blocking ? '8px' : '6px') + ';' +
      'font-size:' + (blocking ? '13px' : '11px') + ';color:#f4e9d1">' +
      escapeHtml(g.body || '') + '</div>';

    // 目标进度条 (放行态下更紧凑)
    var goalText = g.goal || '';
    var progressBar = blocking
      ? renderProgressBar(prog, goalText)
      : renderProgressBarCompact(prog, goalText);

    // 奖励预览 (放行态隐藏文字奖励以节省空间)
    var rewardHtml = blocking ? renderRewardPreview(g.reward) : '';

    // 解析引导目标: 优先取具体建筑 (有 data-building 的卡片),
    // 没有则只跳到对应区域. 有了具体目标后, 跳转后能 scrollIntoView + 高亮.
    var target = resolveGuideTarget(g);

    // 按钮区
    var goBtn = '';
    if (g.nextRoute) {
      var targetLabel = target.buildingName
        ? ('→ ' + (target.verb || '前往') + ' ' + target.buildingName)
        : ('→ ' + getRouteLabel(g.nextRoute));
      if (blocking) {
        goBtn =
          '<button class="btn sm ok" id="guideGo" style="flex:1">' +
            targetLabel +
          '</button>';
      } else {
        // 放行态下, 玩家已经在该区域, 给一个"滚到目标"的小按钮
        goBtn =
          '<button class="btn sm" id="guideGoHome" style="flex:0 0 auto;font-size:11px;padding:4px 8px">' +
            (target.buildingName
              ? ('→ ' + (target.verb || '前往') + ' ' + target.buildingName)
              : ('← 返回 ' + getRouteLabel(g.nextRoute))) +
          '</button>';
      }
    }

    var nextBtnDisabled = !prog.complete;
    var nextBtnLabel = prog.complete
      ? (g.id === 'g_welcome' ? '开始训练' : '下一步 ✓')
      : '目标未完成';

    var nextBtn =
      '<button class="btn sm' + (prog.complete ? ' ok' : ' lock') + '" ' +
        'id="guideNext" style="flex:1"' +
        (nextBtnDisabled ? ' disabled' : '') +
        '>' +
        nextBtnLabel +
      '</button>';

    bubble.innerHTML =
      '<style>@keyframes guideSlideUp{from{transform:translate(0,30px);opacity:0}to{transform:translate(0,0);opacity:1}}' +
        '@keyframes guideEdgeTabPulse{0%,100%{box-shadow:0 0 0 0 rgba(255,217,122,0.55)}70%{box-shadow:0 0 0 10px rgba(255,217,122,0)}}</style>' +
      // 主体内容 (居中, 收起把手改为悬浮绝对定位, 不占气泡宽度)
      '<div style="position:relative">' +
        progress + actionHint + titleHtml + bodyHtml + progressBar + rewardHtml +
        '<div style="margin-top:8px;display:flex;gap:6px">' +
          goBtn + nextBtn +
        '</div>' +
        // 左侧悬浮把手: 8px 宽细条, 几乎隐形, hover 才显形
        '<div id="guideCollapseBtn" title="收起引导 (完成后可从右侧展开)" ' +
          'style="position:absolute;left:-1px;top:50%;transform:translate(-100%,-50%);' +
          'width:8px;height:34px;display:flex;align-items:center;justify-content:center;' +
          'cursor:pointer;background:rgba(255,217,122,0.08);' +
          'border:1px solid rgba(139,107,58,0.6);border-right:none;' +
          'border-radius:4px 0 0 4px;' +
          'color:rgba(255,217,122,0.55);font-size:10px;line-height:1;' +
          'user-select:none;transition:all 0.15s">' +
          // 箭头方向: 指向气泡内容 (›), 暗示"点击这里把气泡藏起来"
          '<span style="margin-left:2px">›</span>' +
        '</div>' +
      '</div>';

    document.body.appendChild(bubble);

    // 绑定收起按钮
    var collapseBtnEl = document.getElementById('guideCollapseBtn');
    if (collapseBtnEl) {
      collapseBtnEl.onclick = function (e) {
        e.stopPropagation();
        setGuideCollapsed(true);
        renderGuideBubble();
      };
      // hover 高亮: 背景与文字都加深, 提示这是个按钮
      collapseBtnEl.onmouseenter = function () {
        collapseBtnEl.style.background = 'rgba(255,217,122,0.25)';
        collapseBtnEl.style.color = '#ffd97a';
        collapseBtnEl.style.borderColor = '#8b6b3a';
      };
      collapseBtnEl.onmouseleave = function () {
        collapseBtnEl.style.background = 'rgba(255,217,122,0.08)';
        collapseBtnEl.style.color = 'rgba(255,217,122,0.55)';
        collapseBtnEl.style.borderColor = 'rgba(139,107,58,0.6)';
      };
    }

    // 绑定按钮
    var goBtnEl = document.getElementById('guideGo');
    if (goBtnEl) {
      goBtnEl.onclick = function (e) {
        e.stopPropagation();
        try { Core.go(g.nextRoute); } catch (err) {}
        // 阻塞态: 跳转后 mask + bubble 都已经在新位置, 玩家可直接操作建筑
        // (放行态下不会有这个按钮)
        scrollToGuideTarget(target);
      };
    }
    // 放行态下, 给玩家一个"返回任务页"的小按钮 (避免迷路)
    var goHomeBtnEl = document.getElementById('guideGoHome');
    if (goHomeBtnEl) {
      goHomeBtnEl.onclick = function (e) {
        e.stopPropagation();
        // 按当前引导目标真正切换到对应 Tab（资源区/军事区等）
        try { Core.go(g.nextRoute); } catch (err) {}
        // 等待 view 重新渲染后滚动并高亮目标建筑
        setTimeout(function () { scrollToGuideTarget(target); }, 50);
      };
    }
    var nextBtnEl = document.getElementById('guideNext');
    nextBtnEl.onclick = function (e) {
      e.stopPropagation();
      if (nextBtnEl.disabled) return;
      nextBtnEl.disabled = true;
      nextBtnEl.textContent = '处理中...';
      advanceGuide().then(function (newGuide) {
        // 服务端仅在奖励成功入账后才返回 completedReward。
        if (newGuide && newGuide.completedReward) {
          G.toast && G.toast('✓ ' + (newGuide.completedStepTitle || '步骤完成') +
            '：获得 ' + formatRewardText(newGuide.completedReward));
        }
        if (newGuide && newGuide.done) {
          removeGuideDom();
          G.toast && G.toast('🎉 新手训练营毕业！从今天起，你的征途不再只是生存，而是去赢得属于你的荣耀。');
        } else if (newGuide && newGuide.id) {
          renderGuideBubble();
          if (!newGuide.completedReward && newGuide.complete && newGuide.id !== g.id) {
            G.toast && G.toast('✓ 步骤完成，进入下一阶段');
          }
        } else if (newGuide && newGuide.message) {
          G.toast && G.toast(newGuide.message);
          nextBtnEl.disabled = false;
          nextBtnEl.textContent = nextBtnLabel;
        }
      });
    };
    document.getElementById('guideSkip').onclick = function (e) {
      e.stopPropagation();
      if (!confirm('确认跳过整个新手训练营？后续仍可继续完成主线任务。')) return;
      skipGuide().then(function () {
        removeGuideDom();
        G.toast && G.toast('已跳过新手引导');
      });
    };
  }

  // 收起态: 屏幕右侧贴边的可展开小标签
  // - 气泡原本在右下角, 收起后 edge tab 也放在右下角, 位置不跳
  // - 内容只有一步的小图标 + 步骤号, 完成后脉冲提示
  // - 箭头方向 (‹) 指向气泡原本出现的位置 (左), 暗示"点我把气泡从左边展开"
  function renderGuideEdgeTab(g) {
    var prog = g.progress || { current: 0, target: 1, complete: false };
    var isDone = !!prog.complete;
    var stepLabel = (g.index || '?') + '/' + (g.total || '?');

    var tab = document.createElement('div');
    tab.id = 'guideEdgeTab';
    tab.style.cssText =
      'position:fixed;right:0;bottom:14px;z-index:998;cursor:pointer;user-select:none;' +
      'display:inline-flex;align-items:center;gap:4px;' +
      'padding:5px 7px 5px 8px;' +
      'background:rgba(58,46,31,0.92);' +
      'color:#ffd97a;border:1px solid #8b6b3a;border-right:none;' +
      'border-radius:14px 0 0 14px;' +
      'box-shadow:0 2px 8px rgba(0,0,0,0.45);' +
      'font-size:12px;line-height:1;backdrop-filter:blur(2px);' +
      (isDone ? 'animation:guideEdgeTabPulse 1.6s infinite;' : '');
    tab.title = isDone ? '目标已完成, 点击展开' : '点击展开新手引导';
    // 箭头 + 图标 + 步骤号: 简洁一行, 不挤压屏幕
    tab.innerHTML =
      '<span style="font-size:14px;line-height:1">‹</span>' +
      '<span style="font-size:13px;line-height:1">📋</span>' +
      '<span style="font-size:11px;font-weight:600;letter-spacing:0.3px">' + stepLabel + '</span>' +
      (isDone ? '<span style="font-size:10px;color:#9bff9b;margin-left:2px">✓</span>' : '');
    tab.onclick = function (e) {
      e.stopPropagation();
      setGuideCollapsed(false);
      renderGuideBubble();
    };
    // hover: 背景稍亮
    tab.onmouseenter = function () { tab.style.background = 'rgba(80,64,42,0.96)'; };
    tab.onmouseleave = function () { tab.style.background = 'rgba(58,46,31,0.92)'; };
    document.body.appendChild(tab);
  }

  function renderProgressBar(prog, goalText) {
    var pct = prog.pct || 0;
    var color = prog.complete ? '#5fd76b' : '#ffd97a';
    var statusText = prog.complete ? '✅ 目标达成' : '⏳ 进行中';
    var html =
      '<div style="background:rgba(0,0,0,0.3);border-radius:6px;padding:8px;margin:6px 0">' +
        '<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:4px;font-size:12px">' +
          '<span style="color:' + color + '">🎯 ' + escapeHtml(goalText) + '</span>' +
          '<span style="color:#c9a96a">' + prog.current + '/' + prog.target + '</span>' +
        '</div>' +
        '<div style="height:8px;background:#1a1208;border-radius:4px;overflow:hidden">' +
          '<div style="height:100%;width:' + pct + '%;background:' + color + ';transition:width 0.3s ease"></div>' +
        '</div>' +
        '<div style="margin-top:4px;font-size:11px;color:#c9a96a">' + statusText + '</div>' +
      '</div>';
    return html;
  }

  // 放行态的紧凑版进度条: 1 行文字 + 1 条横线
  function renderProgressBarCompact(prog, goalText) {
    var pct = prog.pct || 0;
    var color = prog.complete ? '#5fd76b' : '#ffd97a';
    var html =
      '<div style="background:rgba(0,0,0,0.3);border-radius:4px;padding:5px 6px;margin:4px 0">' +
        '<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:3px;font-size:11px">' +
          '<span style="color:' + color + '">🎯 ' + escapeHtml(goalText) + '</span>' +
          '<span style="color:#c9a96a">' + prog.current + '/' + prog.target + '</span>' +
        '</div>' +
        '<div style="height:5px;background:#1a1208;border-radius:3px;overflow:hidden">' +
          '<div style="height:100%;width:' + pct + '%;background:' + color + ';transition:width 0.3s ease"></div>' +
        '</div>' +
      '</div>';
    return html;
  }

  // 物品 ID -> 中文显示名 (用于任务奖励预览/到账提示)
  function itemLabel(key) {
    if (!key) return '';
    var def = (G.DATA && G.DATA.items && G.DATA.items[key]);
    if (def && def.name) return def.icon ? def.icon + def.name : def.name;
    return key;
  }

  function renderRewardPreview(reward) {
    if (!reward) return '';
    var parts = [];
    if (reward.food)  parts.push('粮' + shortNum(reward.food));
    if (reward.steel) parts.push('钢' + shortNum(reward.steel));
    if (reward.oil)   parts.push('油' + shortNum(reward.oil));
    if (reward.rare)  parts.push('稀' + shortNum(reward.rare));
    if (reward.gold)  parts.push('金' + shortNum(reward.gold));
    if (reward.skillBook) parts.push('技能书×' + reward.skillBook);
    if (reward.expBook) parts.push('经验书×' + reward.expBook);
    if (reward.itemKey && reward.itemCount) parts.push(itemLabel(reward.itemKey) + '×' + reward.itemCount);
    if (parts.length === 0) return '';
    return '<div style="font-size:11px;color:#7fc4ff;margin-top:4px">🎁 完成后奖励: ' + parts.join(' ') + '</div>';
  }

  // 用于完成时 toast 的到账奖励文字（不带“奖励:”前缀）。
  function formatRewardText(reward) {
    if (!reward) return '无';
    var parts = [];
    if (reward.food) parts.push('粮' + shortNum(reward.food));
    if (reward.steel) parts.push('钢' + shortNum(reward.steel));
    if (reward.oil) parts.push('油' + shortNum(reward.oil));
    if (reward.rare) parts.push('稀' + shortNum(reward.rare));
    if (reward.gold) parts.push('金' + shortNum(reward.gold));
    if (reward.skillBook) parts.push('技能书×' + reward.skillBook);
    if (reward.expBook) parts.push('经验书×' + reward.expBook);
    if (reward.itemKey && reward.itemCount) parts.push(itemLabel(reward.itemKey) + '×' + reward.itemCount);
    return parts.length ? parts.join('、') : '无';
  }

  function renderGraduationToast(g) {
    // 不带遮罩的一次性气泡，玩家可关闭
    var oldBubble = document.getElementById('guideBubble');
    if (oldBubble) oldBubble.parentNode.removeChild(oldBubble);
    var bubble = document.createElement('div');
    bubble.id = 'guideBubble';
    bubble.style.cssText =
      'position:fixed;left:50%;bottom:80px;transform:translateX(-50%);' +
      'max-width:400px;width:calc(100vw - 32px);' +
      'background:linear-gradient(135deg,#3a2e1f,#2a1f12);' +
      'color:#f4e9d1;border:1px solid #8b6b3a;border-radius:10px;padding:14px 16px;' +
      'box-shadow:0 6px 20px rgba(0,0,0,0.6);z-index:998;font-size:13px;';
    bubble.innerHTML =
      '<div style="font-weight:bold;font-size:15px;margin-bottom:6px;color:#ffd97a">' +
        escapeHtml(g.title || '训练营毕业') +
      '</div>' +
      '<div style="line-height:1.6;white-space:pre-wrap;margin-bottom:8px">' +
        escapeHtml(g.body || '') +
      '</div>' +
      renderRewardPreview(g.reward) +
      '<div style="margin-top:10px;text-align:right">' +
        '<button class="btn sm ok" id="guideClose">关闭</button>' +
      '</div>';
    document.body.appendChild(bubble);
    document.getElementById('guideClose').onclick = function () {
      removeGuideDom();
    };
    // 5 秒后自动关闭
    setTimeout(function () { removeGuideDom(); }, 8000);
  }

  function removeGuideDom() {
    stopPolling();
    var m = document.getElementById('guideMask');
    if (m && m.parentNode) m.parentNode.removeChild(m);
    var b = document.getElementById('guideBubble');
    if (b && b.parentNode) b.parentNode.removeChild(b);
    var e = document.getElementById('guideEdgeTab');
    if (e && e.parentNode) e.parentNode.removeChild(e);
  }

  function getRouteLabel(route) {
    return ({
      buildRes: '资源区',
      buildArmy: '军事区',
      world: '地图',
      home: '主城',
      officer: '军官',
      academy: '军校',
      army: '兵种',
      mainQuest: '主线任务'
    })[route] || route;
  }

  /**
   * 根据当前引导步骤解析"具体目标" — 优先返回具体建筑,
   * 没有则只返回区域. 用于:
   *  1) 决定底部按钮文案 (例如 "→ 前往 军校" 而不是 "→ 军事区")
   *  2) 跳转后 scrollIntoView + 高亮 (CSS .guide-target)
   *
   * 解析规则:
   *  - checkKey 命中 DATA.buildings → 是建筑, 带入 buildingName / buildingKey
   *  - 否则只带 routeLabel, 跳转后只停在区域
   *
   * @returns {{ route, routeLabel, buildingKey: string|null,
   *             buildingName: string|null, verb: string }}
   */
  function resolveGuideTarget(g) {
    var routeLabel = getRouteLabel(g.nextRoute);
    var result = {
      route: g.nextRoute,
      routeLabel: routeLabel,
      buildingKey: null,
      buildingName: null,
      verb: '前往'
    };
    if (!g) return result;
    // 优先用后端返回的 targetBuilding (与 checkKey 分离, 例如招募军官步骤目标是军校)
    var key = g.targetBuilding || g.checkKey;
    if (!key) return result;
    var bld = (G.DATA && G.DATA.buildings) ? G.DATA.buildings[key] : null;
    if (bld && bld.name) {
      result.buildingKey = key;
      result.buildingName = bld.name;
      // 根据 checkType 决定动词
      if (g.checkType === 'BUILD_LEVEL' || g.checkType === 'BUILD_LEVEL_SUM') {
        result.verb = '升级';
      } else if (g.checkType === 'BUILD_COUNT') {
        result.verb = '建造';
      } else {
        // OFFICER_RECRUIT / OFFICER_APPOINT / ARMY_RECRUIT
        result.verb = '前往';
      }
    }
    return result;
  }

  /**
   * 跳转到目标区域后, 把具体建筑滚到视口里 + 加上高亮 class.
   * 玩家直接看到自己该点的那个建筑, 不用在一堆卡片里找.
   */
  function scrollToGuideTarget(target) {
    if (!target || !target.buildingKey) return;
    // 渲染是异步的 (Core.render 用了 setTimeout / requestAnimationFrame),
    // 多等几帧再找 DOM 节点
    var tries = 0;
    function attempt() {
      tries++;
      var el = document.querySelector('[data-building="' + target.buildingKey + '"]');
      if (el && el.scrollIntoView) {
        // 移除之前的高亮 (避免叠加)
        var prev = document.querySelectorAll('.guide-target');
        for (var i = 0; i < prev.length; i++) {
          prev[i].classList.remove('guide-target');
        }
        el.classList.add('guide-target');
        try {
          el.scrollIntoView({ behavior: 'smooth', block: 'center' });
        } catch (e) {
          el.scrollIntoView();
        }
        // 4.2s 后摘掉高亮 (动画 3 周期, 留点缓冲)
        setTimeout(function () {
          if (el && el.classList) el.classList.remove('guide-target');
        }, 4200);
        return;
      }
      if (tries < 8) setTimeout(attempt, 60);
    }
    setTimeout(attempt, 80);
  }

  function shortNum(n) {
    if (n >= 10000) return (n / 10000).toFixed(1) + '万';
    return String(n);
  }

  // ====================================================================
  //  轮询机制 - 在有引导进行中时定期刷新 (建造/征兵是异步完成的)
  // ====================================================================
  function startPolling() {
    stopPolling();
    state.pollTimer = setInterval(function () {
      // 静默刷新: 不显示遮罩气泡，只在数据有变化时重渲染
      G.API.getGuide().then(function (g) {
        // 避免无意义重渲染: 进度/状态变化时才更新
        var old = state.guide;
        var changed = !old || old.id !== g.id ||
          (old.progress && g.progress && (
            old.progress.current !== g.progress.current ||
            old.progress.complete !== g.progress.complete
          ));
        state.guide = g;
        if (g.done) {
          removeGuideDom();
          return;
        }
        if (changed) {
          renderGuideBubble();
        }
      }).catch(function () { /* 静默失败 */ });
    }, 4000);
  }

  function stopPolling() {
    if (state.pollTimer) {
      clearInterval(state.pollTimer);
      state.pollTimer = null;
    }
  }

  function maybeStartPolling() {
    if (state.guide && !state.guide.done) {
      startPolling();
    } else {
      stopPolling();
    }
  }

  // ====================================================================
  //  主线任务面板
  // ====================================================================
  function renderQuestView(v) {
    if (state.chapters.length) {
      drawQuestView(v);
    } else {
      v.innerHTML = '<div class="title">主线任务</div><div class="desc">加载中...</div>';
    }
    loadQuests().then(function () {
      if (Core.route === 'mainQuest') drawQuestView(v);
      if (G.Main && G.Main.renderNavBar) G.Main.renderNavBar();
    });
  }

  function drawQuestView(v) {
    var h = '<div class="main-quest-view">';
    h += '<div class="title">任务</div>';
    h += '<div class="desc main-quest-summary">完成各章任务获得资源、道具、军官等丰厚奖励，助您快速成长。</div>';

    // 活动与任务（原首页"活动与任务"块迁到这里）
    h += (G.Task && G.Task.renderActivities) ? G.Task.renderActivities() : '';

    h += '<div class="zone-head"><span class="zone-title">📜 主线任务</span><span class="zone-sub">章节挑战</span></div>';
    for (var ci = 0; ci < state.chapters.length; ci++) {
      var ch = state.chapters[ci];
      var total = ch.quests.length;
      var claimed = 0, completed = 0;
      for (var qi = 0; qi < ch.quests.length; qi++) {
        if (ch.quests[qi].status === 'claimed') claimed++;
        if (ch.quests[qi].status === 'completed' || ch.quests[qi].status === 'claimed') completed++;
      }
      var pct = total ? Math.round(claimed / total * 100) : 0;
      h += '<div class="panel">';
      h += '<div class="quest-chapter-head">';
      h += '<div class="quest-chapter-name">' + escapeHtml(ch.name) + '</div>';
      h += '<div class="quest-chapter-progress">' + claimed + '/' + total + ' (' + pct + '%)</div>';
      h += '</div>';
      h += '<div class="quest-chapter-intro">' + escapeHtml(ch.intro) + '</div>';
      h += '<div class="quest-progress-bar"><div class="quest-progress-fill" style="width:' + pct + '%"></div></div>';

      for (var qj = 0; qj < ch.quests.length; qj++) {
        h += renderQuestItem(ch.quests[qj]);
      }
      h += '</div>';
    }
    h += '</div>';
    v.innerHTML = h;
    bindClaimHandlers();
  }

  function renderQuestItem(q) {
    var status = q.status || 'in_progress';
    var progress = q.progress || 0;
    var target = q.target || 1;
    var pct = Math.min(100, Math.round(progress / target * 100));

    var badge = '';
    var canClaim = false;
    if (status === 'claimed') {
      badge = '<span class="quest-badge ok">✓ 已领取</span>';
    } else if (status === 'completed') {
      badge = '<span class="quest-badge warn">可领取</span>';
      canClaim = true;
    } else if (status === 'locked') {
      badge = '<span class="quest-badge">未解锁</span>';
    } else {
      badge = '<span class="quest-badge">进行中</span>';
    }

    var rewardText = formatReward(q.reward);

    return '<div class="quest-row" data-quest-id="' + q.id + '">' +
      '<div class="quest-row-top">' +
        '<div class="quest-title">' + escapeHtml(q.title) + badge + '</div>' +
        '<div class="quest-reward">' + rewardText + '</div>' +
      '</div>' +
      '<div class="quest-desc">' + escapeHtml(q.desc) + '</div>' +
      '<div class="quest-progress-row">' +
        '<div class="quest-progress-bar small"><div class="quest-progress-fill" style="width:' + pct + '%"></div></div>' +
        '<div class="quest-progress-text">' + progress + '/' + target + '</div>' +
      '</div>' +
      (canClaim ?
        '<button class="btn sm ok quest-claim-btn" data-id="' + q.id + '">领取奖励</button>' :
        '') +
    '</div>';
  }

  function formatReward(r) {
    if (!r) return '';
    var parts = [];
    if (r.food) parts.push('粮' + shortNum(r.food));
    if (r.steel) parts.push('钢' + shortNum(r.steel));
    if (r.oil) parts.push('油' + shortNum(r.oil));
    if (r.rare) parts.push('稀' + shortNum(r.rare));
    if (r.gold) parts.push('金' + shortNum(r.gold));
    if (r.skillBook) parts.push('技能书×' + r.skillBook);
    if (r.expBook) parts.push('经验书×' + r.expBook);
    if (r.itemKey && r.itemCount) parts.push(itemLabel(r.itemKey) + '×' + r.itemCount);
    if (parts.length === 0) return '无';
    return '奖励: ' + parts.join(' ');
  }

  function bindClaimHandlers() {
    var btns = document.querySelectorAll('.quest-claim-btn');
    for (var i = 0; i < btns.length; i++) {
      btns[i].onclick = function () {
        var id = this.getAttribute('data-id');
        var self = this;
        self.disabled = true;
        claimQuest(id).then(function (r) {
          G.toast((r && r.message) || '已领取');
          return loadQuests();
        }).then(function () {
          Core.render();
        }).catch(function (err) {
          G.toast(err && err.message ? err.message : '领取失败');
          self.disabled = false;
        });
      };
    }
  }

  // ====================================================================
  //  注册主路由
  // ====================================================================
  function registerRoutes() {
    if (G.Core && G.Core.views && !G.Core.views.mainQuest) {
      G.Core.views.mainQuest = renderQuestView;
    }
  }

  // ====================================================================
  //  初始化钩子 (在 main.js 启动后调用)
  // ====================================================================
  function init() {
    registerRoutes();
    return Promise.all([loadQuests().catch(function () { return []; }), loadGuide().catch(function () { return null; })])
      .then(function (results) {
        renderGuideBubble();
        if (G.Main && G.Main.renderNavBar) G.Main.renderNavBar();
        return { quests: results[0], guide: results[1] };
      });
  }

  /**
   * 公开: 玩家操作后立即刷新引导状态 (避免等轮询)。
   * 用法: G.MainQuest && G.MainQuest.refresh();
   */
  function refresh() {
    return loadGuide().then(function () {
      renderGuideBubble();
      return state.guide;
    });
  }

  function hasUnclaimed() {
    for (var i = 0; i < state.chapters.length; i++) {
      var qs = state.chapters[i].quests;
      for (var j = 0; j < qs.length; j++) {
        if (qs[j].status === 'completed') return true;
      }
    }
    return false;
  }

  function hasGuide() {
    return state.guide && !state.guide.done;
  }

  function escapeHtml(s) {
    if (s == null) return '';
    return String(s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  G.MainQuest = {
    init: init,
    refresh: refresh,
    loadQuests: loadQuests,
    loadGuide: loadGuide,
    renderGuideBubble: renderGuideBubble,
    renderQuestView: renderQuestView,
    advanceGuide: advanceGuide,
    skipGuide: skipGuide,
    hasUnclaimed: hasUnclaimed,
    hasGuide: hasGuide,
    state: state
  };
})(window.Game);
