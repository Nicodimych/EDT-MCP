"""Small diagnostic MCP client. Default `inspect` does not send an AI message.

Python 3.10+, standard library only. This is an example, not a production client.
No automatic retries of tools/call: a timeout can follow successful execution.
"""
import argparse
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

PROTOCOL = "2025-11-25"


class McpClient:
    def __init__(self, endpoint, timeout=40):
        parsed = urllib.parse.urlparse(endpoint)
        if parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "localhost", "::1"}:
            raise ValueError("This diagnostic example supports loopback HTTP only")
        self.endpoint = endpoint
        self.timeout = timeout
        self.session = None
        self.sequence = 0
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def rpc(self, method, params, notification=False):
        self.sequence += 1
        request_id = self.sequence
        body = {"jsonrpc": "2.0", "method": method, "params": params}
        if not notification:
            body["id"] = request_id
        headers = {
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
            "MCP-Protocol-Version": PROTOCOL,
        }
        if self.session:
            headers["Mcp-Session-Id"] = self.session
        request = urllib.request.Request(
            self.endpoint, data=json.dumps(body).encode("utf-8"), headers=headers, method="POST"
        )
        try:
            with self.opener.open(request, timeout=self.timeout) as response:
                new_session = response.headers.get("Mcp-Session-Id")
                if new_session:
                    self.session = new_session
                if notification:
                    return None
                content_type = response.headers.get("Content-Type", "")
                if "text/event-stream" in content_type:
                    data_lines = []
                    result = None
                    # Stop at the matching response, even if the SSE connection stays open.
                    for raw_line in response:
                        line = raw_line.decode("utf-8").rstrip("\r\n")
                        if line.startswith("data:"):
                            data_lines.append(line[5:].lstrip())
                        elif not line and data_lines:
                            event = json.loads("\n".join(data_lines))
                            data_lines = []
                            if isinstance(event, dict) and event.get("id") == request_id:
                                result = event
                                break
                    if result is None and data_lines:
                        event = json.loads("\n".join(data_lines))
                        if isinstance(event, dict) and event.get("id") == request_id:
                            result = event
                    if result is None:
                        raise RuntimeError("SSE ended without the matching JSON-RPC response")
                else:
                    result = json.load(response)
        except urllib.error.HTTPError as error:
            hint = "; initialize a fresh transport session, do not replay a send blindly" if error.code == 404 else ""
            raise RuntimeError(f"HTTP {error.code}{hint}") from error
        if result.get("id") != request_id:
            raise RuntimeError("Unexpected JSON-RPC response ID")
        if "error" in result:
            raise RuntimeError(json.dumps(result["error"], ensure_ascii=False))
        return result["result"]

    def initialize(self):
        self.session = None
        info = self.rpc("initialize", {
            "protocolVersion": PROTOCOL,
            "capabilities": {},
            "clientInfo": {"name": "workmate-handoff-probe", "version": "0.1"},
        })
        if not self.session:
            raise RuntimeError("initialize returned no Mcp-Session-Id; check protocol/server version")
        self.rpc("notifications/initialized", {}, notification=True)
        return info

    def call(self, name, arguments):
        return self.rpc("tools/call", {"name": name, "arguments": arguments})

    def close(self):
        if self.session:
            request = urllib.request.Request(self.endpoint, method="DELETE", headers={
                "Mcp-Session-Id": self.session, "MCP-Protocol-Version": PROTOCOL,
            })
            try:
                with self.opener.open(request, timeout=5):
                    pass
            except (OSError, urllib.error.URLError):
                pass  # Closing the transport is best effort; never rerun a tool.
            self.session = None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--endpoint", default="http://127.0.0.1:8765/mcp")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("inspect", help="Read server status and tool names only")
    jshell = sub.add_parser("jshell", help="Execute the supplied Java fragment inside EDT")
    jshell.add_argument("file", type=Path)
    jshell.add_argument("--repl", default="edt-mcp")
    job = sub.add_parser("job", help="Poll an existing background job; does not resend it")
    job.add_argument("job_id")
    job.add_argument("--wait", type=int, choices=range(0, 31), default=5)
    args = parser.parse_args()
    client = McpClient(args.endpoint)
    try:
        initialized = client.initialize()
        if args.command == "inspect":
            catalog = client.rpc("tools/list", {})
            result = {
                "initialize": initialized,
                "status": client.call("get_server_status", {}),
                "toolNames": [tool["name"] for tool in catalog.get("tools", [])],
                "nextCursor": catalog.get("nextCursor"),
            }
        elif args.command == "jshell":
            code = args.file.read_text(encoding="utf-8-sig")
            result = client.call("ask_workmate", {
                "workmateTool": "JShell",
                "workmateArgs": json.dumps({
                    "scope": "eclipse", "repl_session_id": args.repl,
                    "manual_ids": ["eclipse_overview"],
                    "request_description": "Workmate chat handoff example: " + args.file.name,
                    "response_description": "Java fragment result; inspect compilation/runtime errors",
                    "code": code,
                }, ensure_ascii=False),
                "waitSeconds": 5, "timeoutSeconds": 30,
            })
        else:
            result = client.call("get_job_status", {"jobId": args.job_id, "waitSeconds": args.wait})
        print(json.dumps(result, ensure_ascii=False, indent=2))
        # Background status and nested JShell errors remain visible in the output.
        # Exit 0 alone is not proof of a completed Java/native request.
        return 2 if result.get("isError") else 0
    finally:
        client.close()


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    try:
        sys.exit(main())
    except (OSError, ValueError, RuntimeError, urllib.error.URLError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
