const apiBase = "";
const terminalRunEvents = new Set(["run.completed", "run.failed", "run.cancelled", "run.waiting_user"]);
const authHeadersStorageKey = "finex:test:authHeadersText";
const authProfileStorageKey = "finex:test:authProfileId";
const ambiguousAutoSelectRetryDelayMs = 1000;
const ambiguousAutoSelectMaxAttempts = 5;

const state = {
  sessions: [],
  selectedSessionIds: new Set(),
  documents: [],
  selectedSessionId: new URLSearchParams(location.search).get("sessionId") || localStorage.getItem("finex:test:lastSessionId"),
  selectedDocuments: new Map(),
  ws: null,
  commandSeq: 1,
  activeRunId: null,
  activeTopicId: null,
  activeRunStatus: null,
  sessionSeq: new Map(),
  subscribedTopics: new Set(),
  topicSessionIds: new Map(),
  assistantNodeByRun: new Map(),
  renderedEventKeys: new Set(),
  pendingDeltaByRun: new Map(),
  deltaFlushScheduled: false,
  restoringRunIds: new Set(),
  ambiguousAutoSelectTasks: new Map(),
  authProfileId: localStorage.getItem(authProfileStorageKey) || newAuthProfileId(),
  authHeadersText: localStorage.getItem(authHeadersStorageKey) || "",
  authProfileSynced: false,
  proxyProfileCookieName: "finex_proxy_profile",
  proxyProfileHeaderName: "x-finex-proxy-profile"
};

const bc = "BroadcastChannel" in window ? new BroadcastChannel("financeex-local-test") : null;

if (bc) {
  bc.onmessage = event => {
    if (event.data?.type === "event") {
      const payload = event.data.payload;
      if (payload?.runId && state.restoringRunIds.has(payload.runId)) {
        return;
      }
      handleChatEvent(payload, "broadcast", { broadcast: false });
    }
  };
}

const $ = id => document.getElementById(id);

window.addEventListener("DOMContentLoaded", async () => {
  bindUi();
  loadAuthHeadersFromStorage();
  updateRunControls();
  await runSafely(loadConfig);
  await runSafely(syncAuthProfile);
  await runSafely(refreshSessions);
  await runSafely(refreshDocuments);
  if (state.selectedSessionId) {
    await runSafely(() => selectSession(state.selectedSessionId));
  }
  connectWs();
});

window.addEventListener("focus", () => {
  if (state.selectedSessionId) {
    runSafely(() => refreshCurrentStreamStatus({ restoreStream: true }));
  }
});

document.addEventListener("visibilitychange", () => {
  sendWs({ type: "presence", state: document.hidden ? "background" : "foreground" });
  if (!document.hidden && state.selectedSessionId) {
    runSafely(() => refreshCurrentStreamStatus({ restoreStream: true }));
  }
});

function bindUi() {
  bindClick("connectWsBtn", connectWs);
  bindClick("disconnectWsBtn", disconnectWs);
  bindClick("openCloneBtn", openCloneTab);
  bindClick("saveAuthHeadersBtn", saveAuthHeaders);
  bindClick("clearAuthHeadersBtn", clearAuthHeaders);
  bindClick("refreshSessionsBtn", refreshSessions);
  bindClick("batchDeleteSessionsBtn", deleteSelectedSessions);
  bindClick("createSessionBtn", createSession);
  bindClick("loadSessionBtn", () => requireSession(sessionId => loadSessionMessagesAndStatus(sessionId, true)));
  bindClick("loadMessagesBtn", () => requireSession(loadMessagesOnly));
  bindClick("loadTreeBtn", () => requireSession(loadMessageTree));
  bindClick("renameSessionBtn", renameSession);
  bindClick("archiveSessionBtn", () => mutateSession("archive"));
  bindClick("restoreSessionBtn", () => mutateSession("restore"));
  bindClick("deleteSessionBtn", deleteSession);
  bindClick("stopRunBtn", stopRun);
  bindClick("resumeEventsBtn", () => requireSession(sessionId => resumeSessionEvents(sessionId, lastSeq(sessionId))));
  bindClick("restoreActiveRunBtn", restoreActiveRun);
  bindClick("refreshDocsBtn", refreshDocuments);
  $("chatForm").addEventListener("submit", event => {
    event.preventDefault();
    runSafely(() => sendRun());
  });
  $("uploadForm").addEventListener("submit", event => {
    event.preventDefault();
    runSafely(() => uploadDocument());
  });
}

function bindClick(id, handler) {
  $(id).addEventListener("click", event => runSafely(() => handler(event)));
}

async function loadConfig() {
  const config = await requestJson("/__config");
  $("backendLabel").textContent = `backend: ${config.backendUrl}`;
  state.proxyProfileCookieName = config.proxyProfileCookieName || state.proxyProfileCookieName;
  state.proxyProfileHeaderName = config.proxyProfileHeaderName || state.proxyProfileHeaderName;
  updateAuthHeaderStatus();
}

function loadAuthHeadersFromStorage() {
  localStorage.setItem(authProfileStorageKey, state.authProfileId);
  document.cookie = `${state.proxyProfileCookieName}=${encodeURIComponent(state.authProfileId)}; Path=/; SameSite=Lax`;
  $("authHeadersInput").value = state.authHeadersText;
  updateAuthHeaderStatus();
}

async function saveAuthHeaders() {
  state.authHeadersText = $("authHeadersInput").value;
  localStorage.setItem(authHeadersStorageKey, state.authHeadersText);
  await syncAuthProfile({ force: true });
  if (state.ws && state.ws.readyState === WebSocket.OPEN) {
    disconnectWs();
    connectWs();
  }
  log(`auth headers saved profile=${state.authProfileId}`);
}

async function clearAuthHeaders() {
  state.authHeadersText = "";
  $("authHeadersInput").value = "";
  localStorage.removeItem(authHeadersStorageKey);
  await fetch(`/__auth-config/${encodeURIComponent(state.authProfileId)}`, { method: "DELETE" });
  state.authProfileSynced = false;
  updateAuthHeaderStatus();
  if (state.ws && state.ws.readyState === WebSocket.OPEN) {
    disconnectWs();
    connectWs();
  }
  log("auth headers cleared");
}

async function syncAuthProfile({ force = false } = {}) {
  document.cookie = `${state.proxyProfileCookieName}=${encodeURIComponent(state.authProfileId)}; Path=/; SameSite=Lax`;
  if (!force && state.authProfileSynced) {
    return;
  }
  const headers = parseAuthHeaders(state.authHeadersText);
  const response = await fetch("/__auth-config", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ profileId: state.authProfileId, headers })
  });
  const body = parseJsonBody(await response.text());
  if (!response.ok) {
    throw new Error(body?.message || "保存鉴权请求头失败");
  }
  state.authProfileSynced = true;
  updateAuthHeaderStatus(body.headerCount ?? headers.length);
}

function parseAuthHeaders(text) {
  const headers = [];
  for (const rawLine of String(text || "").split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith("#")) {
      continue;
    }
    const splitAt = line.indexOf(":");
    if (splitAt <= 0) {
      throw new Error(`请求头格式错误，应为 Name: value：${line}`);
    }
    headers.push({
      enabled: true,
      name: line.slice(0, splitAt).trim(),
      value: line.slice(splitAt + 1).trim()
    });
  }
  return headers;
}

function updateAuthHeaderStatus(count = null) {
  const status = $("authHeadersState");
  if (!status) return;
  const parsedCount = count ?? safeParseAuthHeaderCount();
  status.textContent = parsedCount > 0 ? `headers: ${parsedCount}` : "headers: none";
  status.className = parsedCount > 0 ? "pill" : "pill muted";
}

function safeParseAuthHeaderCount() {
  try {
    return parseAuthHeaders(state.authHeadersText).length;
  } catch {
    return 0;
  }
}

