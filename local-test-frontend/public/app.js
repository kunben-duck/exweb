const apiBase = "";
const terminalRunEvents = new Set(["run.completed", "run.failed", "run.cancelled"]);
const authHeadersStorageKey = "finex:test:authHeadersText";
const authProfileStorageKey = "finex:test:authProfileId";

const state = {
  sessions: [],
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
  assistantNodeByRun: new Map(),
  renderedEventKeys: new Set(),
  pendingDeltaByRun: new Map(),
  deltaFlushScheduled: false,
  restoringRunIds: new Set(),
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
  bindClick("createSessionBtn", createSession);
  bindClick("loadStateBtn", () => requireSession(sessionId => loadSessionState(sessionId, true)));
  bindClick("loadMessagesBtn", () => requireSession(loadMessagesOnly));
  bindClick("renameSessionBtn", renameSession);
  bindClick("archiveSessionBtn", () => mutateSession("archive"));
  bindClick("restoreSessionBtn", () => mutateSession("restore"));
  bindClick("closeSessionBtn", () => mutateSession("close"));
  bindClick("stopRunBtn", stopRun);
  bindClick("retryRunBtn", retryRun);
  bindClick("resumeSseBtn", () => requireSession(sessionId => resumeSse(sessionId, lastSeq(sessionId))));
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
  const session = await requestJson("/api/v1/ex/chat/sessions", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ title, channel: "web-local-test" })
  });
  $("newSessionTitle").value = "";
  await refreshSessions();
  await selectSession(session.sessionId);
}

async function refreshSessions() {
  const page = await requestJson("/api/v1/ex/chat/sessions?limit=30");
  state.sessions = page.items || [];
  renderSessions();
}

function renderSessions() {
  const list = $("sessionsList");
  list.replaceChildren();
  for (const session of state.sessions) {
    const item = document.createElement("button");
    item.type = "button";
    item.className = `list-item session-item${session.sessionId === state.selectedSessionId ? " active" : ""}`;
    item.innerHTML = `
      <div class="item-title">${escapeHtml(session.title || "未命名会话")}</div>
      <div class="item-meta">${escapeHtml(session.status)} · ${formatTime(session.updatedAt)}</div>
      <div class="item-meta">${escapeHtml(session.sessionId)}</div>
    `;
    item.addEventListener("click", () => runSafely(() => selectSession(session.sessionId)));
    list.appendChild(item);
  }
}

async function selectSession(sessionId) {
  state.selectedSessionId = sessionId;
  localStorage.setItem("finex:test:lastSessionId", sessionId);
  history.replaceState(null, "", `?sessionId=${encodeURIComponent(sessionId)}`);
  renderSessions();
  await loadSessionState(sessionId, true);
  await refreshDocuments();
}

async function loadSessionState(sessionId, restoreStream) {
  const stateDto = await requestJson(`/api/v1/ex/chat/sessions/${encodeURIComponent(sessionId)}/state?messageLimit=50`);
  const streamStatus = stateDto.streamStatus;
  const hasActiveRun = Boolean(restoreStream && streamStatus?.activeRunId && streamStatus?.activeStreamTopicId);
  $("currentSessionId").textContent = sessionId;
  $("renameTitle").value = stateDto.session?.title || "";
  renderHistory(stateDto.messages?.items || []);
  setStreamStatus(streamStatus);
  if (hasActiveRun) {
    startActiveRunSseRestore(streamStatus, "state");
  } else {
    replayStoredEvents(sessionId);
  }
}

async function refreshCurrentStreamStatus({ restoreStream = false } = {}) {
  if (!state.selectedSessionId) return;
  const status = await requestJson(`/api/v1/ex/chat/sessions/${encodeURIComponent(state.selectedSessionId)}/stream-status`);
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
  resumeRunSse(status.activeRunId, resumeAfterSeq)
    .then(result => {
      log(`restore ${reason} run=${status.activeRunId} sseEvents=${result.eventCount} lastSeq=${result.lastSeq} terminal=${result.terminal}`);
      if (!result.terminal) {
        log(`restore ${reason} run=${status.activeRunId} ended before terminal; use SSE resume again if the run is still active`);
      }
    })
    .catch(error => log(`run sse restore failed: ${error.message}`))
    .finally(() => state.restoringRunIds.delete(status.activeRunId));
}

