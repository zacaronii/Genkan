"""Connects to the hook bridge's WebSocket and prints each line (retries until the host is up)."""
import asyncio, json, sys, time
import websockets

URL = sys.argv[1] if len(sys.argv) > 1 else "ws://127.0.0.1:6677"
DEADLINE = time.time() + float(sys.argv[2] if len(sys.argv) > 2 else 90)

async def main():
    while time.time() < DEADLINE:
        try:
            async with websockets.connect(URL) as ws:
                print("listener: connected", flush=True)
                async for msg in ws:
                    d = json.loads(msg)
                    print(f"listener: [{d.get('thread')}] {d.get('sentence')}", flush=True)
                print("listener: server closed", flush=True)
                return
        except (OSError, websockets.exceptions.WebSocketException):
            await asyncio.sleep(0.5)
    print("listener: gave up", flush=True)

asyncio.run(main())