async function createSession() {
  const title = $("newSessionTitle").value.trim() || "本地联调会话";
  const session = await requestJson("/v1/chat/sessions", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ title, channel: "web-local-test" })
  });
  $("newSessionTitle").value = "";
  await refreshSessions();
  await selectSession(session.sessionId);
}

async function refreshSessions() {
  const page = await requestJson("/v1/chat/sessions?limit=30");
  state.sessions = page.items || [];
  const visibleIds = new Set(state.sessions.map(session => session.sessionId));
  for (const sessionId of [...state.selectedSessionIds]) {
    if (!visibleIds.has(sessionId)) {
      state.selectedSessionIds.delete(sessionId);
    }
  }
  renderSessions();
}

function renderSessions() {
  const list = $("sessionsList");
  list.replaceChildren();
  for (const session of state.sessions) {
    const item = document.createElement("div");
    item.className = `list-item session-item${session.sessionId === state.selectedSessionId ? " active" : ""}`;
    item.innerHTML = `
      <div class="session-row">
        <input data-action="select-delete" type="checkbox"
          ${state.selectedSessionIds.has(session.sessionId) ? "checked" : ""}
          aria-label="选择 ${escapeHtml(session.title || session.sessionId)}" />
        <button data-action="open" class="session-open" type="button">
          <div class="item-title">${escapeHtml(session.title || "未命名会话")}</div>
          <div class="item-meta">${escapeHtml(session.status)} · ${formatTime(session.updatedAt)}</div>
          <div class="item-meta">${escapeHtml(session.sessionId)}</div>
          ${session.firstAssistantAnswer ? `<div class="item-preview">答：${escapeHtml(session.firstAssistantAnswer)}</div>` : ""}
        </button>
      </div>
    `;
    item.querySelector('[data-action="open"]').addEventListener("click", () => runSafely(() => selectSession(session.sessionId)));
    item.querySelector('[data-action="select-delete"]').addEventListener("change", event => {
      if (event.target.checked) {
        state.selectedSessionIds.add(session.sessionId);
      } else {
        state.selectedSessionIds.delete(session.sessionId);
      }
    });
    list.appendChild(item);
  }
}

async function selectSession(sessionId) {
  clearAmbiguousAutoSelectTasks();
  state.selectedSessionId = sessionId;
  localStorage.setItem("finex:test:lastSessionId", sessionId);
  history.replaceState(null, "", `?sessionId=${encodeURIComponent(sessionId)}`);
  renderSessions();
  await loadSessionMessagesAndStatus(sessionId, true);
  await refreshDocuments();
}

async function loadSessionMessagesAndStatus(sessionId, restoreStream) {
  const encodedSessionId = encodeURIComponent(sessionId);
  const [sessionDto, messagesPage, streamStatus] = await Promise.all([
    requestJson(`/v1/chat/sessions/${encodedSessionId}`),
    requestJson(`/v1/chat/sessions/${encodedSessionId}/messages?limit=50`),
    requestJson(`/v1/chat/sessions/${encodedSessionId}/stream-status`)
  ]);
  const hasActiveRun = Boolean(restoreStream && streamStatus?.activeRunId && streamStatus?.activeStreamTopicId);
  $("currentSessionId").textContent = sessionId;
  $("renameTitle").value = sessionDto?.title || "";
  renderHistory(messagesPage?.items || []);
  setStreamStatus(streamStatus);
  if (hasActiveRun) {
    startActiveRunSseRestore(streamStatus, "stream-status");
  } else {
    replayStoredEvents(sessionId);
  }
}

async function refreshCurrentStreamStatus({ restoreStream = false } = {}) {
  if (!state.selectedSessionId) return;
  const status = await requestJson(`/v1/chat/sessions/${encodeURIComponent(state.selectedSessionId)}/stream-status`);
  setStreamStatus(status);
  if (restoreStream && status.activeRunId && status.activeStreamTopicId
      && !state.subscribedTopics.has(status.activeStreamTopicId)
      && !state.restoringRunIds.has(status.activeRunId)) {
    startActiveRunSseRestore(status, "stream-status");
  }
}

function startActiveRunSseRestore(status, reason) {
  if (!status?.activeRunId || !status?.activeStreamTopicId) return;
  if (state.restoringRunIds.has(status.activeRunId)) return;
  state.restoringRunIds.add(status.activeRunId);
  const resumeAfterSeq = activeRunCatchupSeq(status);
  resumeRunEvents(status.activeRunId, resumeAfterSeq)
    .then(result => {
      log(`restore ${reason} run=${status.activeRunId} resumeEvents=${result.eventCount} lastSeq=${result.lastSeq} terminal=${result.terminal}`);
      if (!result.terminal) {
        log(`restore ${reason} run=${status.activeRunId} ended before terminal; use event resume again if the run is still active`);
      }
    })
    .catch(error => log(`run event resume failed: ${error.message}`))
    .finally(() => state.restoringRunIds.delete(status.activeRunId));
}

async function loadMessagesOnly(sessionId) {
  const page = await requestJson(`/v1/chat/sessions/${encodeURIComponent(sessionId)}/messages?limit=50`);
  renderHistory(page.items || []);
  replayStoredEvents(sessionId);
  log(`messages loaded session=${sessionId} count=${(page.items || []).length}`);
}

async function loadMessageTree(sessionId) {
  const tree = await requestJson(`/v1/chat/sessions/${encodeURIComponent(sessionId)}/messages/tree`);
  const mapping = tree.mapping || {};
  const summary = {
    sessionId: tree.sessionId,
    currentLeafMessageId: tree.currentLeafMessageId,
    rootMessageIds: tree.rootMessageIds || [],
    nodeCount: Object.keys(mapping).length,
    nodes: Object.fromEntries(Object.entries(mapping).map(([id, node]) => [
      id,
      {
        role: node.message?.role,
        parentMessageId: node.parentMessageId,
        children: node.children || [],
        siblingIndex: node.message?.siblingIndex,
        partCount: node.message?.parts?.length || 0
      }
    ]))
  };
  $("messageTreeView").textContent = JSON.stringify(summary, null, 2);
  log(`tree loaded session=${sessionId} nodes=${summary.nodeCount}`);
}

function renderHistory(messages) {
  $("messages").replaceChildren();
  state.assistantNodeByRun.clear();
  state.renderedEventKeys.clear();
  state.pendingDeltaByRun.clear();
  state.deltaFlushScheduled = false;
  for (const message of messages) {
    appendMessage(message.role, message.content || "", {
      messageId: message.messageId,
      createdAt: message.createdAt,
      parentMessageId: message.parentMessageId,
      nodeOrder: message.nodeOrder,
      treeDepth: message.treeDepth,
      siblingIndex: message.siblingIndex,
      runId: message.runId,
      originType: message.originType,
      locked: message.locked,
      editedFromMessageId: message.editedFromMessageId,
      regeneratedFromMessageId: message.regeneratedFromMessageId,
      parts: message.parts || [],
      versionInfo: message.versionInfo || null
    }, message);
  }
  scrollMessages();
}

async function renameSession() {
  const sessionId = requireSessionId();
  await requestJson(`/v1/chat/sessions/${encodeURIComponent(sessionId)}`, {
    method: "PATCH",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ title: $("renameTitle").value.trim() })
  });
  await refreshSessions();
}

async function mutateSession(action) {
  const sessionId = requireSessionId();
  await requestJson(`/v1/chat/sessions/${encodeURIComponent(sessionId)}/${action}`, { method: "POST" });
  await refreshSessions();
}

async function deleteSession() {
  const sessionId = requireSessionId();
  await requestJson(`/v1/chat/sessions/${encodeURIComponent(sessionId)}`, { method: "DELETE" });
  state.selectedSessionIds.delete(sessionId);
  state.selectedSessionId = null;
  state.activeRun = null;
  state.pendingDeltaByRun.clear();
  $("currentSessionId").textContent = "-";
  $("currentSeq").textContent = "0";
  $("activeRun").textContent = "-";
  $("messages").replaceChildren();
  await refreshSessions();
}