async function loadMessagesOnly(sessionId) {
  const page = await requestJson(`/api/v1/ex/chat/sessions/${encodeURIComponent(sessionId)}/messages?limit=50`);
  renderHistory(page.items || []);
  replayStoredEvents(sessionId);
  log(`messages loaded session=${sessionId} count=${(page.items || []).length}`);
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
      createdAt: message.createdAt
    });
  }
  scrollMessages();
}

async function renameSession() {
  const sessionId = requireSessionId();
  await requestJson(`/api/v1/ex/chat/sessions/${encodeURIComponent(sessionId)}`, {
    method: "PATCH",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ title: $("renameTitle").value.trim() })
  });
  await refreshSessions();
}

async function mutateSession(action) {
  const sessionId = requireSessionId();
  await requestJson(`/api/v1/ex/chat/sessions/${encodeURIComponent(sessionId)}/${action}`, { method: "POST" });
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
  const body = {
    commandId: `cmd_${Date.now()}`,
    sessionId: state.selectedSessionId || null,
    conversationId: state.selectedSessionId || null,
    message,
    attachments,
    metadata: {
      clientMessageId: `client_${Date.now()}`,
      forceNewTask: $("forceNewTask").checked,
      source: "local-test-frontend"
    }
  };

  appendMessage("user", message);
  $("messageInput").value = "";
  state.activeRunId = null;
  state.activeTopicId = null;
  state.activeRunStatus = "STARTING";
  setActiveRunLabel();
  let run;
  try {
    run = await requestJson("/api/v1/ex/chat/runs", {
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
  subscribeTopic(run.streamTopicId, run.firstSeq || 0);
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
    result = await requestJson(`/api/v1/ex/chat/runs/${encodeURIComponent(state.activeRunId)}/stop`, { method: "POST" });
  } catch (error) {
    state.activeRunStatus = previousStatus;
    setActiveRunLabel();
    throw error;
  }
  state.activeRunStatus = result.status;
  setActiveRunLabel();
  if (result.sessionId) {
    await resumeSse(result.sessionId, resumeAfterSeq);
  }
  log(`stop ${result.runId} -> ${result.status}`);
}

async function retryRun() {
  if (isRunInProgress()) return alert("当前回答仍在生成中，请先停止或等待完成后再重新生成");
  if (!state.activeRunId) return alert("没有可重试 run");
  const run = await requestJson(`/api/v1/ex/chat/runs/${encodeURIComponent(state.activeRunId)}/retry`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ commandId: `retry_${Date.now()}`, message: null, attachments: [], metadata: { source: "local-test-frontend" } })
  });
  state.activeRunId = run.runId;
  state.activeTopicId = run.streamTopicId;
  state.activeRunStatus = "RUNNING";
  setActiveRunLabel();
  await ensureWs();
  subscribeTopic(run.streamTopicId, run.firstSeq || 0);
}

async function restoreActiveRun() {
  const sessionId = requireSessionId();
  const status = await requestJson(`/api/v1/ex/chat/sessions/${encodeURIComponent(sessionId)}/stream-status`);
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
    updateWsState("disconnected");
  });
  state.ws.addEventListener("error", () => updateWsState("error"));
}

