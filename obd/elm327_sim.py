#!/usr/bin/env python3
"""Symulator modułu OBD (ELM327) do prób Kokpitu bez auta.

Udaje moduł Bluetooth „V-LINK” wpięty w BMW F11 z silnikiem Diesla: te same polecenia, ten sam układ
odpowiedzi i te same obsługiwane parametry co w zapisie z auta (2026-11-21), tylko po TCP zamiast Bluetooth.
Wartości na żywo liczy prosty model jazdy. VIN i numery sterowników są zmyślone.

    ./elm327_sim.py                      # jazda w kółko: postój, miasto, trasa
    ./elm327_sim.py --scenario cold      # zimny silnik, rozgrzewa się
    ./elm327_sim.py --scenario regen     # wypalanie filtra cząstek (gorące spaliny)
    ./elm327_sim.py --dtc P0401,P2002    # zapisane usterki;  --pending P0299  oczekujące
    ./elm327_sim.py --port 35000

W trakcie pracy przyjmuje polecenia z klawiatury: dtc P0401 | pending P0299 | clear | scenario drive|idle|cold|regen
| volt 11.8 | temp 115 | stan

Emulator Androida: adres 10.0.2.2:35000 (albo `adb reverse tcp:35000 tcp:35000` i 127.0.0.1:35000).
"""
import argparse
import math
import socket
import sys
import threading
import time

VIN = "WBAXX00000SIM0001"          # zmyślony
CALID = ["SIM0816D", "SIMF4800"]    # silnik, skrzynia
NAMES = ["ECM\x00-EngineControl\x00\x00", "TCM\x00-TransmisCtrl\x00\x00\x00"]

# parametry trybu 01 obsługiwane przez sterownik silnika (jak w aucie)
ENGINE_PIDS = [0x01, 0x04, 0x05, 0x0C, 0x0D, 0x0F, 0x10, 0x11, 0x1C, 0x1F, 0x21, 0x30, 0x31, 0x33, 0x41, 0x42, 0x45,
               0x46, 0x49, 0x4A, 0x69, 0x6D, 0x70, 0x78, 0x7A, 0x8B, 0x8C]
GEARBOX_PIDS = [0x01, 0x04, 0x05, 0x0C, 0x0D, 0x11, 0x21, 0x41, 0x42]


class Car:
    """Model auta: stan zależny od scenariusza i czasu."""

    def __init__(self, scenario):
        self.lock = threading.Lock()
        self.t0 = time.time()
        self.scenario = scenario
        self.coolant = 20.0 if scenario == "cold" else 88.0
        self.dtc, self.pending = [], []
        self.volt = None          # wymuszone napięcie
        self.temp = None          # wymuszona temperatura płynu
        self.km = 0.0
        self.last = time.time()
        self.rpm = 780.0
        self.speed = 0.0

    def step(self):
        now = time.time()
        dt = min(now - self.last, 2.0)
        self.last = now
        t = now - self.t0
        sc = self.scenario
        if sc in ("idle", "cold"):
            target_v = 0.0
        elif sc == "regen":
            target_v = 90.0 + 10 * math.sin(t / 9)
        else:
            # pętla 150 s: postój 15 s, miasto do 50, światła, trasa do 120, zjazd
            p = t % 150
            if p < 15: target_v = 0
            elif p < 50: target_v = 50 + 8 * math.sin(t / 3)
            elif p < 60: target_v = 0
            elif p < 125: target_v = 118 + 6 * math.sin(t / 7)
            else: target_v = 30
        acc = max(-9.0, min(7.0, (target_v - self.speed) * 0.8))   # km/h na sekundę
        self.speed = max(0.0, self.speed + acc * dt)
        self.acc = acc
        v = self.speed
        gear_ratio = [0, 118, 68, 46, 36, 29, 24, 19, 16]           # obr./min na km/h (8 biegów)
        gear = 0 if v < 3 else next((g for g in range(1, 9) if v * gear_ratio[g] < (2600 if acc > 2 else 1900)), 8)
        self.gear = gear
        target_rpm = 780 + 15 * math.sin(t * 2.1) if gear == 0 else max(900, v * gear_ratio[gear])
        self.rpm += (target_rpm - self.rpm) * min(1.0, dt * 4)
        self.load = max(8.0, min(100.0, 18 + v * 0.22 + max(0.0, acc) * 11 + (20 if sc == "regen" else 0)))
        self.km += v * dt / 3600
        warm_to = 90.0 + (4 if v > 100 else 0)
        self.coolant += (warm_to - self.coolant) * dt / (140 if v < 5 else 60)
        self.boost = 100 + max(0.0, self.load - 22) * 1.9          # kPa bezwzględne
        self.maf = self.rpm / 1000 * (14 + self.load * 0.9)
        self.egt = 120 + self.load * 4.2 + (330 if sc == "regen" else 0)
        self.rail = 300 + self.load * 13                           # bar

    def snapshot(self):
        with self.lock:
            self.step()
            c = self.temp if self.temp is not None else self.coolant
            volt = self.volt if self.volt is not None else 14.2 + 0.05 * math.sin(time.time() / 4)
            return dict(rpm=self.rpm, speed=self.speed, load=self.load, coolant=c, boost=self.boost, maf=self.maf,
                        egt=self.egt, rail=self.rail, volt=volt, run=int(time.time() - self.t0) + 600, km=self.km,
                        gear=self.gear, pedal=min(100.0, 14 + max(0.0, self.acc) * 9 + self.speed * 0.12))