async function deleteSelectedSessions() {
  const sessionIds = [...state.selectedSessionIds];
  if (sessionIds.length === 0) {
    throw new Error("请先勾选要批量删除的会话");
  }
  const result = await requestJson("/v1/chat/sessions", {
    method: "DELETE",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ sessionIds })
  });
  if (state.selectedSessionId && sessionIds.includes(state.selectedSessionId)) {
    state.selectedSessionId = null;
    state.activeRun = null;
    state.pendingDeltaByRun.clear();
    $("currentSessionId").textContent = "-";
    $("currentSeq").textContent = "0";
    $("activeRun").textContent = "-";
    $("messages").replaceChildren();
  }
  state.selectedSessionIds.clear();
  log(`batch deleted sessions count=${result.deletedCount ?? sessionIds.length}`);
  await refreshSessions();
}

async function sendRun() {
  if (isRunInProgress()) {
    alert("当前回答仍在生成中，请先停止或等待完成后再发送新消息");
    return;
  }
  const message = $("messageInput").value.trim();
  if (!message) return;

  const attachments = [...state.selectedDocuments.values()].map(document => ({ documentId: document.id }));
  const targetDomainAgentId = $("targetDomainAgentIdInput")?.value?.trim();
  const metadataExtra = parseOptionalJsonObject($("runMetadataInput")?.value?.trim(), "run metadata");
  if (metadataExtra === undefined) {
    return;
  }
  const body = {
    commandId: `cmd_${Date.now()}`,
    sessionId: state.selectedSessionId || null,
    conversationId: state.selectedSessionId || null,
    message,
    attachments,
    metadata: {
      clientMessageId: `client_${Date.now()}`,
      source: "local-test-frontend",
      ...(metadataExtra || {})
    }
  };
  if (targetDomainAgentId) {
    body.targetType = "DOMAIN_AGENT";
    body.targetId = targetDomainAgentId;
  }

  $("messageInput").value = "";
  await startRunRequest(body, { optimisticUserMessage: message });
}

async function startRunRequest(body, { optimisticUserMessage = null } = {}) {
  // 本地联调台复用同一个 run 创建流程承载普通提问、编辑历史问题和重新生成回答。
  // 后端仍以 /chat/runs 为唯一提问入口，runMode 决定消息树写入方式。
  if (optimisticUserMessage) {
    appendMessage("user", optimisticUserMessage);
  }
  state.activeRunId = null;
  state.activeTopicId = null;
  state.activeRunStatus = "STARTING";
  setActiveRunLabel();
  let run;
  try {
    run = await requestJson("/v1/chat/runs", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body)
    });
  } catch (error) {
    state.activeRunId = null;
    state.activeTopicId = null;
    state.activeRunStatus = null;
    setActiveRunLabel();
    throw error;
  }

  state.activeRunId = run.runId;
  state.activeTopicId = run.streamTopicId;
  state.activeRunStatus = "RUNNING";
  setActiveRunLabel();
  if (!state.selectedSessionId) {
    state.selectedSessionId = run.sessionId;
    localStorage.setItem("finex:test:lastSessionId", run.sessionId);
    history.replaceState(null, "", `?sessionId=${encodeURIComponent(run.sessionId)}`);
    await refreshSessions();
  }

  log(`run created ${run.runId} topic=${run.streamTopicId}`);
  setSessionSeq(run.sessionId, Math.max(lastSeq(run.sessionId), Number(run.firstSeq || 0)));
  await ensureWs();
  subscribeTopic(run.streamTopicId, run.firstSeq || 0, run.sessionId);
  return run;
}

async function stopRun() {
  if (!state.activeRunId) return alert("没有 active run");
  if (!isRunInProgress()) return alert("当前 run 已经不在运行中");
  const resumeAfterSeq = state.selectedSessionId ? lastSeq(state.selectedSessionId) : 0;
  const previousStatus = state.activeRunStatus;
  state.activeRunStatus = "CANCELLING";
  setActiveRunLabel();
  let result;
  try {
    result = await requestJson(`/v1/chat/runs/${encodeURIComponent(state.activeRunId)}/stop`, { method: "POST" });
  } catch (error) {
    state.activeRunStatus = previousStatus;
    setActiveRunLabel();
    throw error;
  }
  state.activeRunStatus = result.status;
  setActiveRunLabel();
  if (result.sessionId) {
    await resumeSessionEvents(result.sessionId, resumeAfterSeq);
  }
  log(`stop ${result.runId} -> ${result.status}`);
}

async function restoreActiveRun() {
  const sessionId = requireSessionId();
  const status = await requestJson(`/v1/chat/sessions/${encodeURIComponent(sessionId)}/stream-status`);
  setStreamStatus(status);
  if (status.activeRunId && status.activeStreamTopicId) {
    startActiveRunSseRestore(status, "manual");
  } else {
    log("当前会话没有 active run topic");
  }
}

function connectWs() {
  if (state.ws && [WebSocket.OPEN, WebSocket.CONNECTING].includes(state.ws.readyState)) return;
  updateWsState("connecting");
  syncAuthProfile()
    .then(openWebSocket)
    .catch(error => {
      updateWsState("error");
      log(`ws auth profile sync failed: ${error.message}`);
    });
}

function openWebSocket() {
  if (state.ws && [WebSocket.OPEN, WebSocket.CONNECTING].includes(state.ws.readyState)) return;
  state.ws = new WebSocket(currentWsUrl());
  updateWsState("connecting");
  state.ws.addEventListener("open", () => {
    updateWsState("connected");
    sendWs({ type: "connect", presence: document.hidden ? "background" : "foreground" });
  });
  state.ws.addEventListener("message", event => {
    try {
      handleWsEnvelope(JSON.parse(event.data));
    } catch (error) {
      log(`ws parse error: ${error.message}`);
    }
  });
  state.ws.addEventListener("close", () => {
    state.subscribedTopics.clear();
    state.topicSessionIds.clear();
    updateWsState("disconnected");
  });
  state.ws.addEventListener("error", () => updateWsState("error"));
}

function disconnectWs() {
  if (state.ws) {
    unsubscribeAllTopics("disconnect");
    state.ws.close();
  }
  state.ws = null;
  updateWsState("disconnected");
}

function unsubscribeAllTopics(reason) {
  for (const topicId of [...state.subscribedTopics]) {
    sendWs({ type: "unsubscribe", topicId });
    log(`ws unsubscribe topic=${topicId} reason=${reason}`);
  }
  state.subscribedTopics.clear();
  state.topicSessionIds.clear();
}

function ensureWs() {
  connectWs();
  if (state.ws?.readyState === WebSocket.OPEN) return Promise.resolve();
  return new Promise((resolve, reject) => {
    const startedAt = Date.now();
    const timer = setInterval(() => {
      if (state.ws?.readyState === WebSocket.OPEN) {
        clearInterval(timer);
        resolve();
      } else if (Date.now() - startedAt > 5000) {
        clearInterval(timer);
        reject(new Error("WebSocket 连接超时"));
      }
    }, 50);
  });
}

function subscribeTopic(topicId, afterSeq, sessionId = state.selectedSessionId) {
  if (!topicId) return;
  ensureWs().then(() => {
    state.subscribedTopics.add(topicId);
    if (sessionId) state.topicSessionIds.set(topicId, sessionId);
    sendWs({ type: "subscribe", topicId, afterSeq: Number(afterSeq || 0) });
    log(`ws subscribe topic=${topicId} afterSeq=${Number(afterSeq || 0)}`);
  }).catch(error => log(`ws subscribe failed: ${error.message}`));
}