function disconnectWs() {
  if (state.ws) {
    for (const topicId of state.subscribedTopics) {
      sendWs({ type: "unsubscribe", topicId });
    }
    state.ws.close();
  }
  state.ws = null;
  state.subscribedTopics.clear();
  updateWsState("disconnected");
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

function subscribeTopic(topicId, afterSeq) {
  if (!topicId) return;
  ensureWs().then(() => {
    state.subscribedTopics.add(topicId);
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
    if (envelope.reply?.type !== "ack") {
      log(`ws reply ${JSON.stringify(envelope.reply)}`);
    }
    return;
  }
  if (envelope.type === "error") {
    log(`ws error ${envelope.code}: ${envelope.message}`);
    if (envelope.code === "RECOVER_REQUIRED" && state.selectedSessionId) {
      const runId = parseRunId(envelope.topicId);
      const resume = runId
        ? resumeRunSse(runId, lastSeq(state.selectedSessionId))
        : resumeSse(state.selectedSessionId, lastSeq(state.selectedSessionId));
      resume.catch(error => log(error.message));
    }
    return;
  }
  if (envelope.type === "message" && envelope.payload) {
    handleChatEvent(envelope.payload, "ws", { topicId: envelope.topicId });
    sendWs({ type: "ack", topicId: envelope.topicId, seq: envelope.payload.sequence });
    if (terminalRunEvents.has(envelope.payload.type)) {
      sendWs({ type: "unsubscribe", topicId: envelope.topicId });
      state.subscribedTopics.delete(envelope.topicId);
    }
  }
}

async function resumeSse(sessionId, afterSeq) {
  await syncAuthProfile();
  const response = await fetch(`${apiBase}/api/v1/ex/chat/sessions/${encodeURIComponent(sessionId)}/events/sse?afterSeq=${Number(afterSeq || 0)}`,
    withProxyProfile());
  if (!response.ok) throw new Error(`SSE resume failed: ${response.status}`);
  if (!response.body) throw new Error("SSE resume response body is empty");
  log(`sse resume session=${sessionId} afterSeq=${afterSeq}`);
  return consumeSseResponse(response, "sse");
}

async function resumeRunSse(runId, afterSeq) {
  await syncAuthProfile();
  const response = await fetch(`${apiBase}/api/v1/ex/chat/runs/${encodeURIComponent(runId)}/events/sse?afterSeq=${Number(afterSeq || 0)}`,
    withProxyProfile());
  if (!response.ok) throw new Error(`Run SSE resume failed: ${response.status}`);
  if (!response.body) throw new Error("Run SSE resume response body is empty");
  log(`sse resume run=${runId} afterSeq=${afterSeq}`);
  return consumeSseResponse(response, "run-sse");
}

async function consumeSseResponse(response, source) {
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let eventCount = 0;
  let lastEventSeq = 0;
  let terminal = false;
  const consumeEvent = data => {
    const event = JSON.parse(data);
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
  if (!event || !event.type) return;
  rememberEvent(event);
  updateSeqFromEvent(event);

  if (options.broadcast !== false && bc) {
    bc.postMessage({ type: "event", sessionId: event.sessionId, payload: event });
  }
  logChatEvent(event, source);

  if (event.sessionId !== state.selectedSessionId) return;
  const eventKey = `${event.sessionId}:${event.sequence}`;
  if (event.sequence && state.renderedEventKeys.has(eventKey)) return;
  if (event.sequence) state.renderedEventKeys.add(eventKey);

  if (event.type === "run.started") {
    state.activeRunId = event.runId;
    state.activeRunStatus = "RUNNING";
    setActiveRunLabel();
    return;
  }
  if (event.type === "message.delta") {
    appendAssistantDelta(event.runId, event.payload?.delta || event.payload?.content || "");
    return;
  }
  if (event.type === "message.completed") {
    return;
  }
  if (terminalRunEvents.has(event.type)) {
    flushPendingDeltas(event.runId);
    state.activeRunId = event.runId;
    state.activeRunStatus = event.type.replace("run.", "").toUpperCase();
    setActiveRunLabel();
    appendMessage("system", `${event.type} ${event.runId}`);
  }
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

function appendMessage(role, content, dataset = {}) {
  const node = document.createElement("div");
  node.className = `message ${role || "system"}`;
  for (const [key, value] of Object.entries(dataset)) {
    if (value !== undefined && value !== null) node.dataset[key] = value;
  }

  const contentNode = document.createElement("div");
  contentNode.className = "message-content";
  contentNode.textContent = content;
  node.appendChild(contentNode);

  if (role === "assistant" && dataset.messageId) {
    const actions = document.createElement("div");
    actions.className = "message-actions";
    actions.appendChild(feedbackButton(dataset.messageId, "LIKE", "赞"));
    actions.appendChild(feedbackButton(dataset.messageId, "DISLIKE", "踩"));
    node.appendChild(actions);
  }

  $("messages").appendChild(node);
  scrollMessages();
  return node;
}

function feedbackButton(messageId, rating, label) {
  const button = document.createElement("button");
  button.type = "button";
  button.textContent = label;
  button.addEventListener("click", () => runSafely(() => submitFeedback(messageId, rating)));
  return button;
}

async function submitFeedback(messageId, rating) {
  const commentText = prompt(`反馈 ${rating}，可输入补充说明：`) || "";
  const feedback = await requestJson(`/api/v1/ex/chat/messages/${encodeURIComponent(messageId)}/feedback`, {
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
}

async function refreshDocuments() {
  const query = state.selectedSessionId ? `?limit=30&sessionId=${encodeURIComponent(state.selectedSessionId)}` : "?limit=30";
  const page = await requestJson(`/api/v1/ex/documents${query}`);
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
  const document = await requestJson("/api/v1/ex/documents", { method: "POST", body: data });
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
    row.querySelector('[data-action="download"]').addEventListener("click", () => window.open(`/api/v1/ex/documents/${encodeURIComponent(doc.id)}/download`, "_blank"));
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
  const doc = await requestJson(`/api/v1/ex/documents/${encodeURIComponent(documentId)}`);
  log(`document detail ${documentId}: ${doc.originalName}, ${doc.status}, ${formatBytes(doc.sizeBytes)}`);
}

async function loadDocumentStatus(documentId) {
  const status = await requestJson(`/api/v1/ex/documents/${encodeURIComponent(documentId)}/status`);
  log(`document status ${documentId}: ${status.status}, tokenSize=${status.tokenSize ?? "-"}`);
}

async function previewDocument(documentId) {
  const access = await requestJson(`/api/v1/ex/documents/${encodeURIComponent(documentId)}/preview-url`);
  window.open(access.accessUrl, "_blank");
}

async function renameDocument(doc) {
  const originalName = prompt("请输入新的文档展示名：", doc.originalName || "");
  if (!originalName) return;
  await requestJson(`/api/v1/ex/documents/${encodeURIComponent(doc.id)}`, {
    method: "PATCH",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ originalName, metadataJson: doc.metadataJson || null })
  });
  await refreshDocuments();
}

async function deleteDocument(documentId) {
  if (!confirm("确认删除这个文档？")) return;
  await requestJson(`/api/v1/ex/documents/${encodeURIComponent(documentId)}`, { method: "DELETE" });
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
  const retryButton = $("retryRunBtn");
  const forceNewTask = $("forceNewTask");
  if (!sendButton || !stopButton || !retryButton || !forceNewTask) return;

  const starting = state.activeRunStatus === "STARTING";
  const running = isRunInProgressStatus(state.activeRunStatus);
  const cancellable = Boolean(state.activeRunId && running && state.activeRunStatus !== "CANCELLING");

  sendButton.disabled = starting || running;
  sendButton.textContent = starting ? "启动中..." : running ? "生成中..." : "发送 run";
  stopButton.disabled = !cancellable;
  stopButton.textContent = state.activeRunStatus === "CANCELLING" ? "停止中..." : "停止回答";
  retryButton.disabled = starting || running || !state.activeRunId;
  forceNewTask.disabled = starting || running;
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
  if (path.startsWith("/api/")) {
    await syncAuthProfile();
  }
  const response = await fetch(`${apiBase}${path}`, withProxyProfile(options));
  const text = await response.text();
  const body = parseJsonBody(text);
  if (!response.ok) {
    throw new Error(body?.message || body?.error || body?.code || `${response.status} ${response.statusText}`);
  }
  return body;
}

function withProxyProfile(options = {}) {
  const headers = new Headers(options.headers || {});
  headers.set(state.proxyProfileHeaderName, state.authProfileId);
  return { ...options, headers };
}

function currentWsUrl() {
  const url = new URL(`${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/api/v1/ex/chat/ws`);
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