def u16(v):
    v = max(0, min(65535, int(round(v))))
    return [v >> 8, v & 0xFF]


def supported(pids, base):
    bits = 0
    for p in pids:
        if base < p <= base + 0x20:
            bits |= 1 << (32 - (p - base))
    if any(p > base + 0x20 for p in pids):
        bits |= 1
    return [(bits >> s) & 0xFF for s in (24, 16, 8, 0)]


def dtc_bytes(code):
    kind = "PCBU".index(code[0].upper())
    n = int(code[1:], 16)
    return [(kind << 6) | (n >> 8), n & 0xFF]


def mode01(car, ecu, pid):
    """Dane parametru (bez 41 PID) albo None, gdy sterownik go nie obsługuje. ecu: 0 silnik, 1 skrzynia."""
    pids = ENGINE_PIDS if ecu == 0 else GEARBOX_PIDS
    if pid in (0x00, 0x20, 0x40, 0x60, 0x80):
        if pid and not any(p > pid for p in pids): return None
        return supported(pids, pid)
    if pid not in pids: return None
    s = car.snapshot()
    n = len(car.dtc) if ecu == 0 else 0
    eng = {
        0x01: [(0x80 if n else 0) | n, 0x0F, 0xEA, 0x00],
        0x04: [int(s["load"] * 2.55)],
        0x05: [int(s["coolant"] + 40)],
        0x0C: u16(s["rpm"] * 4),
        0x0D: [int(s["speed"])],
        0x0F: [int(18 + s["load"] * 0.15 + 40)],
        0x10: u16(s["maf"] * 100),
        0x11: [int(min(255, 228 - s["load"] * 0.5))],
        0x1C: [0x06],
        0x1F: u16(s["run"]),
        0x21: u16(12 if n else 0),
        0x30: [0xFF],
        0x31: u16(10140 + s["km"]),
        0x33: [98],
        0x41: [0x00, 0x1F, 0xEA, 0x6A],
        0x42: u16((s["volt"] + 0.9) * 1000),
        0x45: [0x00],
        0x46: [41],
        0x49: [int(s["pedal"] * 2.55)],
        0x4A: [int(s["pedal"] * 2.55) + 1],
        0x69: [0x03, 0, 0, 0, 0, 0, 0],
        0x6D: [0x02, 0, 0] + u16(s["rail"] * 10) + [0, 0, 0, 0, 0, 0],
        0x70: [0x02, 0, 0] + u16(s["boost"] * 32) + [0, 0, 0, 0, 0],
        0x78: [0x03] + u16((s["egt"] + 40) * 10) + u16((s["egt"] * 0.93 + 40) * 10) + [0, 0, 0, 0],
        0x7A: [0x01] + u16(50 + s["load"] * 9) + [0, 0, 0, 0],
        0x8B: [0x1F, 0x00, 0xFF, 0, 0, 0, 0],
        0x8C: [0x30] + [0] * 8 + [0xBB, 0x55, 0xB7, 0x12, 0, 0, 0, 0],
    }
    if ecu == 0: return eng[pid]
    box = {0x01: [0x00, 0x04, 0x00, 0x00], 0x04: eng[0x04], 0x05: eng[0x05], 0x0C: u16(s["rpm"] * 4 - 2), 0x0D: eng[0x0D],
           0x11: [max(0, eng[0x11][0] - 1)], 0x21: [0, 0], 0x41: [0x00, 0x04, 0x00, 0x00], 0x42: u16(s["volt"] * 1000 + 880)}
    return box[pid]