function sendWs(payload) {
  if (!state.ws || state.ws.readyState !== WebSocket.OPEN) return;
  const message = { id: String(state.commandSeq++), ...payload };
  state.ws.send(JSON.stringify(message));
}

function handleWsEnvelope(envelope) {
  if (envelope.type === "reply") {
    log(`ws reply ${JSON.stringify(envelope.reply)}`);
    return;
  }
  if (envelope.type === "error") {
    log(`ws error ${envelope.code}: ${envelope.message}`);
    const topicSessionId = state.topicSessionIds.get(envelope.topicId) || state.selectedSessionId;
    if (envelope.code === "RECOVER_REQUIRED" && topicSessionId) {
      const runId = parseRunId(envelope.topicId);
      const resume = runId
        ? resumeRunEvents(runId, lastSeq(topicSessionId))
        : resumeSessionEvents(topicSessionId, lastSeq(topicSessionId));
      resume.catch(error => log(error.message));
    }
    return;
  }
  if (envelope.type === "message" && envelope.payload) {
    const turn = unwrapTurnStream(envelope.payload);
    if (turn.kind === "heartbeat") {
      log(`ws heartbeat topic=${envelope.topicId} lastSeq=${turn.lastSeq || 0}`);
      return;
    }
    if (turn.kind === "done") {
      log(`ws done topic=${envelope.topicId} lastSeq=${turn.lastSeq || 0} terminal=${turn.terminalEventType || "-"}`);
      sendWs({ type: "unsubscribe", topicId: envelope.topicId });
      state.subscribedTopics.delete(envelope.topicId);
      state.topicSessionIds.delete(envelope.topicId);
      return;
    }
    if (!turn.event) {
      log(`ws unknown message payload ${JSON.stringify(envelope.payload)}`);
      return;
    }
    const chatEvent = turn.event;
    if (envelope.topicId && chatEvent.sessionId) {
      state.topicSessionIds.set(envelope.topicId, chatEvent.sessionId);
    }
    handleChatEvent(chatEvent, "ws", { topicId: envelope.topicId });
    if (terminalRunEvents.has(chatEvent.type)) {
      sendWs({ type: "unsubscribe", topicId: envelope.topicId });
      state.subscribedTopics.delete(envelope.topicId);
      state.topicSessionIds.delete(envelope.topicId);
    }
  }
}

async function resumeSessionEvents(sessionId, afterSeq) {
  await syncAuthProfile();
  const response = await fetch(`${apiBase}/v1/chat/sessions/${encodeURIComponent(sessionId)}/events/resume?afterSeq=${Number(afterSeq || 0)}`,
    withProxyProfile());
  if (!response.ok) throw new Error(`Event resume failed: ${response.status}`);
  if (!response.body) throw new Error("Event resume response body is empty");
  log(`event resume session=${sessionId} afterSeq=${afterSeq}`);
  return consumeSseResponse(response, "event-resume");
}

async function resumeRunEvents(runId, afterSeq) {
  await syncAuthProfile();
  const response = await fetch(`${apiBase}/v1/chat/runs/${encodeURIComponent(runId)}/events/resume?afterSeq=${Number(afterSeq || 0)}`,
    withProxyProfile());
  if (!response.ok) throw new Error(`Run event resume failed: ${response.status}`);
  if (!response.body) throw new Error("Run event resume response body is empty");
  log(`event resume run=${runId} afterSeq=${afterSeq}`);
  return consumeSseResponse(response, "run-event-resume");
}

async function consumeSseResponse(response, source) {
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let eventCount = 0;
  let lastEventSeq = 0;
  let terminal = false;
  const consumeEvent = data => {
    const turn = unwrapTurnStream(JSON.parse(data));
    if (turn.kind === "heartbeat") {
      log(`${source} heartbeat lastSeq=${turn.lastSeq || 0}`);
      return;
    }
    if (turn.kind === "done") {
      terminal = true;
      lastEventSeq = Math.max(lastEventSeq, Number(turn.lastSeq || 0));
      log(`${source} done lastSeq=${turn.lastSeq || 0} terminal=${turn.terminalEventType || "-"}`);
      return;
    }
    const event = turn.event;
    if (!event) return;
    eventCount += 1;
    lastEventSeq = Math.max(lastEventSeq, Number(event.sequence || 0));
    terminal = terminal || terminalRunEvents.has(event.type);
    handleChatEvent(event, source);
  };
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const parts = buffer.split(/\r?\n\r?\n/);
    buffer = parts.pop() || "";
    for (const part of parts) {
      const event = parseSseBlock(part);
      if (event?.data) {
        consumeEvent(event.data);
      }
    }
  }
  const tail = buffer.trim();
  if (tail) {
    const event = parseSseBlock(tail);
    if (event?.data) {
      consumeEvent(event.data);
    }
  }
  log(`${source} completed events=${eventCount} lastSeq=${lastEventSeq} terminal=${terminal}`);
  return { eventCount, lastSeq: lastEventSeq, terminal };
}

function unwrapTurnStream(payload) {
  if (!payload) return { kind: "empty" };
  if (payload.type === "conversation-turn-stream") {
    const turnPayload = payload.payload || {};
    if (turnPayload.type === "stream-item") {
      return { kind: "event", event: turnPayload.encodedItem?.data || null };
    }
    if (turnPayload.type === "heartbeat") {
      return {
        kind: "heartbeat",
        lastSeq: turnPayload.lastSeq,
        runId: turnPayload.turnId,
        sessionId: turnPayload.conversationId
      };
    }
    if (turnPayload.type === "done") {
      return {
        kind: "done",
        lastSeq: turnPayload.lastSeq,
        runId: turnPayload.turnId,
        sessionId: turnPayload.conversationId,
        terminalEventType: turnPayload.terminalEventType
      };
    }
    return { kind: "unknown", raw: payload };
  }
  // 本地联调台允许解析旧 ChatEventDto，便于手动排查不同后端版本。
  if (payload.runId && payload.sessionId && payload.type) {
    return { kind: "event", event: payload };
  }
  return { kind: "unknown", raw: payload };
}

function parseSseBlock(block) {
  const result = {};
  const data = [];
  for (const line of block.split(/\r?\n/)) {
    if (line.startsWith("event:")) result.event = line.slice(6).trim();
    if (line.startsWith("data:")) data.push(line.slice(5).trimStart());
  }
  result.data = data.join("\n");
  return result;
}

function handleChatEvent(event, source = "event", options = {}) {
  if (!event || !event.type) return false;
  rememberEvent(event);
  updateSeqFromEvent(event);

  if (options.broadcast !== false && bc) {
    bc.postMessage({ type: "event", sessionId: event.sessionId, payload: event });
  }
  logChatEvent(event, source);

  if (event.sessionId !== state.selectedSessionId) {
    // 本地联调台只有一个消息面板，但 WebSocket 是用户级连接，可能同时收到多个 session 的 run topic。
    // 非当前会话事件只进入 sessionSeq/日志；正式前端应按 sessionId 分发到对应会话面板。
    return true;
  }
  const eventKey = `${event.sessionId}:${event.sequence}`;
  if (event.sequence && state.renderedEventKeys.has(eventKey)) return false;
  if (event.sequence) state.renderedEventKeys.add(eventKey);

  if (event.type === "run.started") {
    state.activeRunId = event.runId;
    state.activeRunStatus = "RUNNING";
    setActiveRunLabel();
    return true;
  }
  if (event.type === "message.delta") {
    appendAssistantDelta(event.runId, event.payload?.delta || event.payload?.content || "");
    return true;
  }
  if (event.type === "message.snapshot") {
    replaceAssistantSnapshot(event.runId, event.payload?.content || "");
    return true;
  }
  if (event.type === "message.completed") {
    return true;
  }
  if (event.type === "run.waiting_user") {
    flushPendingDeltas(event.runId);
    state.activeRunId = null;
    state.activeTopicId = null;
    state.activeRunStatus = "WAITING_USER";
    setActiveRunLabel();
    scheduleAmbiguousAutoSelect({
      sessionId: event.sessionId,
      waitingUserInput: true,
      interactionId: event.payload?.interactionId,
      autoSelectAt: event.payload?.autoSelectAt
    });
    appendMessage("system", `${event.type} ${event.runId}`);
    return true;
  }
  if (event.type?.startsWith("runtime.")) {
    flushPendingDeltas(event.runId);
    appendMessage("system", runtimeEventLabel(event.type, event.payload || {}));
    return true;
  }
  if (terminalRunEvents.has(event.type)) {
    flushPendingDeltas(event.runId);
    if ((event.type === "run.completed" || event.type === "run.cancelled")
        && event.payload?.messageReady === true) {
      bindAssistantMessageId(event.runId, event.payload);
    }
    state.activeRunId = event.runId;
    state.activeRunStatus = event.type.replace("run.", "").toUpperCase();
    setActiveRunLabel();
    appendMessage("system", `${event.type} ${event.runId}`);
    return true;
  }
  return true;
}

