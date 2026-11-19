#!/usr/bin/env python3
"""A stand-in AnkiConnect for testing Genkan's mining without desktop Anki.

Answers the actions Genkan uses (version, deckNames, modelNames, modelFieldNames, findNotes,
storeMediaFile, addNote) the way AnkiConnect 6 does, keeps notes in memory, refuses
duplicates in a deck, and writes each note and media file under --out.

    python3 scripts/fake_ankiconnect.py [--port 8765] [--out /tmp/fake-anki]
    adb reverse tcp:8765 tcp:8765      # then set Genkan's address to 127.0.0.1
"""
import argparse
import base64
import json
import os
import re
from http.server import BaseHTTPRequestHandler, HTTPServer

LAPIS_FIELDS = [
    "Expression", "ExpressionFurigana", "ExpressionReading", "ExpressionAudio", "SelectionText",
    "MainDefinition", "DefinitionPicture", "Sentence", "SentenceFurigana", "SentenceAudio", "Picture",
    "Glossary", "Hint", "IsWordAndSentenceCard", "IsClickCard", "IsSentenceCard", "PitchPosition",
    "PitchCategories", "Frequency", "FreqSort", "MiscInfo",
]
DECKS = ["Default", "Mining"]
notes = []


def action(name, params, out):
    if name == "version":
        return 6
    if name == "deckNames":
        return DECKS
    if name == "modelNames":
        return ["Basic", "Lapis"]
    if name == "modelFieldNames":
        if params.get("modelName") != "Lapis":
            raise ValueError("model was not found: %s" % params.get("modelName"))
        return LAPIS_FIELDS
    if name == "findNotes":
        query = params.get("query", "")
        deck = re.search(r'"deck:([^"]*)"', query)
        field = re.search(r'"(\w+):((?:[^"\\]|\\.)*)"$', query)
        return [n["id"] for n in notes
                if (not deck or n["deckName"] == deck.group(1))
                and (not field or n["fields"].get(field.group(1)) == field.group(2).replace('\\"', '"'))]
    if name == "storeMediaFile":
        path = os.path.join(out, "media", os.path.basename(params["filename"]))
        with open(path, "wb") as f:
            f.write(base64.b64decode(params["data"]))
        return params["filename"]
    if name == "addNote":
        note = params["note"]
        if note["deckName"] not in DECKS:
            raise ValueError("deck was not found: %s" % note["deckName"])
        first = note["fields"].get("Expression")
        if any(n["deckName"] == note["deckName"] and n["fields"].get("Expression") == first for n in notes):
            raise ValueError("cannot create note because it is a duplicate")
        note = dict(note, id=1700000000000 + len(notes))
        notes.append(note)
        with open(os.path.join(out, "note-%d.json" % note["id"]), "w") as f:
            json.dump(note, f, ensure_ascii=False, indent=1)
        return note["id"]
    raise ValueError("unsupported action")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=8765)
    p.add_argument("--out", default="/tmp/fake-anki")
    a = p.parse_args()
    os.makedirs(os.path.join(a.out, "media"), exist_ok=True)

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            try:
                reply = {"result": action(request["action"], request.get("params", {}), a.out), "error": None}
            except Exception as e:  # AnkiConnect reports every failure the same way
                reply = {"result": None, "error": str(e)}
            print("fake_ankiconnect: %s -> %s" % (request["action"], reply["error"] or "ok"), flush=True)
            body = json.dumps(reply, ensure_ascii=False).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *args):
            pass

    print("fake_ankiconnect: serving http://%s:%d, notes in %s" % (a.host, a.port, a.out), flush=True)
    HTTPServer((a.host, a.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