def answer(car, data):
    """Zapytanie OBD (bajty) → lista (numer sterownika, bajty odpowiedzi)."""
    mode = data[0]
    out = []
    if mode == 0x01 and len(data) >= 2:
        for ecu in (0, 1):
            d = mode01(car, ecu, data[1])
            if d is not None: out.append((ecu, [0x41, data[1]] + d))
    elif mode in (0x03, 0x07, 0x0A):
        codes = car.dtc if mode == 0x03 else car.pending if mode == 0x07 else car.dtc
        body = [b for c in codes for b in dtc_bytes(c)]
        if mode != 0x0A: out.append((0, [mode + 0x40, len(codes)] + body))
        out.append((1, [mode + 0x40, 0]))
    elif mode == 0x04:
        car.dtc, car.pending = [], []
        out = [(0, [0x44]), (1, [0x44])]
    elif mode == 0x09 and len(data) >= 2:
        pid = data[1]
        if pid == 0x00: out = [(0, [0x49, 0, 0x54, 0x62, 0, 0]), (1, [0x49, 0, 0x14, 0x40, 0, 0])]
        elif pid == 0x02: out = [(0, [0x49, 2, 1] + list(VIN.encode()))]
        elif pid == 0x04: out = [(e, [0x49, 4, 1] + list(CALID[e].ljust(16, "\x00").encode())) for e in (0, 1)]
        elif pid == 0x0A: out = [(e, [0x49, 0x0A, 1] + list(NAMES[e].ljust(20, "\x00").encode())) for e in (0, 1)]
    elif mode == 0x22 and data[1:3] == [0xF1, 0x90]:
        out = [(0, [0x62, 0xF1, 0x90] + list(VIN.encode())), (1, [0x7F, 0x22, 0x22])]
    return out