function runtimeEventLabel(eventType, payload) {
  const sourceType = payload.sourceType || "unknown";
  const text = payload.text || payload.delta || payload.task || payload.inputPreview || payload.projectHome || payload.sourcePayload?.message || payload.sourcePayload?.project_home || "";
  return text ? `${eventType} ${sourceType}: ${text}` : `${eventType} ${sourceType}`;
}

function logChatEvent(event, source) {
  if (event.type === "message.delta") {
    const seq = Number(event.sequence || 0);
    if (seq > 0 && seq % 50 !== 0) {
      return;
    }
  }
  log(`${source} ${event.sequence} ${event.type}`);
}

function appendAssistantDelta(runId, delta) {
  if (!delta) return;
  state.pendingDeltaByRun.set(runId, `${state.pendingDeltaByRun.get(runId) || ""}${delta}`);
  scheduleDeltaFlush();
}

function scheduleDeltaFlush() {
  if (state.deltaFlushScheduled) return;
  state.deltaFlushScheduled = true;
  const schedule = window.requestAnimationFrame || (callback => window.setTimeout(callback, 16));
  schedule(() => flushPendingDeltas());
}

function flushPendingDeltas(runId = null) {
  const entries = runId
    ? [[runId, state.pendingDeltaByRun.get(runId) || ""]]
    : [...state.pendingDeltaByRun.entries()];
  for (const [currentRunId, delta] of entries) {
    if (!delta) continue;
    appendAssistantText(currentRunId, delta);
    state.pendingDeltaByRun.delete(currentRunId);
  }
  if (!runId) {
    state.deltaFlushScheduled = false;
  }
}

function appendAssistantText(runId, delta) {
  let node = state.assistantNodeByRun.get(runId);
  if (!node) {
    node = appendMessage("assistant", "", { runId });
    state.assistantNodeByRun.set(runId, node);
  }
  node.querySelector(".message-content").textContent += delta;
  scrollMessages();
}

function replaceAssistantSnapshot(runId, content) {
  state.pendingDeltaByRun.delete(runId);
  let node = state.assistantNodeByRun.get(runId);
  if (!node) {
    node = appendMessage("assistant", "", { runId });
    state.assistantNodeByRun.set(runId, node);
  }
  node.querySelector(".message-content").textContent = content;
  scrollMessages();
}

function bindAssistantMessageId(runId, payload) {
  const messageId = payload?.assistantMessageId || payload?.feedbackTargetMessageId;
  if (!messageId) return;
  const node = state.assistantNodeByRun.get(runId);
  if (!node) return;
  const dataset = {
    ...node.dataset,
    messageId,
    runId,
    originType: node.dataset.originType || "NORMAL"
  };
  for (const [key, value] of Object.entries(dataset)) {
    if (value !== undefined && value !== null && typeof value !== "object") {
      node.dataset[key] = value;
    }
  }
  ensureMessageActions(node, "assistant", dataset, dataset);
}

function appendMessage(role, content, dataset = {}, message = null) {
  const node = document.createElement("div");
  node.className = `message ${role || "system"}`;
  for (const [key, value] of Object.entries(dataset)) {
    if (value !== undefined && value !== null && typeof value !== "object") {
      node.dataset[key] = value;
    }
  }

  const contentNode = document.createElement("div");
  contentNode.className = "message-content";
  contentNode.textContent = content;
  node.appendChild(contentNode);

  const messageParts = message?.parts || dataset.parts || [];
  if (role === "assistant" && messageParts.length) {
    node.appendChild(messagePartsNode(messageParts));
  }

  ensureMessageActions(node, role, dataset, message, content);
  $("messages").appendChild(node);
  scrollMessages();
  return node;
}

function ensureMessageActions(node, role, dataset = {}, message = null, fallbackContent = null) {
  if (!dataset.messageId || node.querySelector(".message-actions")) {
    return;
  }
  if (dataset.messageId) {
    node.appendChild(messageMeta(dataset));
    const actions = document.createElement("div");
    actions.className = "message-actions";
    const locked = isLockedMessage(dataset);
    actions.appendChild(messageActionButton("复制", false, () => copyMessageContent(message || dataset,
      fallbackContent ?? node.querySelector(".message-content")?.textContent)));
    if (role === "user") {
      actions.appendChild(messageActionButton("编辑", locked, () => editUserMessage(message || dataset)));
    }
    if (role === "assistant") {
      actions.appendChild(messageActionButton("重新生成", locked, () => regenerateAssistantMessage(message || dataset)));
      actions.appendChild(feedbackButton(message || dataset, dataset.messageId, "LIKE", "赞"));
      actions.appendChild(feedbackButton(message || dataset, dataset.messageId, "DISLIKE", "踩"));
    }
    const navigator = messageVersionNavigator();
    actions.appendChild(navigator.node);
    actions.appendChild(messageActionButton("新建分支", false, () => createBranchFromMessage(message || dataset)));
    node.appendChild(actions);
    hydrateMessageVersionNavigator(message || dataset, navigator);
  }
}

function messagePartsNode(parts) {
  const visibleParts = parts.filter(part => part.visible !== false);
  const renderParts = visibleParts.length ? visibleParts : parts;
  const details = document.createElement("details");
  details.className = "message-parts";
  const summary = document.createElement("summary");
  summary.textContent = `过程 ${visibleParts.length}/${parts.length}`;
  details.appendChild(summary);
  for (const part of renderParts) {
    const row = document.createElement("div");
    row.className = `message-part ${part.displayHint || "collapsible"}`;
    const label = [part.partOrder, part.title || part.partType, part.status, part.channel, part.sourceType]
      .filter(value => value !== undefined && value !== null && value !== "")
      .join(" · ");
    row.textContent = part.contentText ? `${label}: ${part.contentText}` : label;
    details.appendChild(row);
  }
  return details;
}

function messageMeta(dataset) {
  const meta = document.createElement("div");
  meta.className = "message-meta";
  const parts = [
    dataset.messageId,
    dataset.originType || "NORMAL",
    `v${dataset.siblingIndex || 1}`,
    dataset.locked === true || dataset.locked === "true" ? "locked" : null
  ].filter(Boolean);
  meta.textContent = parts.join(" · ");
  return meta;
}

function messageActionButton(label, disabled, handler) {
  const button = document.createElement("button");
  button.type = "button";
  button.textContent = label;
  button.disabled = Boolean(disabled);
  button.addEventListener("click", () => runSafely(handler));
  return button;
}

function isLockedMessage(message) {
  return message?.locked === true || message?.locked === "true";
}

