"""Free, key-less information sources: Open-Meteo weather and TCMB exchange rates."""

from __future__ import annotations

import xml.etree.ElementTree as ET
from typing import Any

import httpx

TIMEOUT = httpx.Timeout(6.0, connect=4.0)

# WMO weather interpretation codes -> Turkish
WMO_TR = {
    0: "açık", 1: "çoğunlukla açık", 2: "parçalı bulutlu", 3: "kapalı",
    45: "sisli", 48: "kırağılı sis",
    51: "hafif çisenti", 53: "çisenti", 55: "yoğun çisenti",
    56: "dondurucu çisenti", 57: "yoğun dondurucu çisenti",
    61: "hafif yağmurlu", 63: "yağmurlu", 65: "kuvvetli yağmurlu",
    66: "dondurucu yağmur", 67: "kuvvetli dondurucu yağmur",
    71: "hafif kar yağışlı", 73: "kar yağışlı", 75: "yoğun kar yağışlı", 77: "kar taneli",
    80: "hafif sağanak", 81: "sağanak", 82: "şiddetli sağanak",
    85: "hafif kar sağanağı", 86: "yoğun kar sağanağı",
    95: "gök gürültülü fırtına", 96: "dolulu fırtına", 99: "şiddetli dolulu fırtına",
}


def _get(url: str, params: dict[str, Any] | None = None) -> httpx.Response:
    response = httpx.get(url, params=params, timeout=TIMEOUT, headers={"User-Agent": "Zoro/2.0"})
    response.raise_for_status()
    return response


def weather(city: str, tz: str = "Europe/Istanbul") -> dict[str, Any]:
    geo = _get(
        "https://geocoding-api.open-meteo.com/v1/search",
        {"name": city, "count": 1, "language": "tr", "format": "json"},
    ).json()
    results = geo.get("results") or []
    if not results:
        return {"ok": False, "error": f"'{city}' için konum bulunamadı."}
    place = results[0]
    forecast = _get(
        "https://api.open-meteo.com/v1/forecast",
        {
            "latitude": place["latitude"],
            "longitude": place["longitude"],
            "current": "temperature_2m,apparent_temperature,weather_code,wind_speed_10m",
            "daily": "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max",
            "timezone": tz,
            "forecast_days": 3,
        },
    ).json()
    current = forecast.get("current", {})
    daily = forecast.get("daily", {})
    days = []
    for i, date in enumerate(daily.get("time", [])[:3]):
        days.append({
            "tarih": date,
            "durum": WMO_TR.get(int(daily["weather_code"][i]), "bilinmiyor"),
            "en_yuksek": daily["temperature_2m_max"][i],
            "en_dusuk": daily["temperature_2m_min"][i],
            "yagis_olasiligi": daily.get("precipitation_probability_max", [None] * 3)[i],
        })
    return {
        "ok": True,
        "yer": ", ".join(x for x in [place.get("name"), place.get("admin1"), place.get("country")] if x),
        "simdi": {
            "sicaklik": current.get("temperature_2m"),
            "hissedilen": current.get("apparent_temperature"),
            "durum": WMO_TR.get(int(current.get("weather_code", -1)), "bilinmiyor"),
            "ruzgar_kmh": current.get("wind_speed_10m"),
        },
        "gunler": days,
        "kaynak": "Open-Meteo",
    }


def exchange_rates(codes: list[str] | None = None) -> dict[str, Any]:
    """Today's indicative rates from the Central Bank of Turkey (TCMB)."""
    wanted = {c.upper() for c in (codes or ["USD", "EUR", "GBP"])}
    root = ET.fromstring(_get("https://www.tcmb.gov.tr/kurlar/today.xml").content)
    rates = {}
    for currency in root.findall("Currency"):
        code = currency.get("CurrencyCode", "")
        if code not in wanted:
            continue
        def num(tag: str) -> float | None:
            node = currency.find(tag)
            try:
                return float(node.text) if node is not None and node.text else None
            except ValueError:
                return None
        rates[code] = {
            "birim": int(currency.findtext("Unit") or 1),
            "doviz_alis": num("ForexBuying"),
            "doviz_satis": num("ForexSelling"),
        }
    return {"ok": bool(rates), "tarih": root.get("Tarih"), "kurlar": rates, "kaynak": "TCMB"}
