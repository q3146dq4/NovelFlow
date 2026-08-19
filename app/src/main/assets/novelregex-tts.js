/*
 * Native TTS bridge for NovelRegEx
 * Rotation/layout recovery version
 */

(() => {
  "use strict";

  if (window.__npTts) {
    return;
  }

  const state = {
    lines: [],
    sentences: [],
    highlightName: "np-viewer-tts",
    fallbackStyleInstalled: false,

    // 마지막으로 TTS가 가리킨 문장/줄
    lastSentenceIndex: -1,
    lastLineIndex: -1,
    lastChunk: null,

    listenHookInstalled: false,
    observer: null,

    // 화면 회전/레이아웃 변경 debounce
    resizeTimer: null,

    // 현재 화면 재수집 중인지
    refreshingLayout: false,

    // Cached next chapter target collected with the chapter snapshot.
    nextChapterUrl: "",

    // DOM collection state. MutationObserver marks the snapshot dirty and
    // performs one debounced rescan instead of Android polling collect() repeatedly.
    dirty: true,
    collectTimer: null,
  };

  function cleanText(text) {
    return String(text || "")
      .replace(/\u00a0/g, " ")
      .replace(/\u200b/g, "")
      .replace(/[\r\n\t]+/g, " ")
      .replace(/\s{2,}/g, " ")
      .trim();
  }

  function isElementHidden(element) {
    if (!element) return true;

    return !!element.closest(
      [
        "script",
        "style",
        "noscript",
        "template",
        "[hidden]",
        "[aria-hidden='true']",
      ].join(",")
    );
  }

  function visibleTextNodes(root) {
    const result = [];

    if (!root) return result;

    const walker = document.createTreeWalker(
      root,
      NodeFilter.SHOW_TEXT,
      {
        acceptNode(node) {
          const parent = node.parentElement;

          if (!parent) {
            return NodeFilter.FILTER_REJECT;
          }

          if (isElementHidden(parent)) {
            return NodeFilter.FILTER_REJECT;
          }

          const value = node.nodeValue || "";

          if (!value.trim()) {
            return NodeFilter.FILTER_REJECT;
          }

          return NodeFilter.FILTER_ACCEPT;
        },
      }
    );

    let node;

    while ((node = walker.nextNode())) {
      result.push(node);
    }

    return result;
  }

  function boundaryAt(nodes, offset) {
    if (!nodes || !nodes.length) return null;

    let cursor = 0;

    for (const node of nodes) {
      const value = node.nodeValue || "";
      const length = value.length;
      const next = cursor + length;

      if (offset <= next) {
        return {
          node,
          offset: Math.max(
            0,
            Math.min(offset - cursor, length)
          ),
        };
      }

      cursor = next;
    }

    const last = nodes[nodes.length - 1];

    return {
      node: last,
      offset: (last.nodeValue || "").length,
    };
  }

  function splitSentences(text) {
    const result = [];

    if (!text) return result;

    const regex =
      /([.!?…]+["'”’」』)\]}]*)(\s+)(?=[\p{L}\p{N}"“'‘「『])/gu;

    let lastIndex = 0;
    let match;

    while ((match = regex.exec(text)) !== null) {
      const boundaryEnd =
        match.index + match[1].length;

      const sentenceText =
        text.substring(lastIndex, boundaryEnd);

      const trimmed = sentenceText.trim();

      if (trimmed) {
        const start = text.indexOf(
          trimmed,
          lastIndex
        );

        result.push({
          text: trimmed,
          start,
          end: start + trimmed.length,
        });
      }

      lastIndex =
        boundaryEnd + match[2].length;
    }

    if (lastIndex < text.length) {
      const remaining = text.substring(lastIndex);
      const trimmed = remaining.trim();

      if (trimmed) {
        const start = text.indexOf(
          trimmed,
          lastIndex
        );

        result.push({
          text: trimmed,
          start,
          end: start + trimmed.length,
        });
      }
    }

    if (result.length === 0 && text.trim()) {
      const t = text.trim();

      const start = text.indexOf(t);

      result.push({
        text: t,
        start,
        end: start + t.length,
      });
    }

    return result;
  }

  function splitAtCommas(text) {
    const result = [];

    if (!text) return result;

    let start = 0;

    for (let index = 0; index < text.length; index++) {
      const character = text[index];
      const isComma =
        character === "," ||
        character === "，" ||
        character === "、";
      const isNumberSeparator =
        character === "," &&
        index > 0 &&
        index + 1 < text.length &&
        /[0-9]/.test(text[index - 1]) &&
        /[0-9]/.test(text[index + 1]);

      if (!isComma || isNumberSeparator) continue;

      let partStart = start;
      let partEnd = index + 1;

      while (
        partStart < partEnd &&
        /\s/.test(text[partStart])
      ) {
        partStart++;
      }

      while (
        partEnd > partStart &&
        /\s/.test(text[partEnd - 1])
      ) {
        partEnd--;
      }

      if (partStart < partEnd) {
        result.push({
          text: text.substring(partStart, partEnd),
          start: partStart,
          end: partEnd,
        });
      }

      start = index + 1;
    }

    let partStart = start;
    let partEnd = text.length;

    while (
      partStart < partEnd &&
      /\s/.test(text[partStart])
    ) {
      partStart++;
    }

    while (
      partEnd > partStart &&
      /\s/.test(text[partEnd - 1])
    ) {
      partEnd--;
    }

    if (partStart < partEnd) {
      result.push({
        text: text.substring(partStart, partEnd),
        start: partStart,
        end: partEnd,
      });
    }

    return result;
  }


  function getLineElements() {
    return Array.from(
      document.querySelectorAll(
        "#novel_drawing font.line"
      )
    );
  }

  function collect() {
    state.lines = [];
    state.sentences = [];
    state.nextChapterUrl = "";

    const lineElements = getLineElements();

    lineElements.forEach((element, lineIndex) => {
      const nodes = visibleTextNodes(element);

      const rawText = nodes
        .map((node) => node.nodeValue || "")
        .join("");

      const cleaned = cleanText(rawText);

      const lineInfo = {
        index: lineIndex,
        element,
        nodes,
        text: cleaned,
      };

      state.lines.push(lineInfo);

      if (
        element.querySelector("img") &&
        !cleaned
      ) {
        state.sentences.push({
          line: lineIndex,
          text: "삽화가 있습니다.",
          start: null,
          end: null,
          sourceText: "",
          rawStart: null,
          rawEnd: null,
        });

        return;
      }

      if (!cleaned) return;

      const parts = splitSentences(rawText);

      for (const part of parts) {
        const text = cleanText(part.text);

        if (!text) continue;

        const startBoundary = boundaryAt(
          nodes,
          part.start
        );

        const endBoundary = boundaryAt(
          nodes,
          part.end
        );

        state.sentences.push({
          line: lineIndex,
          text,
          start: startBoundary,
          end: endBoundary,
          sourceText: part.text,
          rawStart: part.start,
          rawEnd: part.end,
        });
      }
    });

    state.nextChapterUrl = findNextChapterUrl();
    state.dirty = false;
    return snapshot();
  }

  function findNextChapterUrl() {
    try {
      const selectors = [
        "#novel_drawing_right",
        "#next_epi_btn_bottom",
        ".menu-next-item",
        ".btn-next-episode",
      ];

      for (const selector of selectors) {
        const element = document.querySelector(selector);
        const anchor = element?.closest?.("a[href]");
        const href = anchor?.href || "";
        if (href && !href.startsWith("javascript:")) return href;
      }

      const elements = document.querySelectorAll("a,button,div,span,p,li");
      for (const element of elements) {
        const raw = element.innerText || element.textContent || "";
        const value = raw.replace(/\s/g, "");
        if (value !== "다음화보기" && value !== "다음화") continue;
        const anchor = element.closest?.("a[href]");
        const href = anchor?.href || "";
        if (href && !href.startsWith("javascript:")) return href;
      }
    } catch (_) {}
    return "";
  }

  function snapshot() {
    if (state.dirty) {
      return collect();
    }

    const episodeTag =
      document
        .querySelector(".menu-top-tag")
        ?.innerText
        ?.trim() || "";

    const title =
      document
        .querySelector(".menu-top-title")
        ?.innerText
        ?.trim() ||
      document.title ||
      "";

    const numberMatch = episodeTag.match(/\d+/);
    const episodeNumber = numberMatch ? numberMatch[0] : "0";

    return JSON.stringify({
      episode: `EP.${episodeNumber}`,
      title,
      lineCount: state.lines.length,
      sentenceCount: state.sentences.length,
      nextChapterUrl: state.nextChapterUrl || "",
      sentences: state.sentences.map((item) => ({
        line: item.line,
        text: item.text,
        commaParts: splitAtCommas(item.sourceText || item.text).map((part) => cleanText(part.text)),
      })),
    });
  }

  function supportsCssHighlight() {
    return !!(
      typeof CSS !== "undefined" &&
      CSS.highlights &&
      typeof CSS.highlights.set === "function" &&
      typeof Highlight !== "undefined"
    );
  }

  function installHighlightStyle() {
    if (!supportsCssHighlight()) return;

    if (
      document.__npTtsHighlightStyleInstalled
    ) {
      return;
    }

    try {
      const style =
        document.createElement("style");

      style.id =
        "npviewer-tts-highlight-style";

      style.textContent = `
        ::highlight(np-viewer-tts) {
          background: rgba(255, 213, 79, 0.90);
          color: inherit;
        }

        .npviewer-tts-line-fallback {
          background: rgba(255, 213, 79, 0.65) !important;
          color: inherit !important;
        }
      `;

      (
        document.head ||
        document.documentElement
      ).appendChild(style);

      document.__npTtsHighlightStyleInstalled =
        true;
    } catch (_) {}
  }

  function clearCssHighlight() {
    try {
      if (
        typeof CSS !== "undefined" &&
        CSS.highlights
      ) {
        CSS.highlights.delete(
          state.highlightName
        );
      }
    } catch (_) {}
  }

  function clearFallbackHighlight() {
    try {
      document
        .querySelectorAll(
          ".npviewer-tts-line-fallback"
        )
        .forEach((element) => {
          element.classList.remove(
            "npviewer-tts-line-fallback"
          );
        });
    } catch (_) {}
  }

  function clearHighlight() {
    clearCssHighlight();
    clearFallbackHighlight();

    state.lastLineIndex = -1;
  }

  function highlight(index) {
    const item =
      state.sentences[index];

    if (!item) return;

    /*
     * 현재 TTS 문장 번호를 저장한다.
     *
     * 화면이 회전하여 WebView DOM이 새로 만들어져도
     * 이 번호를 기준으로 새 DOM에서 다시 highlight한다.
     */
    state.lastSentenceIndex = index;
    state.lastChunk = null;

    installHighlightStyle();

    clearHighlight();

    if (
      !item.start?.node ||
      !item.end?.node
    ) {
      const line =
        state.lines[item.line]?.element;

      if (line) {
        line.classList.add(
          "npviewer-tts-line-fallback"
        );

        scrollToLine(
          item.line,
          true
        );
      }

      state.lastLineIndex =
        item.line;

      return;
    }

    if (supportsCssHighlight()) {
      try {
        const range =
          document.createRange();

        range.setStart(
          item.start.node,
          item.start.offset
        );

        range.setEnd(
          item.end.node,
          item.end.offset
        );

        const highlight =
          new Highlight(range);

        CSS.highlights.set(
          state.highlightName,
          highlight
        );
      } catch (_) {
        const line =
          state.lines[item.line]?.element;

        if (line) {
          line.classList.add(
            "npviewer-tts-line-fallback"
          );
        }
      }
    } else {
      const line =
        state.lines[item.line]?.element;

      if (line) {
        line.classList.add(
          "npviewer-tts-line-fallback"
        );
      }
    }

    scrollToLine(
      item.line,
      false
    );
  }

  function addFallbackHighlight(
    startLine,
    endLine
  ) {
    for (
      let lineIndex = startLine;
      lineIndex <= endLine;
      lineIndex++
    ) {
      state.lines[lineIndex]?.element?.classList.add(
        "npviewer-tts-line-fallback"
      );
    }
  }

  function highlightChunk(
    startSentenceIndex,
    endSentenceIndexExclusive,
    commaPartIndex = -1
  ) {
    const safeStart = Number(startSentenceIndex);
    const safeEnd = Number(endSentenceIndexExclusive);
    const safeCommaPart = Number(commaPartIndex);

    if (
      !Number.isInteger(safeStart) ||
      !Number.isInteger(safeEnd) ||
      safeStart < 0 ||
      safeStart >= state.sentences.length
    ) {
      return false;
    }

    const endExclusive = Math.max(
      safeStart + 1,
      Math.min(safeEnd, state.sentences.length)
    );
    const first = state.sentences[safeStart];
    const last = state.sentences[endExclusive - 1];

    if (!first || !last) return false;

    let startBoundary = first.start;
    let endBoundary = last.end;

    if (
      Number.isInteger(safeCommaPart) &&
      safeCommaPart >= 0 &&
      endExclusive === safeStart + 1 &&
      first.rawStart != null
    ) {
      const commaParts = splitAtCommas(
        first.sourceText || first.text
      );
      const commaPart = commaParts[safeCommaPart];
      const nodes = state.lines[first.line]?.nodes;

      if (commaPart && nodes) {
        startBoundary = boundaryAt(
          nodes,
          first.rawStart + commaPart.start
        );
        endBoundary = boundaryAt(
          nodes,
          first.rawStart + commaPart.end
        );
      }
    }

    state.lastSentenceIndex = safeStart;
    state.lastChunk = {
      start: safeStart,
      end: endExclusive,
      commaPart: safeCommaPart,
    };

    installHighlightStyle();
    clearHighlight();

    if (
      !startBoundary?.node ||
      !endBoundary?.node
    ) {
      addFallbackHighlight(
        first.line,
        last.line
      );
      scrollToLine(first.line, true);
      return true;
    }

    if (supportsCssHighlight()) {
      try {
        const range = document.createRange();
        range.setStart(
          startBoundary.node,
          startBoundary.offset
        );
        range.setEnd(
          endBoundary.node,
          endBoundary.offset
        );
        CSS.highlights.set(
          state.highlightName,
          new Highlight(range)
        );
      } catch (_) {
        addFallbackHighlight(
          first.line,
          last.line
        );
      }
    } else {
      addFallbackHighlight(
        first.line,
        last.line
      );
    }

    scrollToLine(first.line, false);
    return true;
  }

  // ---------------------------------------------------------
  // 스크롤
  // ---------------------------------------------------------

  function scrollToLine(
    lineIndex,
    immediate
  ) {
    const line =
      state.lines[lineIndex]?.element ||
      getLineElements()[lineIndex];

    if (!line) return;

    try {
      line.scrollIntoView({
        behavior: immediate
          ? "auto"
          : "smooth",
        block: "center",
        inline: "nearest",
      });
    } catch (e) {
      try {
        line.scrollIntoView(true);
      } catch (_) {}
    }

    setTimeout(
      () => {
        try {
          if (!line.isConnected) {
            return;
          }

          const rect =
            line.getBoundingClientRect();

          const viewHeight =
            window.innerHeight;

          if (
            rect.top <
              viewHeight * 0.2 ||
            rect.top >
              viewHeight * 0.8
          ) {
            const offset =
              rect.top -
              viewHeight / 2 +
              rect.height / 2;

            try {
              window.scrollBy({
                top: offset,
                behavior: "auto",
              });
            } catch (e) {
              try {
                window.scrollBy(
                  0,
                  offset
                );
              } catch (_) {}
            }

            let parent =
              line.parentElement;

            while (
              parent &&
              parent !== document.body &&
              parent !==
                document.documentElement
            ) {
              try {
                const style =
                  window.getComputedStyle(
                    parent
                  );

                if (
                  style.overflowY ===
                    "auto" ||
                  style.overflowY ===
                    "scroll"
                ) {
                  parent.scrollTop +=
                    offset;
                }
              } catch (_) {}

              parent =
                parent.parentElement;
            }
          }
        } catch (_) {}
      },
      immediate ? 50 : 300
    );

    state.lastLineIndex =
      lineIndex;
  }

  // ---------------------------------------------------------
  // 회전 / 화면 크기 변경 대응
  // ---------------------------------------------------------

  function refreshAfterLayoutChange() {
    if (state.refreshingLayout) {
      return;
    }

    const lineElements =
      getLineElements();

    if (!lineElements.length) {
      return;
    }

    state.refreshingLayout = true;

    /*
     * 회전하기 직전의 TTS 위치를 보존한다.
     */
    const savedSentenceIndex =
      state.lastSentenceIndex;

    const savedChunk = state.lastChunk
      ? { ...state.lastChunk }
      : null;

    const savedLineIndex =
      state.lastLineIndex;

    try {
      /*
       * 기존 CSS Highlight는 회전 전 DOM의
       * TextNode/Range를 참조하고 있을 수 있으므로
       * 먼저 제거한다.
       */
      clearHighlight();

      /*
       * 회전 후 새 WebView DOM을 다시 수집한다.
       */
      collect();

      /*
       * 기존에 읽고 있던 문장을 새 DOM에서
       * 다시 highlight한다.
       */
      if (
        savedChunk &&
        savedChunk.start >= 0 &&
        savedChunk.start <
          state.sentences.length
      ) {
        highlightChunk(
          savedChunk.start,
          savedChunk.end,
          savedChunk.commaPart
        );
      } else if (
        savedSentenceIndex >= 0 &&
        savedSentenceIndex <
          state.sentences.length
      ) {
        highlight(
          savedSentenceIndex
        );
      } else if (
        savedLineIndex >= 0 &&
        savedLineIndex <
          state.lines.length
      ) {
        scrollToLine(
          savedLineIndex,
          true
        );
      }
    } catch (_) {
      /*
       * 회전 도중 DOM이 아직 완성되지 않은 경우
       * 다음 resize 이벤트에서 다시 시도한다.
       */
    } finally {
      state.refreshingLayout = false;
    }
  }

  function scheduleLayoutRefresh() {
    if (state.resizeTimer) {
      clearTimeout(
        state.resizeTimer
      );
    }

    state.resizeTimer =
      setTimeout(() => {
        state.resizeTimer = null;

        refreshAfterLayoutChange();
      }, 350);
  }

  // ---------------------------------------------------------
  // "듣기" 버튼
  // ---------------------------------------------------------

  function isListenElement(element) {
    if (!element) return false;

    let current = element;

    for (
      let i = 0;
      i < 6 && current;
      i++,
      current = current.parentElement
    ) {
      const text = cleanText(
        current.innerText ||
          current.textContent ||
          ""
      ).replace(/\s+/g, "");

      if (
        text === "듣기" ||
        text === "듣기▶" ||
        text === "듣기►" ||
        text === "듣기⏵"
      ) {
        return true;
      }
    }

    return false;
  }

  function installListenHook() {
    if (state.listenHookInstalled) {
      return;
    }

    state.listenHookInstalled =
      true;

    document.addEventListener(
      "click",
      (event) => {
        const target =
          event.target;

        if (
          !(target instanceof Element)
        ) {
          return;
        }

        if (
          !isListenElement(target)
        ) {
          return;
        }

        if (
          !window._NPTTS ||
          typeof window._NPTTS.open !==
            "function"
        ) {
          return;
        }

        event.preventDefault();
        event.stopImmediatePropagation();

        window._NPTTS.open();
      },
      true
    );
  }

  // ---------------------------------------------------------
  // 특정 문장으로 이동
  // ---------------------------------------------------------

  function seekToSentence(
    index,
    immediate = true
  ) {
    const safeIndex =
      Number(index);

    if (
      !Number.isInteger(
        safeIndex
      )
    ) {
      return false;
    }

    const item =
      state.sentences[safeIndex];

    if (!item) {
      return false;
    }

    state.lastSentenceIndex =
      safeIndex;

    highlight(
      safeIndex
    );

    if (
      immediate &&
      item.line >= 0
    ) {
      scrollToLine(
        item.line,
        true
      );
    }

    return true;
  }

  // ---------------------------------------------------------
  // 공개 API
  // ---------------------------------------------------------

  window.__npTts = {
    /*
     * 현재 페이지의 문장/줄을 다시 수집한다.
     */
    collect,

    /*
     * 이미 수집된 상태를 DOM 재스캔 없이 직렬화한다.
     */
    snapshot,

    /*
     * Android preload poll용 가벼운 준비 상태 확인.
     */
    isReady() {
      return state.sentences.length > 0 && !state.dirty;
    },

    /*
     * collect()에서 캐시한 다음 화 URL. 없을 때만 다시 탐색한다.
     */
    nextChapterUrl() {
      if (!state.nextChapterUrl) {
        state.nextChapterUrl = findNextChapterUrl();
      }
      return state.nextChapterUrl || "";
    },

    /*
     * 특정 문장을 highlight한다.
     */
    highlight,

    /*
     * 실제로 하나의 TTS 요청으로 전달된
     * 쉼표/문장/문단 청크 전체를 highlight한다.
     */
    highlightChunk,

    /*
     * 재생하지 않고 특정 문장 위치만 선택한다.
     *
     * Android 쪽에서 일시정지 상태의 seek 처리 등에
     * 사용할 수 있다.
     */
    seekToSentence,

    /*
     * 현재 highlight를 제거한다.
     */
    clearHighlight,

    /*
     * 특정 줄로 스크롤한다.
     */
    scrollToLine,

    /*
     * 회전 후 DOM을 다시 수집하고
     * 현재 문장의 highlight/스크롤을 복구한다.
     */
    refreshAfterLayoutChange,

    /*
     * 현재 줄 개수.
     */
    lineCount() {
      if (state.lines.length) {
        return state.lines.length;
      }

      return getLineElements().length;
    },

    /*
     * 현재 문장 개수.
     */
    sentenceCount() {
      return state.sentences.length;
    },

    /*
     * 문장 수집이 완료되었는지.
     */
    isCollected() {
      return state.lines.length > 0;
    },

    /*
     * 현재 highlight 중인 문장 index.
     */
    currentSentenceIndex() {
      return state.lastSentenceIndex;
    },

    /*
     * 현재 highlight 중인 줄 index.
     */
    currentLineIndex() {
      return state.lastLineIndex;
    },
  };

  // ---------------------------------------------------------
  // 초기화
  // ---------------------------------------------------------

  installListenHook();

  // ---------------------------------------------------------
  // 화면 회전 / 크기 변경 감지
  // ---------------------------------------------------------

  try {
    /*
     * 화면 크기가 변경되는 경우
     * 회전뿐 아니라 split-screen 등의 상황도 대응한다.
     */
    window.addEventListener(
      "resize",
      scheduleLayoutRefresh,
      false
    );

    /*
     * orientationchange를 별도로 감지한다.
     */
    window.addEventListener(
      "orientationchange",
      scheduleLayoutRefresh,
      false
    );

    /*
     * WebView가 다시 표시되는 경우.
     */
    window.addEventListener(
      "pageshow",
      () => {
        scheduleLayoutRefresh();
      },
      false
    );

    /*
     * Android WebView에서 화면이 다시 활성화된 경우.
     */
    document.addEventListener(
      "visibilitychange",
      () => {
        if (
          document.visibilityState ===
          "visible"
        ) {
          scheduleLayoutRefresh();
        }
      },
      false
    );
  } catch (_) {}

  // ---------------------------------------------------------
  // DOM 변경 감시
  // ---------------------------------------------------------

  try {
    const observerRoot =
      document.querySelector(
        "#novel_drawing"
      ) || document.body;

    if (observerRoot) {
      state.observer =
        new MutationObserver(
          () => {
            if (
              !document.querySelector(
                "#novel_drawing font.line"
              )
            ) {
              return;
            }

            state.dirty = true;

            if (state.collectTimer) {
              clearTimeout(state.collectTimer);
            }

            state.collectTimer = setTimeout(() => {
              state.collectTimer = null;
              try {
                collect();
              } catch (_) {}
            }, 120);
          }
        );

      state.observer.observe(
        observerRoot,
        {
          childList: true,
          characterData: true,
          subtree: true,
        }
      );
    }
  } catch (_) {}

  // ---------------------------------------------------------
  // 최초 수집
  // ---------------------------------------------------------

  try {
    if (
      document.querySelector(
        "#novel_drawing font.line"
      )
    ) {
      collect();
    }
  } catch (_) {}
})();
