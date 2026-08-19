(() => {
  const TAB_ID = "novelregex-viewer-settings-tab";
  const PANEL_ID = "novelregex-viewer-settings-panel";
  const STYLE_ID = "novelregex-viewer-settings-style";
  const NORMAL_TAB_TEXT = "일반설정";
  const ADVANCED_TAB_TEXT = "고급설정";
  const debounceState = { timer: null };
  let current = null;

  function compactText(value) {
    return String(value || "").replace(/\s+/g, "").trim();
  }

  function isVisible(element) {
    if (!element || !element.isConnected) return false;
    const rect = element.getBoundingClientRect();
    if (rect.width < 8 || rect.height < 8) return false;
    const style = getComputedStyle(element);
    return style.display !== "none" && style.visibility !== "hidden" && Number(style.opacity || "1") > 0;
  }

  function elementScore(element) {
    const tag = element.tagName;
    let score = 100;
    if (tag === "BUTTON" || tag === "A") score -= 35;
    if (tag === "LI") score -= 20;
    if (element.getAttribute("role") === "tab") score -= 40;
    if (typeof element.onclick === "function") score -= 15;
    score += Math.min(30, element.children.length * 3);
    const rect = element.getBoundingClientRect();
    score += Math.min(30, (rect.width * rect.height) / 12000);
    return score;
  }

  function findTextElement(compactLabel) {
    const candidates = Array.from(
      document.querySelectorAll('button,a,[role="tab"],li,div,span,p')
    ).filter((element) => isVisible(element) && compactText(element.textContent) === compactLabel);
    candidates.sort((a, b) => elementScore(a) - elementScore(b));
    return candidates[0] || null;
  }

  function ancestors(element, limit = 8) {
    const out = [];
    let cursor = element;
    while (cursor && cursor !== document.body && out.length < limit) {
      out.push(cursor);
      cursor = cursor.parentElement;
    }
    return out;
  }

  function commonAncestor(a, b) {
    const bSet = new Set(ancestors(b, 10));
    return ancestors(a, 10).find((node) => bSet.has(node)) || null;
  }

  function directChildUnder(ancestor, node) {
    let cursor = node;
    while (cursor && cursor.parentElement && cursor.parentElement !== ancestor) {
      cursor = cursor.parentElement;
    }
    return cursor && cursor.parentElement === ancestor ? cursor : null;
  }

  function findTabStructure() {
    const normalText = findTextElement(NORMAL_TAB_TEXT);
    const advancedText = findTextElement(ADVANCED_TAB_TEXT);
    if (!normalText || !advancedText) return null;

    const common = commonAncestor(normalText, advancedText);
    if (!common) return null;

    let tabBar = common;
    let normalTab = directChildUnder(tabBar, normalText) || normalText;
    let advancedTab = directChildUnder(tabBar, advancedText) || advancedText;

    // If the common ancestor itself is one of the tabs, use its parent.
    if (normalTab === advancedTab || normalTab === tabBar || advancedTab === tabBar) {
      tabBar = common.parentElement;
      if (!tabBar) return null;
      normalTab = directChildUnder(tabBar, normalText) || normalText;
      advancedTab = directChildUnder(tabBar, advancedText) || advancedText;
    }
    if (!normalTab || !advancedTab || normalTab === advancedTab) return null;

    const tabRect = tabBar.getBoundingClientRect();
    if (tabRect.width < 180 || tabRect.height > 180) return null;
    return { normalText, advancedText, tabBar, normalTab, advancedTab };
  }

  function findSettingsRoot(tabBar) {
    let cursor = tabBar.parentElement;
    let best = null;
    for (let depth = 0; cursor && cursor !== document.body && depth < 7; depth += 1) {
      const rect = cursor.getBoundingClientRect();
      const text = compactText(cursor.textContent);
      if (
        rect.width >= 260 &&
        rect.height >= 260 &&
        text.includes(NORMAL_TAB_TEXT) &&
        text.includes(ADVANCED_TAB_TEXT)
      ) {
        best = cursor;
        if (text.includes("폰트") || text.includes("일러스트") || text.includes("뷰어방식")) {
          return cursor;
        }
      }
      cursor = cursor.parentElement;
    }
    return best;
  }

  function removeDuplicateIds(root) {
    if (!root) return;
    if (root.removeAttribute) root.removeAttribute("id");
    root.querySelectorAll?.("[id]").forEach((element) => element.removeAttribute("id"));
  }

  function replaceAdvancedLabel(clone) {
    const nodes = [clone, ...clone.querySelectorAll("*")];
    const target = nodes
      .filter((element) => compactText(element.textContent) === ADVANCED_TAB_TEXT)
      .sort((a, b) => a.children.length - b.children.length)[0];
    if (target) target.textContent = "NovelRegEx";
    else clone.textContent = "NovelRegEx";
  }

  function backgroundColorFor(element) {
    let cursor = element;
    while (cursor && cursor !== document.documentElement) {
      const color = getComputedStyle(cursor).backgroundColor;
      if (color && color !== "rgba(0, 0, 0, 0)" && color !== "transparent") return color;
      cursor = cursor.parentElement;
    }
    return "#fff";
  }

  function ensureStyle() {
    if (document.getElementById(STYLE_ID)) return;
    const style = document.createElement("style");
    style.id = STYLE_ID;
    style.textContent = `
      #${PANEL_ID} {
        box-sizing: border-box;
        padding: 12px 14px 18px;
        overflow-y: auto;
        overscroll-behavior: contain;
        color: inherit;
        font: inherit;
      }
      #${PANEL_ID} * { box-sizing: border-box; }
      #${PANEL_ID} .nr-section-title {
        padding: 11px 4px 6px;
        font-size: 12px;
        font-weight: 700;
        opacity: .58;
      }
      #${PANEL_ID} .nr-row {
        width: 100%;
        min-height: 46px;
        padding: 8px 4px;
        display: flex;
        align-items: center;
        gap: 12px;
        border: 0;
        border-bottom: 1px solid rgba(127,127,127,.16);
        background: transparent;
        color: inherit;
        text-align: left;
        font: inherit;
      }
      #${PANEL_ID} button.nr-row { cursor: pointer; }
      #${PANEL_ID} .nr-label { flex: 1 1 auto; min-width: 0; }
      #${PANEL_ID} .nr-value {
        flex: 0 0 auto;
        max-width: 58%;
        opacity: .76;
        text-align: right;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
      }
      #${PANEL_ID} .nr-chevron { opacity: .42; padding-left: 2px; }
      #${PANEL_ID} select {
        max-width: 58%;
        min-width: 92px;
        border: 0;
        outline: 0;
        background: transparent;
        color: inherit;
        font: inherit;
        text-align: right;
      }
      #${PANEL_ID} .nr-switch {
        position: relative;
        width: 42px;
        height: 24px;
        flex: 0 0 42px;
      }
      #${PANEL_ID} .nr-switch input { opacity: 0; width: 0; height: 0; }
      #${PANEL_ID} .nr-slider {
        position: absolute;
        inset: 0;
        border-radius: 999px;
        background: rgba(127,127,127,.32);
        transition: .15s;
      }
      #${PANEL_ID} .nr-slider:before {
        content: "";
        position: absolute;
        width: 18px;
        height: 18px;
        left: 3px;
        top: 3px;
        border-radius: 50%;
        background: #fff;
        box-shadow: 0 1px 3px rgba(0,0,0,.26);
        transition: .15s;
      }
      #${PANEL_ID} .nr-switch input:checked + .nr-slider { background: #6b4eff; }
      #${PANEL_ID} .nr-switch input:checked + .nr-slider:before { transform: translateX(18px); }
      #${PANEL_ID} .nr-row.nr-disabled { opacity: .38; pointer-events: none; }
      #${TAB_ID}[data-nr-active="1"] {
        font-weight: 700 !important;
        box-shadow: inset 0 -2px currentColor;
      }
    `;
    (document.head || document.documentElement).appendChild(style);
  }

  function createSectionTitle(text) {
    const title = document.createElement("div");
    title.className = "nr-section-title";
    title.textContent = text;
    return title;
  }

  function setQuickSetting(key, value) {
    try {
      return !!(window._NovelRegExSettings && window._NovelRegExSettings.setQuickSetting(key, String(value)));
    } catch (_) {
      return false;
    }
  }

  function createSelectRow(label, key, value, options, onChanged) {
    const row = document.createElement("div");
    row.className = "nr-row";
    const labelNode = document.createElement("div");
    labelNode.className = "nr-label";
    labelNode.textContent = label;

    const select = document.createElement("select");
    options.forEach(([optionValue, optionLabel]) => {
      const option = document.createElement("option");
      option.value = optionValue;
      option.textContent = optionLabel;
      if (String(optionValue) === String(value)) option.selected = true;
      select.appendChild(option);
    });
    select.addEventListener("change", () => {
      if (!setQuickSetting(key, select.value)) {
        refresh();
        return;
      }
      if (onChanged) onChanged(select.value);
    });

    row.append(labelNode, select);
    return { row, select };
  }

  function createSwitchRow(label, key, checked) {
    const row = document.createElement("label");
    row.className = "nr-row";
    const labelNode = document.createElement("div");
    labelNode.className = "nr-label";
    labelNode.textContent = label;

    const switchLabel = document.createElement("span");
    switchLabel.className = "nr-switch";
    const input = document.createElement("input");
    input.type = "checkbox";
    input.checked = !!checked;
    const slider = document.createElement("span");
    slider.className = "nr-slider";
    switchLabel.append(input, slider);

    input.addEventListener("change", () => {
      if (!setQuickSetting(key, input.checked ? "true" : "false")) {
        input.checked = !input.checked;
      }
    });

    row.append(labelNode, switchLabel);
    return row;
  }

  function createNavigationRow(label, value, actionName) {
    const row = document.createElement("button");
    row.type = "button";
    row.className = "nr-row";
    const labelNode = document.createElement("div");
    labelNode.className = "nr-label";
    labelNode.textContent = label;
    const valueNode = document.createElement("div");
    valueNode.className = "nr-value";
    valueNode.textContent = value || "";
    const chevron = document.createElement("div");
    chevron.className = "nr-chevron";
    chevron.textContent = "›";
    row.append(labelNode, valueNode, chevron);
    row.addEventListener("click", () => {
      try {
        const bridge = window._NovelRegExSettings;
        if (bridge && typeof bridge[actionName] === "function") bridge[actionName]();
      } catch (_) {}
    });
    return row;
  }

  function readQuickSettings() {
    try {
      const raw = window._NovelRegExSettings && window._NovelRegExSettings.getQuickSettings();
      return raw ? JSON.parse(raw) : null;
    } catch (_) {
      return null;
    }
  }

  function renderPanel(panel, data) {
    panel.replaceChildren();
    if (!data) {
      const failed = document.createElement("div");
      failed.style.padding = "24px 4px";
      failed.style.opacity = ".65";
      failed.textContent = "NovelRegEx 설정을 불러오지 못했습니다.";
      panel.appendChild(failed);
      return;
    }

    panel.appendChild(createSectionTitle("TTS"));
    panel.appendChild(
      createSelectRow(
        "청크 단위",
        "tts_chunk_mode",
        data.chunkMode,
        [["comma", "쉼표"], ["sentence", "마침표"], ["paragraph", "문단"]],
      ).row,
    );
    panel.appendChild(
      createSelectRow(
        "Rolling Pre-Queue",
        "tts_rolling_prequeue_depth",
        String(data.rollingPreQueueDepth),
        [["0", "OFF"], ["2", "2 chunks"], ["3", "3 chunks"], ["4", "4 chunks"], ["5", "5 chunks"]],
      ).row,
    );
    panel.appendChild(createSwitchRow("한글 숫자 읽기", "tts_korean_number_enabled", data.koreanNumberEnabled));
    panel.appendChild(createNavigationRow("TTS 정규식", `${data.ttsRegexCount || 0}개`, "openTtsRegexSettings"));

    panel.appendChild(createSectionTitle("일반 설정"));
    panel.appendChild(
      createSelectRow(
        "시작 페이지",
        "start_page",
        data.startPage,
        [
          ["https://novelpia.com", "홈"],
          ["https://novelpia.com/mybook/last_view", "마지막으로 본 작품"],
          ["https://novelpia.com/mybook", "내서재"],
        ],
      ).row,
    );

    const direction = createSelectRow(
      "볼륨 키 방향",
      "volume_direction",
      data.volumeDirection,
      [["up_prev", "↑ 이전 / ↓ 다음"], ["up_next", "↑ 다음 / ↓ 이전"]],
    );
    const behavior = createSelectRow(
      "볼륨 키 동작",
      "volume_behavior",
      data.volumeBehavior,
      [["move_page", "페이지 이동"], ["disable", "기본 볼륨 조절"]],
      (value) => {
        const disabled = value !== "move_page";
        direction.select.disabled = disabled;
        direction.row.classList.toggle("nr-disabled", disabled);
      },
    );
    panel.appendChild(behavior.row);
    direction.select.disabled = data.volumeBehavior !== "move_page";
    direction.row.classList.toggle("nr-disabled", data.volumeBehavior !== "move_page");
    panel.appendChild(direction.row);

    panel.appendChild(createSectionTitle("광고 차단"));
    panel.appendChild(createSwitchRow("광고 차단", "filters_enabled", data.filtersEnabled));
    const ruleValue =
      Number(data.userRuleCount || 0) === Number(data.enabledUserRuleCount || 0)
        ? `${data.userRuleCount || 0}개`
        : `${data.enabledUserRuleCount || 0}/${data.userRuleCount || 0}개 사용`;
    panel.appendChild(createNavigationRow("사용자 규칙", ruleValue, "openUserRules"));

    panel.appendChild(createSectionTitle(""));
    panel.appendChild(createNavigationRow("설정 더보기", "", "openMoreSettings"));
  }

  function refresh() {
    if (!current || !current.panel || !current.panel.isConnected) return;
    renderPanel(current.panel, readQuickSettings());
  }

  function setNovelTabActive(active) {
    const tab = document.getElementById(TAB_ID);
    if (!tab) return;
    tab.dataset.nrActive = active ? "1" : "0";
  }

  function showPanel() {
    if (!current || !current.panel) return;
    current.panel.style.display = "block";
    setNovelTabActive(true);
    refresh();
  }

  function hidePanel() {
    if (!current || !current.panel) return;
    current.panel.style.display = "none";
    setNovelTabActive(false);
  }

  function inject() {
    const existing = document.getElementById(TAB_ID);
    if (existing && existing.isConnected) return true;

    const structure = findTabStructure();
    if (!structure) return false;
    const root = findSettingsRoot(structure.tabBar);
    if (!root) return false;

    ensureStyle();

    const novelTab = structure.advancedTab.cloneNode(true);
    removeDuplicateIds(novelTab);
    novelTab.id = TAB_ID;
    replaceAdvancedLabel(novelTab);
    if (novelTab.tagName === "A") novelTab.removeAttribute("href");
    novelTab.removeAttribute?.("onclick");
    novelTab.querySelectorAll?.("a[href]").forEach((element) => element.removeAttribute("href"));
    novelTab.querySelectorAll?.("[onclick]").forEach((element) => element.removeAttribute("onclick"));

    const tabParent = structure.advancedTab.parentElement;
    if (!tabParent || tabParent !== structure.normalTab.parentElement) return false;
    structure.advancedTab.insertAdjacentElement("afterend", novelTab);

    // Reuse Novelpia's own tab dimensions, but split only this tab group evenly.
    const tabChildren = [structure.normalTab, structure.advancedTab, novelTab];
    const parentDisplay = getComputedStyle(tabParent).display;
    if (tabParent.children.length === 3 || parentDisplay.includes("flex")) {
      tabParent.style.setProperty("display", "flex", "important");
      tabChildren.forEach((tab) => {
        tab.style.setProperty("flex", "1 1 0", "important");
        tab.style.setProperty("width", "0", "important");
        tab.style.setProperty("min-width", "0", "important");
        tab.style.setProperty("max-width", "none", "important");
      });
    } else {
      // Preserve unknown decorative children (for example a site-owned tab indicator).
      tabChildren.forEach((tab) => {
        tab.style.setProperty("width", "33.3333%", "important");
        tab.style.setProperty("min-width", "0", "important");
        tab.style.setProperty("max-width", "33.3333%", "important");
      });
    }

    const rootStyle = getComputedStyle(root);
    if (rootStyle.position === "static") root.style.position = "relative";

    const rootRect = root.getBoundingClientRect();
    const tabRect = tabParent.getBoundingClientRect();
    const panel = document.createElement("div");
    panel.id = PANEL_ID;
    panel.style.position = "absolute";
    panel.style.left = "0";
    panel.style.right = "0";
    panel.style.top = `${Math.max(0, tabRect.bottom - rootRect.top)}px`;
    panel.style.bottom = "0";
    panel.style.zIndex = "2147483000";
    panel.style.background = backgroundColorFor(root);
    panel.style.display = "none";
    root.appendChild(panel);

    current = {
      root,
      panel,
      normalTab: structure.normalTab,
      advancedTab: structure.advancedTab,
      novelTab,
    };

    novelTab.addEventListener(
      "click",
      (event) => {
        event.preventDefault();
        event.stopImmediatePropagation();
        showPanel();
      },
      true,
    );
    [structure.normalTab, structure.advancedTab].forEach((tab) => {
      tab.addEventListener("click", hidePanel, true);
    });
    return true;
  }

  function scheduleInject() {
    if (debounceState.timer) clearTimeout(debounceState.timer);
    debounceState.timer = setTimeout(() => {
      debounceState.timer = null;
      inject();
    }, 120);
  }

  const observer = new MutationObserver(scheduleInject);
  observer.observe(document.documentElement || document, { childList: true, subtree: true });
  scheduleInject();

  window.__novelregexViewerSettings = {
    refresh,
    show: showPanel,
    hide: hidePanel,
    reinject: scheduleInject,
  };
})();