class Elm:
    """Jedno połączenie: stan ustawień modułu (echo, nagłówki, odstępy) i układanie odpowiedzi w ramki CAN."""

    def __init__(self, car):
        self.car = car
        self.reset()

    def reset(self):
        self.echo, self.headers, self.spaces, self.linefeed = True, False, True, False
        self.first = True

    def frames(self, ecu, payload):
        ident = "7E%X" % (8 + ecu)
        if len(payload) <= 7:
            rows = [[len(payload)] + payload]
        else:
            rows = [[0x10 | (len(payload) >> 8), len(payload) & 0xFF] + payload[:6]]
            rest, n = payload[6:], 1
            while rest:
                rows.append([0x20 | (n & 0x0F)] + (rest[:7] + [0xAA] * 7)[:7])
                rest, n = rest[7:], n + 1
        sep = " " if self.spaces else ""
        lines = []
        for r in rows:
            if self.headers:
                lines.append(sep.join([ident] + ["%02X" % b for b in r]))
            elif len(rows) == 1:
                lines.append(sep.join("%02X" % b for b in r[1:]))
            else:
                lines.append(("%X: " % (r[0] & 0x0F)) + sep.join("%02X" % b for b in r[(2 if r[0] & 0xF0 == 0x10 else 1):]))
        return lines

    def handle(self, raw):
        cmd = raw.replace(" ", "").upper()
        lines = []
        if cmd.startswith("AT") or cmd.startswith("ST") or cmd.startswith("VT"):
            c = cmd[2:] if cmd.startswith("AT") else cmd
            if c == "Z": self.reset(); time.sleep(0.8); lines = ["", "", "ELM327 v2.2"]
            elif c in ("D", "WS"): self.reset(); lines = ["OK"] if c == "D" else ["", "", "ELM327 v2.2"]
            elif c == "I": lines = ["ELM327 v2.2"]
            elif c == "@1": lines = ["OBDII to RS232 Interpreter"]
            elif c == "VTI": lines = ["Icar01 v2.2"]
            elif c == "RV": lines = ["%.1fV" % self.car.snapshot()["volt"]]
            elif c == "DP": lines = ["AUTO, ISO 15765-4 (CAN 11/500)"]
            elif c == "DPN": lines = ["A6"]
            elif c == "IGN": lines = ["ON"]
            elif c in ("E0", "E1"): self.echo = c == "E1"; lines = ["OK"]
            elif c in ("H0", "H1"): self.headers = c == "H1"; lines = ["OK"]
            elif c in ("S0", "S1"): self.spaces = c == "S1"; lines = ["OK"]
            elif c in ("L0", "L1"): self.linefeed = c == "L1"; lines = ["OK"]
            elif c[:2] in ("SP", "TP", "AT", "ST", "SH", "CE", "CR", "FC", "AR", "CA", "M0", "AL", "CF", "CM"): lines = ["OK"]
            else: lines = ["?"]
        else:
            try:
                data = [int(cmd[i:i + 2], 16) for i in range(0, len(cmd), 2)]
            except ValueError:
                data = []
            if not data or len(cmd) % 2:
                lines = ["?"]
            else:
                res = answer(self.car, data)
                time.sleep(0.06)
                if self.first: lines.append("SEARCHING..."); self.first = False; time.sleep(0.2)
                if not res: time.sleep(0.2); lines.append("NO DATA")
                for ecu, payload in res: lines += self.frames(ecu, payload)
        nl = "\r\n" if self.linefeed else "\r"
        return (raw + nl if self.echo else "") + nl.join(lines) + nl + nl + ">"


def serve(conn, addr, car):
    print("połączenie:", addr, flush=True)
    elm, buf = Elm(car), b""
    try:
        while True:
            chunk = conn.recv(256)
            if not chunk: break
            buf += chunk
            while b"\r" in buf:
                line, buf = buf.split(b"\r", 1)
                text = line.decode("ascii", "ignore").strip()
                if text: conn.sendall(elm.handle(text).encode())
    except OSError:
        pass
    finally:
        conn.close()
        print("rozłączono:", addr, flush=True)


def console(car):
    for line in sys.stdin:
        w = line.split()
        if not w: continue
        try:
            with car.lock:
                if w[0] == "dtc": car.dtc += [c.upper() for c in w[1:]]
                elif w[0] == "pending": car.pending += [c.upper() for c in w[1:]]
                elif w[0] == "clear": car.dtc, car.pending = [], []
                elif w[0] == "scenario": car.scenario = w[1]
                elif w[0] == "volt": car.volt = float(w[1]) if len(w) > 1 else None
                elif w[0] == "temp": car.temp = float(w[1]) if len(w) > 1 else None
            if w[0] == "stan": print({k: round(v, 1) for k, v in car.snapshot().items()}, car.dtc, car.pending)
            else: print("ok")
        except (ValueError, IndexError) as e:
            print("błąd:", e)


def main():
    ap = argparse.ArgumentParser(description="Symulator modułu OBD (ELM327) po TCP")
    ap.add_argument("--port", type=int, default=35000)
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--scenario", default="drive", choices=["drive", "idle", "cold", "regen"])
    ap.add_argument("--dtc", default="")
    ap.add_argument("--pending", default="")
    a = ap.parse_args()
    car = Car(a.scenario)
    car.dtc = [c.upper() for c in a.dtc.split(",") if c]
    car.pending = [c.upper() for c in a.pending.split(",") if c]
    threading.Thread(target=console, args=(car,), daemon=True).start()
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((a.host, a.port))
    srv.listen(2)
    print(f"symulator OBD: port {a.port}, scenariusz {a.scenario}, usterki {car.dtc or 'brak'}", flush=True)
    while True:
        conn, addr = srv.accept()
        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        threading.Thread(target=serve, args=(conn, addr, car), daemon=True).start()


if __name__ == "__main__":
    main()
