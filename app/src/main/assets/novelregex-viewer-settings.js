(() => {
  const TAB_ID = "novelregex-viewer-settings-tab";
  const PANEL_ID = "novelregex-viewer-settings-panel";
  const STYLE_ID = "novelregex-viewer-settings-style";
  const SITE = {
    themeBox: "theme_box",
    normalTab: "btn_panel_1",
    advancedTab: "btn_panel_2",
    normalPanel: "option_panel_1",
    advancedPanel: "option_panel_2",
  };

  let current = null;
  let injectTimer = null;

  function siteNodes() {
    const themeBox = document.getElementById(SITE.themeBox);
    const normalTab = document.getElementById(SITE.normalTab);
    const advancedTab = document.getElementById(SITE.advancedTab);
    const normalPanel = document.getElementById(SITE.normalPanel);
    const advancedPanel = document.getElementById(SITE.advancedPanel);
    if (!themeBox || !normalTab || !advancedTab || !normalPanel || !advancedPanel) return null;
    const tabRow = normalTab.parentElement;
    if (!tabRow || advancedTab.parentElement !== tabRow) return null;
    return { themeBox, normalTab, advancedTab, normalPanel, advancedPanel, tabRow };
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
        height: 52px;
        min-height: 52px;
        box-sizing: border-box;
        margin: 0;
        padding: 0 4px;
        display: flex;
        align-items: center;
        gap: 12px;
        border: 0;
        border-bottom: 1px solid rgba(127,127,127,.16);
        background: transparent;
        color: inherit;
        text-align: left;
        font: inherit;
        line-height: 1.25;
      }
      #${PANEL_ID} button.nr-row {
        cursor: pointer;
        appearance: none;
        -webkit-appearance: none;
      }
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
        font-family: inherit;
        font-style: inherit;
        font-weight: inherit;
        font-size: var(--nr-select-font-size, 14px) !important;
        line-height: 1.3;
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
      #${PANEL_ID} .nr-more-settings-separator {
        height: 10px;
        box-sizing: border-box;
        border-top: 1px solid rgba(127,127,127,.16);
      }
      #${TAB_ID} { text-align: center; font-size: 13px; width: 33.3333%; }
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
      return !!(
        window._NovelRegExSettings &&
        window._NovelRegExSettings.setQuickSetting(key, String(value))
      );
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

  function createNativeChoiceRow(label, value, key) {
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
        if (bridge && typeof bridge.openQuickChoiceDialog === "function") {
          bridge.openQuickChoiceDialog(key);
        }
      } catch (_) {}
    });

    return row;
  }

  function optionLabel(options, value, fallback = "") {
    const found = options.find(([optionValue]) => String(optionValue) === String(value));
    return found ? String(found[1]) : fallback;
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
      const raw =
        window._NovelRegExSettings &&
        window._NovelRegExSettings.getQuickSettings();
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

    const selectFontSize = Number(data.selectFontSizeCssPx);
    if (Number.isFinite(selectFontSize) && selectFontSize >= 8 && selectFontSize <= 40) {
      panel.style.setProperty("--nr-select-font-size", `${selectFontSize.toFixed(2)}px`);
    } else {
      panel.style.setProperty("--nr-select-font-size", "14px");
    }

    panel.appendChild(createSectionTitle("TTS"));
    const ttsEngineOptions = Array.isArray(data.ttsEngines)
      ? data.ttsEngines.map((item) => [String(item.value || ""), String(item.label || item.value || "TTS 엔진")])
      : [["", "시스템 기본 엔진"]];
    panel.appendChild(
      createNativeChoiceRow(
        "TTS 엔진",
        optionLabel(ttsEngineOptions, data.ttsEnginePackage || "", "시스템 기본 엔진"),
        "tts_engine_package",
      ),
    );
    const chunkOptions = [["comma", "쉼표"], ["sentence", "마침표"], ["paragraph", "문단"]];
    panel.appendChild(
      createNativeChoiceRow(
        "청크 단위",
        optionLabel(chunkOptions, data.chunkMode, "문단"),
        "tts_chunk_mode",
      ),
    );
    const preQueueOptions = [["0", "OFF"], ["2", "2 chunks"], ["3", "3 chunks"], ["4", "4 chunks"], ["5", "5 chunks"]];
    panel.appendChild(
      createNativeChoiceRow(
        "Rolling Pre-Queue",
        optionLabel(preQueueOptions, String(data.rollingPreQueueDepth), "3 chunks"),
        "tts_rolling_prequeue_depth",
      ),
    );
    panel.appendChild(
      createSwitchRow(
        "한글 숫자 읽기",
        "tts_korean_number_enabled",
        data.koreanNumberEnabled,
      ),
    );
    panel.appendChild(
      createNavigationRow(
        "TTS 정규식",
        `${data.ttsRegexCount || 0}개`,
        "openTtsRegexSettings",
      ),
    );

    panel.appendChild(createSectionTitle("일반 설정"));
    const startPageOptions = [
      ["https://novelpia.com", "홈"],
      ["https://novelpia.com/mybook/last_view", "마지막으로 본 작품"],
      ["https://novelpia.com/mybook", "내서재"],
    ];
    panel.appendChild(
      createNativeChoiceRow(
        "시작 페이지",
        optionLabel(startPageOptions, data.startPage, "내서재"),
        "start_page",
      ),
    );

    const behaviorOptions = [["move_page", "페이지 이동"], ["disable", "기본 볼륨 조절"]];
    const directionOptions = [["up_prev", "↑ 이전 / ↓ 다음"], ["up_next", "↑ 다음 / ↓ 이전"]];

    const behaviorRow =
      createNativeChoiceRow(
        "볼륨 키 동작",
        optionLabel(behaviorOptions, data.volumeBehavior, "페이지 이동"),
        "volume_behavior",
      );
    panel.appendChild(behaviorRow);

    const directionRow =
      createNativeChoiceRow(
        "볼륨 키 방향",
        optionLabel(directionOptions, data.volumeDirection, "↑ 이전 / ↓ 다음"),
        "volume_direction",
      );
    const directionDisabled = data.volumeBehavior !== "move_page";
    directionRow.disabled = directionDisabled;
    directionRow.classList.toggle("nr-disabled", directionDisabled);
    panel.appendChild(directionRow);

    panel.appendChild(createSectionTitle("광고 차단"));
    panel.appendChild(
      createSwitchRow("광고 차단", "filters_enabled", data.filtersEnabled),
    );
    const ruleValue =
      Number(data.userRuleCount || 0) === Number(data.enabledUserRuleCount || 0)
        ? `${data.userRuleCount || 0}개`
        : `${data.enabledUserRuleCount || 0}/${data.userRuleCount || 0}개 사용`;
    panel.appendChild(
      createNavigationRow("사용자 규칙", ruleValue, "openUserRules"),
    );

    const moreSettingsSeparator = document.createElement("div");
    moreSettingsSeparator.className = "nr-more-settings-separator";
    panel.appendChild(moreSettingsSeparator);
    panel.appendChild(createNavigationRow("앱 설정 더보기", "", "openMoreSettings"));
  }

  function syncPanelHeight() {
    if (!current?.panel || !current.normalPanel) return;
    const inlineHeight =
      current.normalPanel.style.height || current.advancedPanel.style.height;
    if (inlineHeight) {
      current.panel.style.height = inlineHeight;
      return;
    }
    const rect = current.normalPanel.getBoundingClientRect();
    current.panel.style.height = rect.height > 0 ? `${rect.height}px` : "380px";
  }

  function refresh() {
    if (!current?.panel?.isConnected) return;
    syncPanelHeight();
    renderPanel(current.panel, readQuickSettings());
  }

  function hidePanel() {
    if (!current?.panel) return;
    current.panel.style.display = "none";
    current.novelTab.style.backgroundColor = "rgba(0,0,0,0.2)";
    current.novelTab.dataset.nrActive = "0";
  }

  function showPanel() {
    if (!current?.panel) return;
    current.normalPanel.style.display = "none";
    current.advancedPanel.style.display = "none";
    current.normalTab.style.backgroundColor = "rgba(0,0,0,0.2)";
    current.advancedTab.style.backgroundColor = "rgba(0,0,0,0.2)";
    current.novelTab.style.backgroundColor = "";
    current.novelTab.dataset.nrActive = "1";
    syncPanelHeight();
    current.panel.style.display = "block";
    refresh();
  }

  function inject() {
    const existing = document.getElementById(TAB_ID);
    if (existing?.isConnected && document.getElementById(PANEL_ID)?.isConnected) {
      return true;
    }

    const nodes = siteNodes();
    if (!nodes) return false;
    ensureStyle();

    document.getElementById(TAB_ID)?.remove();
    document.getElementById(PANEL_ID)?.remove();

    const novelTab = nodes.advancedTab.cloneNode(true);
    novelTab.id = TAB_ID;
    novelTab.textContent = "NovelRegEx";
    novelTab.removeAttribute("onclick");
    novelTab.querySelectorAll?.("[id]").forEach((el) => el.removeAttribute("id"));
    novelTab.querySelectorAll?.("[onclick]").forEach((el) => el.removeAttribute("onclick"));
    novelTab.querySelectorAll?.("a[href]").forEach((el) => el.removeAttribute("href"));

    nodes.normalTab.style.width = "33.3333%";
    nodes.advancedTab.style.width = "33.3333%";
    novelTab.style.width = "33.3333%";
    nodes.advancedTab.insertAdjacentElement("afterend", novelTab);

    const panel = document.createElement("div");
    panel.id = PANEL_ID;
    panel.style.display = "none";
    panel.style.overflowY = "auto";
    nodes.advancedPanel.insertAdjacentElement("afterend", panel);

    current = {
      ...nodes,
      novelTab,
      panel,
    };
    syncPanelHeight();

    novelTab.addEventListener(
      "click",
      (event) => {
        event.preventDefault();
        event.stopImmediatePropagation();
        showPanel();
      },
      true,
    );
    nodes.normalTab.addEventListener("click", hidePanel, true);
    nodes.advancedTab.addEventListener("click", hidePanel, true);
    return true;
  }

  function scheduleInject() {
    if (injectTimer) clearTimeout(injectTimer);
    injectTimer = setTimeout(() => {
      injectTimer = null;
      inject();
    }, 80);
  }

  const observer = new MutationObserver(scheduleInject);
  observer.observe(document.documentElement || document, {
    childList: true,
    subtree: true,
  });
  window.addEventListener("resize", () => {
    syncPanelHeight();
    scheduleInject();
  });

  scheduleInject();
  window.__novelregexViewerSettings = {
    refresh,
    show: showPanel,
    hide: hidePanel,
    reinject: scheduleInject,
  };
})();
