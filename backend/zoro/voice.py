"""Natural voice: Turkish text to 24 kHz 16-bit mono PCM, streamed.

Uses the Speech API (gpt-4o-mini-tts), which reads the text verbatim. The
Realtime models were tried on 2026-10-31 and rejected for this: they
paraphrase ("Tabii, şimdi okuyorum: ...") instead of reading.
"""

from __future__ import annotations

import base64
import json
from typing import Any, Iterator

SAMPLE_RATE = 24000
VOICE_INSTRUCTIONS = (
    "Türkçe konuş. Sıcak, sakin ve saygılı bir kişisel asistan sesiyle, acele etmeden, "
    "doğal bir hızda oku. Saatleri ve sayıları Türkçe söyle."
)


class VoiceUnavailable(Exception):
    """The speech model could not be reached or returned an error."""


def speech_events(client: Any, model: str, voice: str, text: str, timeout: float) -> Iterator[tuple[str, Any]]:
    """Yield ("audio", bytes) chunks, then ("usage", dict) once at the end."""
    try:
        manager = client.audio.speech.with_streaming_response.create(
            model=model, voice=voice, input=text, instructions=VOICE_INSTRUCTIONS,
            response_format="pcm", stream_format="sse", timeout=timeout,
        )
        with manager as response:
            for line in response.iter_lines():
                if not line.startswith("data:"):
                    continue
                try:
                    event = json.loads(line[5:])
                except ValueError:
                    continue  # "data: [DONE]"
                kind = event.get("type")
                if kind == "speech.audio.delta" and event.get("audio"):
                    yield "audio", base64.b64decode(event["audio"])
                elif kind == "speech.audio.done":
                    yield "usage", event.get("usage") or {}
    except VoiceUnavailable:
        raise
    except Exception as error:
        raise VoiceUnavailable(f"{type(error).__name__}: {error}") from error