async function copyMessageContent(message, fallbackContent) {
  const text = String(message?.content ?? fallbackContent ?? "");
  if (!text) return;
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(text);
  } else {
    const box = document.createElement("textarea");
    box.value = text;
    box.style.position = "fixed";
    box.style.left = "-9999px";
    document.body.appendChild(box);
    box.select();
    document.execCommand("copy");
    box.remove();
  }
  log(`message copied ${message.messageId || "-"}`);
}

function messageVersionNavigator() {
  // 同父同角色 sibling 超过一个时显示 < 1/3 >；候选版本由 /messages 的 versionInfo 直接返回。
  const node = document.createElement("span");
  node.className = "version-nav";
  node.hidden = true;

  const previous = document.createElement("button");
  previous.type = "button";
  previous.className = "icon-button";
  previous.textContent = "<";
  previous.title = "上一个历史版本";
  previous.disabled = true;

  const label = document.createElement("span");
  label.className = "version-label";
  label.textContent = "1/1";

  const next = document.createElement("button");
  next.type = "button";
  next.className = "icon-button";
  next.textContent = ">";
  next.title = "下一个历史版本";
  next.disabled = true;

  node.append(previous, label, next);
  return { node, previous, label, next };
}

function hydrateMessageVersionNavigator(message, navigator) {
  const versionInfo = message?.versionInfo;
  if (!versionInfo || versionInfo.total <= 1 || !Array.isArray(versionInfo.variants)) {
    navigator.node.hidden = true;
    return;
  }
  navigator.node.hidden = false;
  renderVersionNavigator(navigator, versionInfo);
}

function renderVersionNavigator(navigator, versionInfo) {
  const variants = versionInfo.variants || [];
  const current = Math.max(0, Math.min((versionInfo.currentIndex || 1) - 1, variants.length - 1));
  navigator.label.textContent = `${current + 1}/${versionInfo.total}`;
  navigator.previous.disabled = current <= 0;
  navigator.next.disabled = current >= variants.length - 1;
  navigator.previous.onclick = () => runSafely(() => selectMessageVersion(variants[current - 1]));
  navigator.next.onclick = () => runSafely(() => selectMessageVersion(variants[current + 1]));
}

function feedbackButton(message, messageId, rating, label) {
  const button = document.createElement("button");
  button.type = "button";
  button.textContent = label;
  const currentRating = message?.feedback?.status === "ACTIVE" ? message.feedback.rating : null;
  if (currentRating === rating) {
    button.className = "feedback-active";
    button.title = "再次点击取消";
  }
  button.addEventListener("click", () => runSafely(async () => {
    const result = currentRating === rating
      ? await cancelFeedback(messageId)
      : await submitFeedback(messageId, rating);
    if (state.selectedSessionId) {
      await loadMessagesOnly(state.selectedSessionId);
    }
    return result;
  }));
  return button;
}

async function submitFeedback(messageId, rating) {
  const commentText = prompt(`反馈 ${rating}，可输入补充说明：`) || "";
  const feedback = await requestJson(`/v1/chat/messages/${encodeURIComponent(messageId)}/feedback`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      runId: null,
      rating,
      reasonCode: "LOCAL_TEST",
      commentText,
      metadata: { source: "local-test-frontend" }
    })
  });
  log(`feedback ${feedback.feedbackId} ${feedback.rating}`);
  return feedback;
}

async function cancelFeedback(messageId) {
  const feedback = await requestJson(`/v1/chat/messages/${encodeURIComponent(messageId)}/feedback`, {
    method: "DELETE"
  });
  log(`feedback cancelled message=${feedback.messageId}`);
  return feedback;
}

async function editUserMessage(message) {
  if (isRunInProgress()) return alert("当前回答仍在生成中，请先停止或等待完成后再编辑消息");
  if (isLockedMessage(message)) return alert("分支快照消息是只读的，不能编辑");
  const messageId = message.messageId || message.id;
  const original = message.content || "";
  const next = prompt("编辑这条用户消息，并从该位置重新提问：", original);
  if (!next || next.trim() === original.trim()) return;
  const body = {
    commandId: `edit_${Date.now()}`,
    sessionId: requireSessionId(),
    conversationId: requireSessionId(),
    message: next.trim(),
    runMode: "EDIT_USER",
    editedMessageId: messageId,
    attachments: [],
    metadata: { source: "local-test-frontend", treeAction: "edit-user" }
  };
  await startRunRequest(body, { optimisticUserMessage: next.trim() });
  log(`edit user message ${messageId}`);
}

async function regenerateAssistantMessage(message) {
  if (isRunInProgress()) return alert("当前回答仍在生成中，请先停止或等待完成后再重新生成");
  if (isLockedMessage(message)) return alert("分支快照消息是只读的，不能重新生成");
  const messageId = message.messageId || message.id;
  if (!confirm("确认基于这条 assistant 消息重新生成一个候选回答？")) return;
  const body = {
    commandId: `regen_${Date.now()}`,
    sessionId: requireSessionId(),
    conversationId: requireSessionId(),
    message: null,
    runMode: "REGENERATE_ASSISTANT",
    regeneratedMessageId: messageId,
    attachments: [],
    metadata: { source: "local-test-frontend", treeAction: "regenerate-assistant" }
  };
  await startRunRequest(body);
  log(`regenerate assistant message ${messageId}`);
}

async function createBranchFromMessage(message) {
  const messageId = message.messageId || message.id;
  const title = prompt("新分支标题：", `从 ${messageId.slice(0, 12)} 新建分支`);
  if (title === null) return;
  const session = await requestJson(`/v1/chat/sessions/${encodeURIComponent(requireSessionId())}/branches`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ sourceMessageId: messageId, title: title.trim() || null })
  });
  await refreshSessions();
  await selectSession(session.sessionId);
  log(`branch created ${session.sessionId} from ${messageId}`);
}

async function selectMessageVersion(variant) {
  if (!variant) return;
  const leafMessageId = variant.switchLeafMessageId || variant.messageId;
  await selectMessagePath(leafMessageId);
}

async function selectMessagePath(leafMessageId) {
  const sessionId = requireSessionId();
  const page = await requestJson(`/v1/chat/sessions/${encodeURIComponent(sessionId)}/messages?leafMessageId=${encodeURIComponent(leafMessageId)}&limit=50`);
  renderHistory(page.items || []);
  replayStoredEvents(sessionId);
  requestJson(`/v1/chat/sessions/${encodeURIComponent(sessionId)}/path`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ leafMessageId })
  })
    .then(() => log(`path saved leaf=${leafMessageId}`))
    .catch(error => log(`path save failed leaf=${leafMessageId}: ${error.message}`));
  log(`path preview leaf=${leafMessageId}`);
}

async function refreshDocuments() {
  const query = state.selectedSessionId ? `?limit=30&sessionId=${encodeURIComponent(state.selectedSessionId)}` : "?limit=30";
  const page = await requestJson(`/v1/documents${query}`);
  state.documents = page.items || [];
  renderDocuments();
}

async function uploadDocument() {
  const file = $("fileInput").files?.[0];
  if (!file) return alert("请选择文件");
  const data = new FormData();
  data.append("file", file);
  if ($("uploadBindSession").checked && state.selectedSessionId) {
    data.append("sessionId", state.selectedSessionId);
  }
  const uploadMetadataText = $("uploadMetadataInput")?.value?.trim();
  if (uploadMetadataText) {
    const uploadMetadata = parseOptionalJsonObject(uploadMetadataText, "upload metadata");
    if (uploadMetadata === undefined) {
      return;
    }
    data.append("metadata", JSON.stringify(uploadMetadata));
  }
  const document = await requestJson("/v1/documents", { method: "POST", body: data });
  log(`document uploaded ${document.id}`);
  $("fileInput").value = "";
  await refreshDocuments();
}

