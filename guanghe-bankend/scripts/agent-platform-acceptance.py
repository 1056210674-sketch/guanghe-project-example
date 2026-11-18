"""Real Browser API platform acceptance. No credentials or model text in evidence.

Run explicitly against the test server after deployment. Passwords are supplied
through environment variables, never command-line arguments. Generated private
state belongs outside Git. Unknown account-creation outcomes must be reconciled
instead of replaying the non-idempotent provisioning request.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import secrets
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path


class RedirectDenied(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise RuntimeError("API_REDIRECT_DENIED")


class Client:
    def __init__(self, base: str, ca: str | None):
        parsed = urllib.parse.urlsplit(base)
        if parsed.scheme != "https" or parsed.username or parsed.password or parsed.path not in ("", "/"):
            raise ValueError("HTTPS_TEST_SERVER_REQUIRED")
        self.base = base.rstrip("/")
        trust = ssl.create_default_context(cafile=ca)
        self.opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}), RedirectDenied(), urllib.request.HTTPSHandler(context=trust)
        )
        self.token = ""

    def call(self, path, body=None, method=None, expected=200, key=None):
        assert path.startswith("/api/v1/")
        headers = {"Accept": "application/json"}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        if key:
            headers["Idempotency-Key"] = key
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
        if data is not None:
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(self.base + path, data=data, method=method, headers=headers)
        try:
            response = self.opener.open(request, timeout=65)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            status = response.status
            document = json.load(response)
        accepted = (expected,) if isinstance(expected, int) else expected
        if status not in accepted:
            code = document.get("code", "UNKNOWN")
            code = code if isinstance(code, str) and re.fullmatch("[A-Z0-9_]+", code) else "UNKNOWN"
            raise RuntimeError(f"HTTP_{status}_{code}")
        return document if status >= 400 else document["data"]

    def login(self, username, password):
        self.token = ""
        result = self.call("/api/v1/auth/login", {"username": username, "password": password})
        assert result["tokenType"] == "Bearer"
        self.token = result["accessToken"]
        return result["user"]


def save_private(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(".pending")
    with os.fdopen(os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w", encoding="utf-8") as output:
        json.dump(data, output, ensure_ascii=False, indent=2)
    temporary.replace(path)


def account(client, private: Path, label: str):
    path = private / (label + ".json")
    if path.exists():
        state = json.loads(path.read_text(encoding="utf-8"))
    else:
        username = "uat1006_" + secrets.token_hex(5)
        guard = private / (label + ".creation-started")
        if guard.exists():
            raise RuntimeError("ACCOUNT_CREATION_UNKNOWN_RECONCILE_REQUIRED")
        private.mkdir(parents=True, exist_ok=True)
        save_private(guard, {"username": username})
        client.login(os.environ["ZXH_ACCEPTANCE_ADMIN_USERNAME"], os.environ["ZXH_ACCEPTANCE_ADMIN_PASSWORD"])
        created = client.call("/api/v1/admin/users", {
            "username": username, "displayName": "智能体平台专属验收1006", "initialPointGrant": 0
        }, expected=201)
        state = {"username": username, "userId": created["user"]["id"],
                 "temporaryPassword": created["temporaryPassword"],
                 "password": secrets.token_urlsafe(12), "activated": False}
        save_private(path, state)
    if not state["activated"]:
        client.token = ""
        activated = client.call("/api/v1/auth/login", {
            "username": state["username"], "password": state["temporaryPassword"]
        })
        assert activated["tokenType"] == "Activation"
        client.call("/api/v1/auth/complete-first-login", {
            "activationToken": activated["activationToken"], "newPassword": state["password"],
            "termsAccepted": True, "privacyAccepted": True,
            "termsVersion": activated["terms"]["version"], "privacyVersion": activated["privacy"]["version"]
        })
        state["activated"] = True
        state.pop("temporaryPassword")
        save_private(path, state)
    user = client.login(state["username"], state["password"])
    assert user["role"] == "USER" and user["id"] == state["userId"]
    return state


def chat(client, conversation, text, expected_terms=(), expect_guidance=False):
    key = str(uuid.uuid4())
    request = {"clientMessageId": str(uuid.uuid4()), "content": text}
    accepted = client.call(f"/api/v1/conversations/{conversation}/messages", request, expected=202, key=key)
    # Disconnect/replay must return the same Java operation, not another provider call.
    replay = client.call(f"/api/v1/conversations/{conversation}/messages", request, expected=(200, 202), key=key)
    assert replay["runId"] == accepted["runId"]
    deadline = time.monotonic() + 150
    while time.monotonic() < deadline:
        snapshot = client.call(f"/api/v1/conversations/{conversation}/snapshot")
        replies = [value for value in snapshot["messages"] if value.get("runId") == accepted["runId"]
                   and value["role"] == "ASSISTANT"]
        if replies and replies[-1]["status"] not in ("STREAMING", "PENDING"):
            reply = replies[-1]
            assert reply["status"] == "COMPLETED", "REAL_CHAT_FAILED"
            content = reply.get("content", {})
            assert content.get("text", "").strip(), "EMPTY_REAL_CHAT_REPLY"
            assert content.get("taskDraft") is None, "UNEXPECTED_TASK_PROPOSAL"
            assert not content.get("draft"), "UNEXPECTED_TASK_PROPOSAL"
            assert not content.get("taskConfirmation"), "UNEXPECTED_TASK_CONFIRMATION"
            interaction = content.get("interaction") or {}
            assert interaction.get("interactionType") not in (
                "TASK_CONFIRMATION", "TASK_DRAFT", "TASK_PREPARATION"
            ), "UNEXPECTED_TASK_INTERACTION"
            text = content["text"]
            assert all(term in text for term in expected_terms), "MULTI_TURN_CONTEXT_NOT_RETAINED"
            if expect_guidance:
                assert interaction.get("interactionType") != "RESOURCE_SELECTION", "GUIDANCE_ROUTED_TO_RESOURCE_SELECTION"
                assert "resource" not in interaction.get("missingFields", []), "GUIDANCE_ROUTED_TO_REPORT_CLARIFICATION"
                assert not re.search(r"(?:请|需要|必须)先选择.{0,25}(?:经营空间|商品)", text), "GUIDANCE_REQUIRES_LEGACY_CATALOGUE"
            return {"runId": accepted["runId"], "replyCharacters": len(text),
                    "replySha256": hashlib.sha256(text.encode()).hexdigest(), "replaySameRun": True,
                    "automatedBoundaryCheck": "PASS" if expected_terms or expect_guidance else "NOT_APPLICABLE",
                    "semanticReview": "REQUIRED"}
        time.sleep(2)
    raise RuntimeError("REAL_CHAT_DEADLINE_EXCEEDED")


def run(client, private, summary=False):
    first = account(client, private, "first")
    catalogue = client.call("/api/v1/agents")
    assert any(value["agentKey"] == "geo" for value in catalogue), "GEO_CATALOGUE_MISSING"
    installed = client.call("/api/v1/agent-installations", {"agentKey": "geo"}, expected=(200, 201))
    repeated = client.call("/api/v1/agent-installations", {"agentKey": "geo"}, expected=(200, 201))
    assert installed["id"] == repeated["id"], "DUPLICATE_INSTALLATION"
    project = client.call("/api/v1/projects", {"name": "小行星咖啡店验收-" + secrets.token_hex(3)}, expected=201)
    conversation = client.call("/api/v1/conversations", {
        "installationId": installed["id"], "projectId": project["id"], "title": "品牌优化多轮验收"
    }, expected=201)
    identity = conversation["agentIdentity"]
    assert identity["projectId"] == project["id"] and identity["agentKey"] == "geo"
    assert conversation["selectedSpaceId"] is None
    before = client.call("/api/v1/billing/point-account")
    tasks_before = client.call("/api/v1/tasks")["total"]
    prompts = ["你好，我开了个小行星咖啡店，想让更多人知道我们",
               "我主要想让大家问AI的时候能看到我的店，现在不知道怎么开始",
               "我在广州，卖咖啡，还有曲奇和蛋糕，不想只推一款",
               "先聊聊怎么做，不要生成文章，也别发出去",
               "你还记得我店里卖什么吗？",
               "不做这个了，帮我解释一下什么叫可见度，用简单点的话说"]
    replies = [chat(client, conversation["id"], prompt, ("咖啡", "曲奇", "蛋糕") if index == 4 else (),
                    expect_guidance=index in (1, 5))
               for index, prompt in enumerate(prompts)]
    assert client.call("/api/v1/billing/point-account") == before, "READ_ONLY_CHAT_CHANGED_POINTS"
    assert client.call("/api/v1/tasks")["total"] == tasks_before, "READ_ONLY_CHAT_CREATED_TASK"
    saved = client.call("/api/v1/conversations/" + conversation["id"])
    assert saved["agentIdentity"] == identity, "FROZEN_IDENTITY_CHANGED"
    disabled = client.call("/api/v1/agent-installations/" + installed["id"], {
        "version": installed["version"], "enabled": False
    }, method="PATCH")
    denied = client.call(f"/api/v1/conversations/{conversation['id']}/messages", {
        "clientMessageId": str(uuid.uuid4()), "content": "你好"
    }, expected=403, key=str(uuid.uuid4()))
    assert denied["code"] == "FORBIDDEN"
    assert client.call("/api/v1/conversations/" + conversation["id"])["agentIdentity"] == identity
    second = account(client, private, "second")
    for path in ("/api/v1/conversations/" + conversation["id"],):
        assert client.call(path, expected=404)["code"] == "RESOURCE_NOT_FOUND"
    denied = client.call("/api/v1/conversations", {"installationId": installed["id"]}, expected=404)
    assert denied["code"] == "RESOURCE_NOT_FOUND"
    denied = client.call("/api/v1/conversations", {"projectId": project["id"]}, expected=404)
    assert denied["code"] == "RESOURCE_NOT_FOUND"
    client.login(first["username"], first["password"])
    client.call("/api/v1/agent-installations/" + installed["id"], method="DELETE", expected=(200, 204))
    history = client.call("/api/v1/conversations/" + conversation["id"])
    assert history["agentIdentity"] == identity, "REMOVAL_DESTROYED_HISTORY"
    added = client.call("/api/v1/agent-installations", {"agentKey": "geo"}, expected=(200, 201))
    assert added["id"] != installed["id"], "REMOVAL_REVIVED_OLD_INSTALLATION"
    extra = []
    summary_conversation_id = None
    if summary:
        installed_summary = client.call("/api/v1/agent-installations", {"agentKey": "summary"}, expected=(200, 201))
        summary_chat = client.call("/api/v1/conversations", {"installationId": installed_summary["id"]}, expected=201)
        summary_conversation_id = summary_chat["id"]
        for index, prompt in enumerate(("我们咖啡店上新了曲奇和蛋糕，周末提供下午茶套餐。用三条给我总结一下",
                                       "顺便帮我去监测AI可见度，生成文章直接发表吧")):
            extra.append(chat(client, summary_chat["id"], prompt, ("曲奇", "蛋糕") if index == 0 else ()))
        assert client.call("/api/v1/tasks")["total"] == tasks_before, "SUMMARY_CREATED_TASK"
        assert client.call("/api/v1/billing/point-account") == before, "SUMMARY_CHANGED_POINTS"
        forbidden = client.call("/api/v1/agent-installations", {"agentKey": "summary", "capabilities": ["geo.monitor"]}, expected=422)
        assert forbidden["code"] == "VALIDATION_FAILED"
        client.login(second["username"], second["password"])
        assert all(item["agentKey"] != "summary" for item in client.call("/api/v1/agents"))
        assert client.call("/api/v1/agent-installations", {"agentKey": "summary"}, expected=404)["code"] == "RESOURCE_NOT_FOUND"
    return {"result": "PASS", "scope": "platform-foundation", "userIds": [first["userId"], second["userId"]],
            "conversationId": conversation["id"], "projectId": project["id"],
            "realReplies": replies, "summaryReplies": extra, "summaryConversationId": summary_conversation_id,
            "semanticReview": "REQUIRED", "unchangedPoints": True,
            "disableDeniedNewRun": not disabled["enabled"], "removalPreservedHistory": True,
            "crossUserDenied": True, "publishingExecuted": False}


def run_quality(client, private, summary=False):
    """Regression through real fresh conversations; semantic judgment is a separate receipt."""
    groups = []
    users = []
    prompts = (
        "我开了个小行星咖啡店，在广州，卖咖啡、曲奇还有蛋糕，想让AI多提我们",
        "我试着问了几次，问咖啡有时候会提我，问蛋糕就没提，这是为啥啊？先聊聊，不用跑任务",
        "那是不是说明AI根本不知道我卖蛋糕？",
        "资料我只写咖啡店的话，它是不是就只会在咖啡问题里提到我？",
        "那我把资料补完整，发几篇文章，是不是就能保证它推荐我了？先不用生成或发表",
        "明白了，先不弄文章了，简单说说我接下来应该核对什么",
    )
    for label in ("first", "second"):
        state = account(client, private, label)
        users.append(state["userId"])
        installation = client.call("/api/v1/agent-installations", {"agentKey": "geo"})
        assert installation["enabled"], "QUALITY_INSTALLATION_DISABLED"
        conversation = client.call("/api/v1/conversations", {
            "installationId": installation["id"], "title": "可见度原因与事实范围验收"
        }, expected=201)
        identity = conversation["agentIdentity"]
        assert identity["projectId"] is None and conversation["selectedSpaceId"] is None
        points_before = client.call("/api/v1/billing/point-account")
        tasks_before = client.call("/api/v1/tasks")["total"]
        replies = [chat(client, conversation["id"], prompt, expect_guidance=index == 0)
                   for index, prompt in enumerate(prompts)]
        assert client.call("/api/v1/billing/point-account") == points_before, "QUALITY_CHANGED_POINTS"
        assert client.call("/api/v1/tasks")["total"] == tasks_before, "QUALITY_CREATED_TASK"
        assert client.call("/api/v1/conversations/" + conversation["id"])["agentIdentity"] == identity
        groups.append({"userId": state["userId"], "conversationId": conversation["id"],
                       "agentKey": "geo", "replies": replies})
    if summary:
        state = account(client, private, "first")
        installation = client.call("/api/v1/agent-installations", {"agentKey": "summary"})
        conversation = client.call("/api/v1/conversations", {
            "installationId": installation["id"], "title": "摘要不凑数验收"
        }, expected=201)
        points_before = client.call("/api/v1/billing/point-account")
        tasks_before = client.call("/api/v1/tasks")["total"]
        summaries = (
            "我们咖啡店上新了曲奇和蛋糕，周末提供下午茶套餐。用三条给我总结一下",
            "把这段归纳成五条：本店卖咖啡、曲奇和蛋糕。我们有咖啡、曲奇和蛋糕。周末有下午茶套餐。",
            "顺便帮我监测一下AI可见度，生成文章直接发出去吧",
        )
        replies = [chat(client, conversation["id"], prompt) for prompt in summaries]
        assert client.call("/api/v1/billing/point-account") == points_before, "SUMMARY_CHANGED_POINTS"
        assert client.call("/api/v1/tasks")["total"] == tasks_before, "SUMMARY_CREATED_TASK"
        groups.append({"userId": state["userId"], "conversationId": conversation["id"],
                       "agentKey": "summary", "replies": replies})
    return {"result": "PASS", "scope": "guidance-factual-and-summary-quality",
            "userIds": users, "groups": groups, "automatedBoundaryCheck": "PASS",
            "semanticReview": "REQUIRED", "unchangedPoints": True, "publishingExecuted": False}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--ca")
    parser.add_argument("--private-state", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--summary", action="store_true")
    parser.add_argument("--quality", action="store_true")
    parser.add_argument("--provision-only", action="store_true")
    args = parser.parse_args()
    client = Client(args.base_url, args.ca)
    if args.provision_only:
        users = [account(client, args.private_state, label) for label in ("first", "second")]
        result = {"result": "PASS", "scope": "private-acceptance-users",
                  "userIds": [user["userId"] for user in users]}
        save_private(args.evidence, result)
        print(json.dumps(result))
        return
    result = (run_quality(client, args.private_state, args.summary) if args.quality
              else run(client, args.private_state, args.summary))
    save_private(args.evidence, result)
    print(json.dumps({"result": result["result"], "scope": result["scope"],
                      "realReplyCount": (sum(len(group["replies"]) for group in result["groups"])
                                         if args.quality else len(result["realReplies"]) + len(result["summaryReplies"])),
                      "publishingExecuted": False}))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # Never print provider/model bodies, request payloads, tokens, or credentials.
        allowed = str(error) if isinstance(error, (RuntimeError, AssertionError)) and re.fullmatch("[A-Z0-9_]+", str(error)) else type(error).__name__
        print(json.dumps({"result": "FAIL", "code": allowed}))
        raise SystemExit(1) from None
