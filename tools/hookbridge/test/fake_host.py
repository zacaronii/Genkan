"""Stands in for hookbridge-host: serves the fake VN's lines as hookbridge frames, no Wine needed.

Each line goes out the way LunaHook reported fakevn.exe's text: one character at a time on the
junk threads (GetTextExtentPoint32A, MultiByteToWideChar), then the whole line on TextOutA.
A reader that picks TextOutA gets clean lines.

    pip install websockets
    python3 test/fake_host.py [--host 127.0.0.1] [--port 6677] [--every 4] [--plain]

To reach it from an Android device or emulator: adb reverse tcp:6677 tcp:6677
--plain sends bare text frames instead (as Textractor's WebSocket plugin does).
"""
import argparse, asyncio, json
import websockets

LINES = [
    "「おはよう。今日もいい天気だね」",
    "彼女はそう言って、窓の外を眺めた。",
    "桜の花びらが風に舞っている。",
    "「放課後、図書室で待ってるから」",
    "先生に𠮟られた後で、食べさせられなかったお弁当を開けた。",
]

THREADS = {
    "TextOutA": "HS10@0:gdi32.dll:TextOutA",
    "GetTextExtentPoint32A": "HS10@0:gdi32.dll:GetTextExtentPoint32A",
    "MultiByteToWideChar": "HS10@0:kernel32.dll:MultiByteToWideChar",
}


def frame(text, thread):
    return json.dumps({"sentence": text, "thread": thread, "hookcode": THREADS[thread],
                       "ctx": "4012a0", "ctx2": "0"}, ensure_ascii=False)


async def serve(ws, every, plain):
    print("fake_host: a client joined", flush=True)
    try:
        i = 0
        while True:
            line = LINES[i % len(LINES)]
            if plain:
                await ws.send(line)
            else:
                for ch in line:
                    await ws.send(frame(ch, "GetTextExtentPoint32A"))
                    await ws.send(frame(ch, "MultiByteToWideChar"))
                    await asyncio.sleep(0.02)
                await ws.send(frame(line, "TextOutA"))
            print(f"fake_host: sent {line}", flush=True)
            i += 1
            await asyncio.sleep(every)
    except websockets.exceptions.ConnectionClosed:
        print("fake_host: a client left", flush=True)


async def main():
    p = argparse.ArgumentParser()
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=6677)
    p.add_argument("--every", type=float, default=4.0, help="seconds between lines")
    p.add_argument("--plain", action="store_true", help="bare text frames, no JSON")
    a = p.parse_args()
    async with websockets.serve(lambda ws: serve(ws, a.every, a.plain), a.host, a.port):
        print(f"fake_host: serving ws://{a.host}:{a.port}", flush=True)
        await asyncio.Future()

asyncio.run(main())