function renderDocuments() {
  const list = $("documentsList");
  list.replaceChildren();
  for (const doc of state.documents) {
    const row = document.createElement("div");
    const selected = state.selectedDocuments.has(doc.id);
    const available = doc.status === "AVAILABLE";
    row.className = `list-item document-item${selected ? " active" : ""}`;
    row.innerHTML = `
      <div class="item-title">${escapeHtml(doc.originalName || doc.id)}</div>
      <div class="item-meta">${escapeHtml(doc.status)} · ${formatBytes(doc.sizeBytes)} · ${escapeHtml(doc.id)}</div>
      <div class="button-row">
        <button data-action="toggle" type="button"${available ? "" : " disabled"}>${selected ? "移除附件" : "作为附件"}</button>
        <button data-action="detail" type="button">详情</button>
        <button data-action="status" type="button">状态</button>
        <button data-action="preview" type="button"${available ? "" : " disabled"}>预览</button>
        <button data-action="download" type="button"${available ? "" : " disabled"}>下载</button>
        <button data-action="rename" type="button">改名</button>
        <button data-action="delete" type="button">删除</button>
      </div>
    `;
    row.querySelector('[data-action="toggle"]').addEventListener("click", () => toggleDocument(doc));
    row.querySelector('[data-action="detail"]').addEventListener("click", () => runSafely(() => loadDocumentDetail(doc.id)));
    row.querySelector('[data-action="status"]').addEventListener("click", () => runSafely(() => loadDocumentStatus(doc.id)));
    row.querySelector('[data-action="preview"]').addEventListener("click", () => runSafely(() => previewDocument(doc.id)));
    row.querySelector('[data-action="download"]').addEventListener("click", () => window.open(`/v1/documents/${encodeURIComponent(doc.id)}/download`, "_blank"));
    row.querySelector('[data-action="rename"]').addEventListener("click", () => runSafely(() => renameDocument(doc)));
    row.querySelector('[data-action="delete"]').addEventListener("click", () => runSafely(() => deleteDocument(doc.id)));
    list.appendChild(row);
  }
  renderSelectedDocs();
}

function toggleDocument(doc) {
  if (state.selectedDocuments.has(doc.id)) {
    state.selectedDocuments.delete(doc.id);
  } else if (doc.status === "AVAILABLE") {
    state.selectedDocuments.set(doc.id, doc);
  }
  renderDocuments();
}

function renderSelectedDocs() {
  const box = $("selectedDocs");
  box.replaceChildren();
  for (const doc of state.selectedDocuments.values()) {
    const chip = document.createElement("span");
    chip.className = "doc-chip";
    chip.textContent = doc.originalName || doc.id;
    box.appendChild(chip);
  }
}

async function loadDocumentDetail(documentId) {
  const doc = await requestJson(`/v1/documents/${encodeURIComponent(documentId)}`);
  log(`document detail ${documentId}: ${doc.originalName}, ${doc.status}, ${formatBytes(doc.sizeBytes)}`);
}

async function loadDocumentStatus(documentId) {
  const status = await requestJson(`/v1/documents/${encodeURIComponent(documentId)}/status`);
  log(`document status ${documentId}: ${status.status}, tokenSize=${status.tokenSize ?? "-"}`);
}

async function previewDocument(documentId) {
  const access = await requestJson(`/v1/documents/${encodeURIComponent(documentId)}/preview-url`);
  window.open(access.accessUrl, "_blank");
}

async function renameDocument(doc) {
  const originalName = prompt("请输入新的文档展示名：", doc.originalName || "");
  if (!originalName) return;
  await requestJson(`/v1/documents/${encodeURIComponent(doc.id)}`, {
    method: "PATCH",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ originalName, metadataJson: metadataJsonForUpdate(doc.metadataJson) })
  });
  await refreshDocuments();
}

async function deleteDocument(documentId) {
  if (!confirm("确认删除这个文档？")) return;
  await requestJson(`/v1/documents/${encodeURIComponent(documentId)}`, { method: "DELETE" });
  state.selectedDocuments.delete(documentId);
  await refreshDocuments();
}

function rememberEvent(event) {
  if (!event?.sessionId || !event.sequence) return;
  const key = eventsKey(event.sessionId);
  const events = readStoredEvents(event.sessionId);
  if (!events.some(item => item.sequence === event.sequence)) {
    events.push(event);
    events.sort((a, b) => a.sequence - b.sequence);
    localStorage.setItem(key, JSON.stringify(events.slice(-500)));
  }
}

function replayStoredEvents(sessionId) {
  const events = readStoredEvents(sessionId);
  const completedRuns = new Set(events.filter(event => terminalRunEvents.has(event.type)).map(event => event.runId));
  for (const event of events) {
    if (!completedRuns.has(event.runId)) {
      handleChatEvent(event, "replay", { broadcast: false });
    }
  }
}

function readStoredEvents(sessionId) {
  try {
    return JSON.parse(localStorage.getItem(eventsKey(sessionId)) || "[]");
  } catch {
    return [];
  }
}

function updateSeqFromEvent(event) {
  if (event?.sessionId) {
    setSessionSeq(event.sessionId, Math.max(lastSeq(event.sessionId), Number(event.sequence || 0)));
  }
}

function setStreamStatus(status) {
  if (!status) return;
  state.activeRunId = status.activeRunId || null;
  state.activeTopicId = status.activeStreamTopicId || null;
  state.activeRunStatus = status.activeRunStatus || null;
  setActiveRunLabel();
  scheduleAmbiguousAutoSelect(status);
}

function scheduleAmbiguousAutoSelect(status) {
  const sessionId = status?.sessionId || state.selectedSessionId;
  if (!sessionId || sessionId !== state.selectedSessionId) {
    return;
  }
  const interactionId = status?.waitingUserInput ? status.interactionId : null;
  const autoSelectAt = status?.autoSelectAt;
  if (!interactionId || !autoSelectAt) {
    clearAmbiguousAutoSelectTasks();
    return;
  }
  const deadlineMs = Date.parse(autoSelectAt);
  if (!Number.isFinite(deadlineMs)) {
    log(`ambiguous auto-select ignored invalid deadline interaction=${interactionId}`);
    return;
  }
  const existing = state.ambiguousAutoSelectTasks.get(interactionId);
  if (existing?.autoSelectAt === autoSelectAt) {
    return;
  }
  clearAmbiguousAutoSelectTasks();
  const task = {
    sessionId,
    interactionId,
    autoSelectAt,
    commandId: `cmd_ambiguous_auto_${interactionId}_${Date.now()}`,
    attempts: 0,
    inFlight: false,
    timerId: null
  };
  state.ambiguousAutoSelectTasks.set(interactionId, task);
  armAmbiguousAutoSelect(task, Math.max(0, deadlineMs - Date.now()));
}

function armAmbiguousAutoSelect(task, delayMs) {
  task.timerId = window.setTimeout(
    () => triggerAmbiguousAutoSelect(task),
    Math.max(0, delayMs));
}

async function triggerAmbiguousAutoSelect(task) {
  if (state.ambiguousAutoSelectTasks.get(task.interactionId) !== task || task.inFlight) {
    return;
  }
  task.inFlight = true;
  task.attempts += 1;
  try {
    await startRunRequest({
      commandId: task.commandId,
      sessionId: task.sessionId,
      conversationId: task.sessionId,
      runMode: "CONTINUE_INTERACTION",
      interactionId: task.interactionId,
      interactionAction: "AUTO_SELECT",
      metadata: currentRunMetadataSnapshot()
    });
    state.ambiguousAutoSelectTasks.delete(task.interactionId);
    log(`ambiguous auto-select started interaction=${task.interactionId}`);
  } catch (error) {
    if (error.code === "INTERACTION_ALREADY_HANDLED") {
      state.ambiguousAutoSelectTasks.delete(task.interactionId);
      try {
        await recoverHandledAmbiguousSelection(task.sessionId, task.interactionId);
      } catch (recoveryError) {
        log(`ambiguous auto-select race recovery failed interaction=${task.interactionId}: `
          + recoveryError.message);
      }
      return;
    }
    if (retryableAutoSelectError(error) && task.attempts < ambiguousAutoSelectMaxAttempts) {
      task.inFlight = false;
      armAmbiguousAutoSelect(task, ambiguousAutoSelectRetryDelayMs);
      log(`ambiguous auto-select retry interaction=${task.interactionId} attempt=${task.attempts + 1}`);
      return;
    }
    state.ambiguousAutoSelectTasks.delete(task.interactionId);
    log(`ambiguous auto-select stopped interaction=${task.interactionId}: ${error.message}`);
  } finally {
    task.inFlight = false;
  }
}

async function recoverHandledAmbiguousSelection(sessionId, interactionId) {
  for (let attempt = 1; attempt <= ambiguousAutoSelectMaxAttempts; attempt += 1) {
    const status = await requestJson(
      `/v1/chat/sessions/${encodeURIComponent(sessionId)}/stream-status`);
    if (sessionId === state.selectedSessionId) {
      state.activeRunId = status.activeRunId || null;
      state.activeTopicId = status.activeStreamTopicId || null;
      state.activeRunStatus = status.activeRunStatus || null;
      setActiveRunLabel();
    }
    if (status.activeRunId && status.activeStreamTopicId) {
      startActiveRunSseRestore(status, "ambiguous-auto-select-race");
      return;
    }
    if (!status.waitingUserInput || status.interactionId !== interactionId) {
      if (sessionId === state.selectedSessionId) {
        await loadMessagesOnly(sessionId);
      }
      return;
    }
    await delay(ambiguousAutoSelectRetryDelayMs);
  }
  log(`ambiguous auto-select race recovery timed out interaction=${interactionId}`);
}

function retryableAutoSelectError(error) {
  return !error.status || error.status >= 500;
}

function currentRunMetadataSnapshot() {
  const text = $("runMetadataInput")?.value?.trim();
  if (!text) {
    return {};
  }
  try {
    const value = JSON.parse(text);
    return value && !Array.isArray(value) && typeof value === "object" ? value : {};
  } catch {
    return {};
  }
}

function clearAmbiguousAutoSelectTasks() {
  for (const task of state.ambiguousAutoSelectTasks.values()) {
    if (task.timerId !== null) {
      window.clearTimeout(task.timerId);
    }
  }
  state.ambiguousAutoSelectTasks.clear();
}

function delay(durationMs) {
  return new Promise(resolve => window.setTimeout(resolve, durationMs));
}

function activeRunCatchupSeq(status) {
  const firstSeq = Number(status?.activeRunFirstSeq || 0);
  return firstSeq > 0 ? firstSeq - 1 : 0;
}

function parseRunId(topicId) {
  const prefix = "chat-run-";
  return topicId?.startsWith(prefix) ? topicId.slice(prefix.length) : null;
}

function setActiveRunLabel() {
  $("activeRun").textContent = state.activeRunId ? `${state.activeRunId} (${state.activeRunStatus || "-"})` : "-";
  updateRunControls();
}

function updateRunControls() {
  const sendButton = $("sendRunBtn");
  const stopButton = $("stopRunBtn");
  const targetInput = $("targetDomainAgentIdInput");
  const metadataInput = $("runMetadataInput");
  if (!sendButton || !stopButton) return;

  const starting = state.activeRunStatus === "STARTING";
  const running = isRunInProgressStatus(state.activeRunStatus);
  const cancellable = Boolean(state.activeRunId && running && state.activeRunStatus !== "CANCELLING");

  sendButton.disabled = starting || running;
  sendButton.textContent = starting ? "启动中..." : running ? "生成中..." : "发送 run";
  stopButton.disabled = !cancellable;
  stopButton.textContent = state.activeRunStatus === "CANCELLING" ? "停止中..." : "停止回答";
  if (targetInput) targetInput.disabled = starting || running;
  if (metadataInput) metadataInput.disabled = starting || running;
}

function isRunInProgress() {
  return isRunInProgressStatus(state.activeRunStatus);
}

function isRunInProgressStatus(status) {
  return ["RUNNING", "CANCELLING"].includes(String(status || "").toUpperCase());
}

function setSessionSeq(sessionId, seq) {
  if (!sessionId) return;
  state.sessionSeq.set(sessionId, seq);
  localStorage.setItem(seqKey(sessionId), String(seq));
  if (sessionId === state.selectedSessionId) {
    $("currentSeq").textContent = String(seq);
  }
}

function lastSeq(sessionId) {
  if (!sessionId) return 0;
  return Number(state.sessionSeq.get(sessionId) || localStorage.getItem(seqKey(sessionId)) || 0);
}

function seqKey(sessionId) {
  return `finex:test:lastSeq:${sessionId}`;
}

function eventsKey(sessionId) {
  return `finex:test:events:${sessionId}`;
}

function openCloneTab() {
  const sessionId = state.selectedSessionId || "";
  window.open(`${location.origin}${location.pathname}${sessionId ? `?sessionId=${encodeURIComponent(sessionId)}` : ""}`, "_blank");
}

async function requestJson(path, options = {}) {
  if (path.startsWith("/v1/")) {
    await syncAuthProfile();
  }
  const response = await fetch(`${apiBase}${path}`, withProxyProfile(options));
  const text = await response.text();
  const body = parseJsonBody(text);
  if (!response.ok) {
    const error = new Error(
      body?.message || body?.error || body?.code || `${response.status} ${response.statusText}`);
    error.code = body?.code || null;
    error.status = response.status;
    throw error;
  }
  return body;
}

function withProxyProfile(options = {}) {
  const headers = new Headers(options.headers || {});
  headers.set(state.proxyProfileHeaderName, state.authProfileId);
  return { ...options, headers };
}

function currentWsUrl() {
  const url = new URL(`${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/v1/chat/ws`);
  url.searchParams.set("proxyProfileId", state.authProfileId);
  return url.toString();
}

function newAuthProfileId() {
  if (crypto.randomUUID) {
    return `profile_${crypto.randomUUID().replaceAll("-", "")}`;
  }
  return `profile_${Date.now()}_${Math.random().toString(16).slice(2)}`;
}

function parseJsonBody(text) {
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return { message: text };
  }
}

function parseOptionalJsonObject(text, label) {
  if (!text) return null;
  try {
    const value = JSON.parse(text);
    if (!value || Array.isArray(value) || typeof value !== "object") {
      alert(`${label} 必须是 JSON object`);
      return undefined;
    }
    return value;
  } catch (error) {
    alert(`${label} JSON 格式错误：${error.message}`);
    return undefined;
  }
}

function metadataJsonForUpdate(metadataJson) {
  if (metadataJson == null) return null;
  return typeof metadataJson === "string" ? metadataJson : JSON.stringify(metadataJson);
}

function requireSession(handler) {
  const sessionId = requireSessionId();
  return handler(sessionId);
}

function requireSessionId() {
  if (!state.selectedSessionId) {
    throw new Error("请先选择或创建会话");
  }
  return state.selectedSessionId;
}

function updateWsState(status) {
  $("wsState").textContent = `ws: ${status}`;
  $("wsState").className = status === "connected" ? "pill" : "pill muted";
}

function scrollMessages() {
  $("messages").scrollTop = $("messages").scrollHeight;
}

function log(message) {
  const line = `[${new Date().toLocaleTimeString()}] ${message}`;
  $("eventLog").textContent = `${line}\n${$("eventLog").textContent}`.slice(0, 16000);
}

async function runSafely(fn) {
  try {
    await fn();
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    log(`error: ${message}`);
    console.error(error);
  }
}

function formatTime(value) {
  return value ? new Date(value).toLocaleString() : "-";
}

function formatBytes(value) {
  const size = Number(value || 0);
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${(size / 1024 / 1024).toFixed(1)} MB`;
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}
