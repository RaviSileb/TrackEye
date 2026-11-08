#!/usr/bin/env python3
"""TrackEye for Linux (Ubuntu) - eye & micro-gesture control.

Privacy-first: all video processing is local. No network use.
Face/eye detection uses OpenCV Haar cascades (python3-opencv).
Without a camera or OpenCV the app still runs in demo mode
(mouse/touch drives the gaze dot) so every feature can be tried.
System-wide click/scroll/back is optional via xdotool (X11).
"""
import json
import math
import os
import shutil
import subprocess
import sys
import time

import gi

gi.require_version("Gtk", "3.0")
from gi.repository import GLib, Gtk, Gdk, GdkPixbuf  # noqa: E402

try:
    import cv2
    import numpy as np
except Exception:  # pragma: no cover - handled at runtime
    cv2 = None
    np = None

CONFIG_DIR = os.path.join(os.path.expanduser("~"), ".config", "trackeye")
CONFIG_PATH = os.path.join(CONFIG_DIR, "profiles.json")
CASCADE_DIRS = [
    "/usr/share/opencv4/haarcascades",
    "/usr/share/opencv/haarcascades",
]

MODES = ["KURZOR", "SCROLL", "KLAVESNICE", "SCAN"]
DEFAULT_ACTIONS = {
    "BLINK": "click", "DOUBLE_BLINK": "back", "LONG_BLINK": "activate",
    "BROW_RAISE": "switch_mode", "HEAD_UP": "scroll_up", "HEAD_DOWN": "scroll_down",
    "HEAD_LEFT": "back", "HEAD_RIGHT": "forward", "DWELL": "click",
}


def default_settings():
    return {
        "dwell_ms": 800, "blink_threshold": 0.25, "confidence": 0.6,
        "tilt_sensitivity": 1.0, "overlay": True, "dynamic_fps": True,
        "hold_to_activate": False, "system_control": False,
    }


def default_calibration():
    return {"cx": 0.5, "cy": 0.5, "rx": 0.18, "ry": 0.14, "brow": 0.0}


def preset_profiles():
    chat = {"calibration": default_calibration(), "settings": {**default_settings(), "dwell_ms": 700}, "actions": dict(DEFAULT_ACTIONS)}
    browser = {"calibration": default_calibration(), "settings": {**default_settings(), "dwell_ms": 900}, "actions": dict(DEFAULT_ACTIONS)}
    edu = {"calibration": default_calibration(), "settings": {**default_settings(), "dwell_ms": 1200, "hold_to_activate": True}, "actions": dict(DEFAULT_ACTIONS)}
    return {"Telefon – chat": chat, "PC – prohlížeč": browser, "Tablet – vzdělávání": edu}


class BlinkDetector:
    """Eyes visible -> not visible briefly = blink (Haar eye loss)."""

    def __init__(self):
        self.closed_since = None
        self.last_end = -10.0

    def on_frame(self, eyes_visible, now):
        if not eyes_visible:
            if self.closed_since is None:
                self.closed_since = now
            return None
        if self.closed_since is None:
            return None
        duration = now - self.closed_since
        self.closed_since = None
        if duration < 0.06:
            return None
        if duration >= 0.6:
            self.last_end = now
            return "LONG_BLINK"
        double = now - self.last_end <= 0.6
        self.last_end = now
        return "DOUBLE_BLINK" if double else "BLINK"


class DwellDetector:
    def __init__(self, radius=0.06):
        self.anchor = None
        self.since = 0.0
        self.fired = False
        self.radius = radius

    def on_point(self, point, now, dwell_ms):
        if self.anchor is None or math.hypot(point[0] - self.anchor[0], point[1] - self.anchor[1]) > self.radius:
            self.anchor = point
            self.since = now
            self.fired = False
            return None
        if not self.fired and (now - self.since) * 1000 >= dwell_ms:
            self.fired = True
            return "DWELL"
        return None

    def progress(self, point, now, dwell_ms):
        if self.anchor is None or math.hypot(point[0] - self.anchor[0], point[1] - self.anchor[1]) > self.radius:
            return 0.0
        return max(0.0, min(1.0, (now - self.since) * 1000 / max(1, dwell_ms)))


class HeadGestureDetector:
    def __init__(self, threshold=0.09, refractory=0.7):
        self.threshold = threshold
        self.refractory = refractory
        self.last_fired = 0.0
        self.armed = True

    def on_point(self, point, calib, now, sensitivity):
        dx = point[0] - calib["cx"]
        dy = point[1] - calib["cy"]
        t = self.threshold / max(0.2, sensitivity)
        gesture = None
        if dy > t:
            gesture = "HEAD_DOWN"
        elif dy < -t:
            gesture = "HEAD_UP"
        elif dx > t:
            gesture = "HEAD_RIGHT"
        elif dx < -t:
            gesture = "HEAD_LEFT"
        if gesture is None:
            self.armed = True
            return None
        if not self.armed or now - self.last_fired < self.refractory:
            return None
        self.armed = False
        self.last_fired = now
        return gesture


class ProfileStore:
    def __init__(self):
        self.data = {"profiles": {}, "active": None, "stats": {"clicks": 0, "gestures": 0, "training_hits": 0}}
        self.load()

    def load(self):
        try:
            with open(CONFIG_PATH, encoding="utf-8") as fh:
                loaded = json.load(fh)
            if isinstance(loaded, dict):
                self.data.update(loaded)
        except Exception:
            pass

    def save(self):
        os.makedirs(CONFIG_DIR, exist_ok=True)
        with open(CONFIG_PATH, "w", encoding="utf-8") as fh:
            json.dump(self.data, fh, ensure_ascii=False, indent=2)

    def all_profiles(self):
        merged = preset_profiles()
        merged.update(self.data.get("profiles", {}))
        return merged


def find_cascade(name):
    if cv2 is None:
        return None
    candidates = [os.path.join(d, name) for d in CASCADE_DIRS]
    data_dir = getattr(cv2, "data", None)
    if data_dir is not None:
        candidates.append(os.path.join(data_dir.haarcascades, name))
    for path in candidates:
        if os.path.exists(path):
            return path
    return None


class TrackEyeApp(Gtk.Window):
    def __init__(self):
        super().__init__(title="TrackEye — ovládání pohledem a gesty")
        self.set_default_size(980, 760)
        self.connect("destroy", Gtk.main_quit)

        self.store = ProfileStore()
        profiles = self.store.all_profiles()
        active_name = self.store.data.get("active") or "PC – prohlížeč"
        profile = profiles.get(active_name, preset_profiles()["PC – prohlížeč"])
        self.calibration = dict(profile["calibration"])
        self.settings = dict(profile["settings"])
        self.actions = dict(profile["actions"])
        self.active_profile_name = active_name

        self.mode = "KURZOR"
        self.control_active = True
        self.gaze = (0.5, 0.5)
        self.smooth = (0.5, 0.5)
        self.has_face = False
        self.session_start = time.time()
        self.scan_index = 0
        self.train_hits = 0
        self.calib_step = 0
        self.calib_samples = []
        self.last_move = time.time()
        self.last_frame = 0.0

        self.blink_detector = BlinkDetector()
        self.dwell_detector = DwellDetector()
        self.head_detector = HeadGestureDetector()

        self.face_cascade = self._load_cascade("haarcascade_frontalface_default.xml")
        self.eye_cascade = self._load_cascade("haarcascade_eye.xml")
        self.capture = None
        if cv2 is not None:
            cap = cv2.VideoCapture(0)
            if cap is not None and cap.isOpened():
                self.capture = cap
            elif cap is not None:
                cap.release()

        self._build_ui()
        GLib.timeout_add(33, self._tick)
        GLib.timeout_add(1200, self._scan_tick)

    def _load_cascade(self, name):
        path = find_cascade(name)
        if path and cv2 is not None:
            cascade = cv2.CascadeClassifier(path)
            return None if cascade.empty() else cascade
        return None

    # ---------------- UI ----------------
    def _build_ui(self):
        root = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        root.set_margin_top(10); root.set_margin_bottom(10)
        root.set_margin_start(10); root.set_margin_end(10)
        self.add(root)

        title = Gtk.Label(label="<b><big>TrackEye</big></b>", use_markup=True, xalign=0)
        root.pack_start(title, False, False, 0)
        self.status_label = Gtk.Label(label=self._initial_status(), xalign=0)
        self.status_label.set_line_wrap(True)
        root.pack_start(self.status_label, False, False, 0)

        self.video_area = Gtk.DrawingArea()
        self.video_area.set_size_request(640, 300)
        self.video_area.connect("draw", self._draw_video)
        self.video_area.set_events(Gdk.EventMask.POINTER_MOTION_MASK | Gdk.EventMask.BUTTON_PRESS_MASK)
        self.video_area.connect("motion-notify-event", self._on_demo_motion)
        root.pack_start(self.video_area, False, False, 0)

        self.mode_label = Gtk.Label(label="Režim: KURZOR", xalign=0)
        root.pack_start(self.mode_label, False, False, 0)

        modes_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=6)
        for mode_name, label in [("KURZOR", "Kurzor"), ("SCROLL", "Scroll"), ("KLAVESNICE", "Klávesnice"), ("SCAN", "Scan && Select")]:
            btn = Gtk.Button(label=label)
            btn.connect("clicked", lambda _b, m=mode_name: self._set_mode(m))
            modes_box.pack_start(btn, True, True, 0)
        root.pack_start(modes_box, False, False, 0)

        self.activate_btn = Gtk.Button(label="Aktivní ovládání: ZAP (dlouhé mrknutí přepne)")
        self.activate_btn.connect("clicked", lambda _b: self._toggle_active())
        root.pack_start(self.activate_btn, False, False, 0)

        demo_label = Gtk.Label(label="<b>Testovací plocha</b> (klikněte pohledem / dwell / mrknutím)", use_markup=True, xalign=0)
        root.pack_start(demo_label, False, False, 0)
        demo_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=6)
        self.demo_buttons = []
        for name in ["Cíl A", "Cíl B", "Cíl C"]:
            btn = Gtk.Button(label=name)
            btn.set_size_request(-1, 56)
            btn.connect("clicked", lambda _b, n=name: self._log(f"Kliknuto: {n}"))
            self.demo_buttons.append(btn)
            demo_box.pack_start(btn, True, True, 0)
        root.pack_start(demo_box, False, False, 0)

        self.scan_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        self.scan_buttons = []
        scan_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=6)
        for i, name in enumerate(["Klik", "Zpět", "Scroll ↓", "Domů"]):
            btn = Gtk.Button(label=name)
            btn.set_size_request(-1, 56)
            btn.connect("clicked", lambda _b, idx=i: self._scan_selected(idx))
            self.scan_buttons.append(btn)
            scan_row.pack_start(btn, True, True, 0)
        self.scan_box.pack_start(Gtk.Label(label="Scan & Select — mrknutím potvrďte zvýrazněnou volbu", xalign=0), False, False, 0)
        self.scan_box.pack_start(scan_row, False, False, 0)
        self.scan_box.set_no_show_all(True)
        root.pack_start(self.scan_box, False, False, 0)

        self.keyboard_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        self.keyboard_entry = Gtk.Entry()
        self.keyboard_entry.set_placeholder_text("Text psaný pohledem…")
        self.keyboard_box.pack_start(self.keyboard_entry, False, False, 0)
        keys_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=6)
        for label in ["A", "B", "C", "␣", "⌫"]:
            btn = Gtk.Button(label=label)
            btn.connect("clicked", lambda _b, k=label: self._type_key(k))
            keys_row.pack_start(btn, True, True, 0)
        self.keyboard_box.pack_start(keys_row, False, False, 0)
        self.keyboard_box.set_no_show_all(True)
        root.pack_start(self.keyboard_box, False, False, 0)

        self.log_label = Gtk.Label(label="Události gest se zobrazí zde.", xalign=0)
        self.log_label.set_line_wrap(True)
        root.pack_start(self.log_label, False, False, 0)

        notebook = Gtk.Notebook()
        notebook.append_page(self._calibration_tab(), Gtk.Label(label="Kalibrace"))
        notebook.append_page(self._profiles_tab(), Gtk.Label(label="Profily"))
        notebook.append_page(self._training_tab(), Gtk.Label(label="Trénink"))
        notebook.append_page(self._stats_tab(), Gtk.Label(label="Statistiky"))
        notebook.append_page(self._settings_tab(), Gtk.Label(label="Nastavení"))
        root.pack_start(notebook, True, True, 0)

        privacy = Gtk.Label(label="Soukromí: video se zpracovává pouze na tomto počítači. Aplikace nepoužívá síť.", xalign=0)
        root.pack_start(privacy, False, False, 0)
        self.show_all()
        self.scan_box.hide()
        self.keyboard_box.hide()

    def _initial_status(self):
        if self.capture is not None and self.face_cascade is not None:
            return "Kamera běží. Zpracování je pouze lokální."
        if cv2 is None:
            return "Demo režim: chybí python3-opencv — gaze řídíte myší nad videem. Nainstalujte .deb závislosti."
        if self.capture is None:
            return "Demo režim: kamera nenalezena — gaze řídíte myší nad videem."
        return "Demo režim: Haar kaskády nenalezeny — gaze řídíte myší nad videem."

    def _calibration_tab(self):
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        box.pack_start(Gtk.Label(label="Rychlá kalibrace: dívejte se do středu, vlevo, vpravo, nahoru, dolů (v demo režimu tam přesuňte myš).", xalign=0), False, False, 0)
        self.calib_label = Gtk.Label(label="Krok: STŘED", xalign=0)
        box.pack_start(self.calib_label, False, False, 0)
        capture = Gtk.Button(label="Zachytit tento směr")
        capture.connect("clicked", lambda _b: self._capture_calibration())
        box.pack_start(capture, False, False, 0)
        reset = Gtk.Button(label="Reset kalibrace")
        reset.connect("clicked", lambda _b: self._reset_calibration())
        box.pack_start(reset, False, False, 0)
        return box

    def _profiles_tab(self):
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=6)
        for name in preset_profiles():
            btn = Gtk.Button(label=name)
            btn.connect("clicked", lambda _b, n=name: self._apply_profile(n))
            row.pack_start(btn, True, True, 0)
        box.pack_start(row, False, False, 0)
        self.profile_entry = Gtk.Entry()
        self.profile_entry.set_placeholder_text("Název vlastního profilu")
        box.pack_start(self.profile_entry, False, False, 0)
        save = Gtk.Button(label="Uložit aktuální nastavení jako profil")
        save.connect("clicked", lambda _b: self._save_profile())
        box.pack_start(save, False, False, 0)
        self.profile_list_label = Gtk.Label(label="", xalign=0)
        box.pack_start(self.profile_list_label, False, False, 0)
        self._refresh_profile_list()
        return box

    def _training_tab(self):
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        box.pack_start(Gtk.Label(label="Trénink: klikejte na bublinu (pohledem + mrknutí/dwell, nebo myší).", xalign=0), False, False, 0)
        self.train_btn = Gtk.Button(label="🎯 Bublina 0/5 — klikni na mě")
        self.train_btn.set_size_request(-1, 64)
        self.train_btn.connect("clicked", lambda _b: self._train_hit())
        box.pack_start(self.train_btn, False, False, 0)
        self.train_label = Gtk.Label(label="", xalign=0)
        box.pack_start(self.train_label, False, False, 0)
        return box

    def _stats_tab(self):
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        self.stats_label = Gtk.Label(label="", xalign=0)
        box.pack_start(self.stats_label, False, False, 0)
        clear = Gtk.Button(label="Vymazat statistiky")
        clear.connect("clicked", lambda _b: self._clear_stats())
        box.pack_start(clear, False, False, 0)
        self._update_stats()
        return box

    def _settings_tab(self):
        grid = Gtk.Grid(column_spacing=12, row_spacing=8)
        self.dwell_scale = self._add_scale(grid, 0, "Dwell čas [ms] (300–1200)", 300, 1200, self.settings["dwell_ms"])
        self.conf_scale = self._add_scale(grid, 1, "Práh jistoty [%] (10–100)", 10, 100, self.settings["confidence"] * 100)
        self.tilt_scale = self._add_scale(grid, 2, "Citlivost náklonu [%] (50–200)", 50, 200, self.settings["tilt_sensitivity"] * 100)
        self.overlay_check = Gtk.CheckButton(label="Zobrazovat overlay (gaze dot)")
        self.overlay_check.set_active(bool(self.settings["overlay"]))
        self.dynamic_check = Gtk.CheckButton(label="Šetřit baterii (dynamické FPS)")
        self.dynamic_check.set_active(bool(self.settings["dynamic_fps"]))
        self.hold_check = Gtk.CheckButton(label="Hold-to-activate (dlouhé mrknutí zap/vyp)")
        self.hold_check.set_active(bool(self.settings["hold_to_activate"]))
        self.system_check = Gtk.CheckButton(label="Systémové ovládání přes xdotool (jen X11, vyžaduje xdotool)")
        self.system_check.set_active(bool(self.settings["system_control"]))
        for i, widget in enumerate([self.overlay_check, self.dynamic_check, self.hold_check, self.system_check]):
            grid.attach(widget, 0, 3 + i, 2, 1)
        apply_btn = Gtk.Button(label="Použít nastavení")
        apply_btn.connect("clicked", lambda _b: self._apply_settings())
        grid.attach(apply_btn, 0, 7, 2, 1)
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        box.pack_start(grid, False, False, 0)
        return box

    def _add_scale(self, grid, row, label, low, high, value):
        grid.attach(Gtk.Label(label=label, xalign=0), 0, row, 1, 1)
        scale = Gtk.Scale.new_with_range(Gtk.Orientation.HORIZONTAL, low, high, 10)
        scale.set_value(value)
        scale.set_hexpand(True)
        grid.attach(scale, 1, row, 1, 1)
        return scale

    # ---------------- logic ----------------
    def _apply_settings(self):
        self.settings["dwell_ms"] = int(self.dwell_scale.get_value())
        self.settings["confidence"] = self.conf_scale.get_value() / 100.0
        self.settings["tilt_sensitivity"] = self.tilt_scale.get_value() / 100.0
        self.settings["overlay"] = self.overlay_check.get_active()
        self.settings["dynamic_fps"] = self.dynamic_check.get_active()
        self.settings["hold_to_activate"] = self.hold_check.get_active()
        self.settings["system_control"] = self.system_check.get_active()
        self._log("Nastavení použito.")

    def _set_mode(self, mode):
        self.mode = mode
        self.mode_label.set_text(f"Režim: {mode}")
        self.scan_box.set_visible(mode == "SCAN")
        self.keyboard_box.set_visible(mode == "KLAVESNICE")
        self._log(f"Přepnut režim: {mode}")

    def _toggle_active(self):
        self.control_active = not self.control_active
        self.activate_btn.set_label("Aktivní ovládání: ZAP (dlouhé mrknutí přepne)" if self.control_active else "Aktivní ovládání: VYP (dlouhé mrknutí zapne)")

    def _type_key(self, key):
        text = self.keyboard_entry.get_text()
        if key == "⌫":
            self.keyboard_entry.set_text(text[:-1])
        elif key == "␣":
            self.keyboard_entry.set_text(text + " ")
        else:
            self.keyboard_entry.set_text(text + key)
        self.keyboard_entry.set_position(-1)

    def _capture_calibration(self):
        steps = ["STŘED", "VLEVO", "VPRAVO", "NAHORU", "DOLŮ"]
        self.calib_samples.append(self.gaze)
        self.calib_step += 1
        if self.calib_step >= len(steps):
            center = self.calib_samples[0]
            rx = max(0.06, max(abs(s[0] - center[0]) for s in self.calib_samples[1:]))
            ry = max(0.05, max(abs(s[1] - center[1]) for s in self.calib_samples[1:]))
            self.calibration = {"cx": center[0], "cy": center[1], "rx": rx, "ry": ry, "brow": self.calibration.get("brow", 0.0)}
            self.calib_label.set_text("Kalibrace hotová ✓")
            self._log(f"Kalibrace hotová: rozsah x={rx:.2f}, y={ry:.2f}")
            self.calib_step = 0
            self.calib_samples = []
        else:
            self.calib_label.set_text(f"Krok: {steps[self.calib_step]}")

    def _reset_calibration(self):
        self.calibration = default_calibration()
        self.calib_step = 0
        self.calib_samples = []
        self.calib_label.set_text("Krok: STŘED")
        self._log("Kalibrace resetována.")

    def _apply_profile(self, name):
        profile = self.store.all_profiles()[name]
        self.calibration = dict(profile["calibration"])
        self.settings = dict(profile["settings"])
        self.actions = dict(profile["actions"])
        self.active_profile_name = name
        self.store.data["active"] = name
        self.store.save()
        self._log(f"Aktivní profil: {name}")

    def _save_profile(self):
        name = self.profile_entry.get_text().strip()
        if not name:
            self._log("Zadejte název profilu.")
            return
        self.store.data.setdefault("profiles", {})[name] = {
            "calibration": dict(self.calibration), "settings": dict(self.settings), "actions": dict(self.actions),
        }
        self.store.data["active"] = name
        self.store.save()
        self._refresh_profile_list()
        self._log(f"Uložen profil: {name}")

    def _refresh_profile_list(self):
        names = ", ".join(self.store.all_profiles().keys())
        self.profile_list_label.set_text(f"Dostupné profily: {names}")

    def _train_hit(self):
        self.train_hits += 1
        self.store.data["stats"]["training_hits"] = self.store.data["stats"].get("training_hits", 0) + 1
        self.store.save()
        self.train_btn.set_label(f"🎯 Bublina {self.train_hits}/5 — klikni na mě")
        self.train_label.set_text(f"Zásahy: {self.train_hits}/5" + (" — trénink hotový!" if self.train_hits >= 5 else ""))
        self._update_stats()

    def _clear_stats(self):
        self.store.data["stats"] = {"clicks": 0, "gestures": 0, "training_hits": 0}
        self.store.save()
        self.session_start = time.time()
        self._update_stats()

    def _update_stats(self):
        stats = self.store.data.get("stats", {})
        minutes = (time.time() - self.session_start) / 60.0
        clicks = stats.get("clicks", 0)
        gestures = stats.get("gestures", 0)
        if clicks == 0 and minutes > 2:
            tip = "Doporučení: zkuste trénink bublin nebo snižte dwell čas."
        elif gestures > 0 and clicks * 2 < gestures:
            tip = "Doporučení: zvyšte práh jistoty nebo dwell čas (možné falešné detekce)."
        else:
            tip = "Doporučení: nastavení vypadá vyváženě."
        self.stats_label.set_text(
            f"Doba relace: {minutes:.1f} min\nKliky: {clicks}\nGesta celkem: {gestures}\n"
            f"Trénink zásahy: {stats.get('training_hits', 0)}\n{tip}"
        )

    def _log(self, message):
        self.log_label.set_text(message)

    def _scan_tick(self):
        if self.mode == "SCAN" and self.control_active and self.scan_buttons:
            self.scan_index = (self.scan_index + 1) % len(self.scan_buttons)
            for i, btn in enumerate(self.scan_buttons):
                btn.set_opacity(1.0 if i == self.scan_index else 0.45)
        return True

    def _scan_selected(self, index):
        ["click", "back", "scroll_down", "home"][index] and self._perform_action(["click", "back", "scroll_down", "home"][index])

    def _on_gesture(self, gesture):
        if gesture == "LONG_BLINK" and self.settings.get("hold_to_activate"):
            self._toggle_active()
            self._log("Ovládání aktivováno." if self.control_active else "Ovládání pozastaveno.")
            return
        if not self.control_active:
            return
        self.store.data["stats"]["gestures"] = self.store.data["stats"].get("gestures", 0) + 1
        if gesture == "BLINK" and self.mode == "SCAN":
            self._scan_selected(self.scan_index)
            return
        action = self.actions.get(gesture)
        if action:
            self._log(f"Gesto {gesture} → {action}")
            self._perform_action(action)
        self._update_stats()

    def _perform_action(self, action):
        system = bool(self.settings.get("system_control")) and shutil.which("xdotool")
        display = Gdk.Display.get_default()
        seat = display.get_default_seat() if display else None
        pointer = seat.get_pointer() if seat else None
        if action == "click":
            self.store.data["stats"]["clicks"] = self.store.data["stats"].get("clicks", 0) + 1
            self.store.save()
            if system and pointer:
                _screen, x, y = pointer.get_position()
                subprocess.run(["xdotool", "mousemove", str(int(x)), str(int(y)), "click", "1"], check=False)
            else:
                self._click_demo_at_gaze()
        elif action == "back":
            if system:
                subprocess.run(["xdotool", "key", "alt+Left"], check=False)
            else:
                self._log("Zpět (demo): zapněte systémové ovládání pro alt+Left.")
        elif action == "forward":
            if system:
                subprocess.run(["xdotool", "key", "alt+Right"], check=False)
            else:
                self._log("Vpřed (demo).")
        elif action in ("scroll_up", "scroll_down"):
            if system:
                subprocess.run(["xdotool", "click", "4" if action == "scroll_up" else "5"], check=False)
            else:
                self._log("Scroll nahoru (demo)." if action == "scroll_up" else "Scroll dolů (demo).")
        elif action == "switch_mode":
            idx = MODES.index(self.mode)
            self._set_mode(MODES[(idx + 1) % len(MODES)])
        elif action == "activate":
            self._toggle_active()
        elif action == "home":
            if system:
                subprocess.run(["xdotool", "key", "Super_L"], check=False)
            else:
                self._log("Domů (demo).")
        self._update_stats()

    def _click_demo_at_gaze(self):
        allocation = self.video_area.get_allocation()
        gx = self.gaze[0] * allocation.width
        gy = self.gaze[1] * allocation.height
        # Demo targets live below the video; map gaze over the whole window as fallback click log.
        self._log(f"Klik pohledem na ({gx:.0f}, {gy:.0f}) — v demo ploše použijte dwell/blink nad cíli.")
        for btn in self.demo_buttons:
            alloc = btn.get_allocation()
            origin = btn.translate_coordinates(self, 0, 0)
            if origin is None:
                continue
            bx, by = origin
            video_origin = self.video_area.translate_coordinates(self, 0, 0)
            if video_origin is None:
                continue
            px = video_origin[0] + gx
            py = video_origin[1] + gy
            if bx <= px <= bx + alloc.width and by <= py <= by + alloc.height:
                btn.clicked()

    def _on_demo_motion(self, _widget, event):
        if self.capture is None or self.face_cascade is None:
            allocation = self.video_area.get_allocation()
            if allocation.width > 0 and allocation.height > 0:
                self.gaze = (max(0.0, min(1.0, event.x / allocation.width)), max(0.0, min(1.0, event.y / allocation.height)))
                self.has_face = True

    # ---------------- camera loop ----------------
    def _tick(self):
        now = time.time()
        idle = now - self.last_move > 2.5
        min_interval = 0.2 if (self.settings.get("dynamic_fps") and idle) else 0.033
        if now - self.last_frame >= min_interval:
            self.last_frame = now
            self._process_camera(now)
        self.video_area.queue_draw()
        return True

    def _process_camera(self, now):
        if self.capture is None or self.face_cascade is None or cv2 is None:
            return
        ok, frame = self.capture.read()
        if not ok or frame is None:
            return
        frame = cv2.flip(frame, 1)
        self._last_pixbuf = self._frame_to_pixbuf(frame)
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        faces = self.face_cascade.detectMultiScale(gray, 1.2, 6, minSize=(90, 90))
        if len(faces) == 0:
            self.has_face = False
            self.status_label.set_text("Obličej nenalezen — namiřte obličej na kameru a zlepšete osvětlení.")
            return
        x, y, w, h = max(faces, key=lambda r: r[2] * r[3])
        self.has_face = True
        self.status_label.set_text("Tracking aktivní • zpracování pouze lokální")
        cx = (x + w / 2.0) / frame.shape[1]
        cy = (y + h / 2.0) / frame.shape[0]
        calib = self.calibration
        nx = 0.5 + (cx - calib["cx"]) / (2 * max(0.05, calib["rx"]))
        ny = 0.5 + (cy - calib["cy"]) / (2 * max(0.05, calib["ry"]))
        nx = max(0.0, min(1.0, nx)); ny = max(0.0, min(1.0, ny))
        sx, sy = self.smooth
        sx += (nx - sx) * 0.35; sy += (ny - sy) * 0.35
        self.smooth = (sx, sy)
        self.gaze = self.smooth
        if abs(nx - 0.5) + abs(ny - 0.5) > 0.02:
            self.last_move = now

        eyes_visible = True
        if self.eye_cascade is not None:
            roi = gray[y:y + h // 2, x:x + w]
            eyes = self.eye_cascade.detectMultiScale(roi, 1.15, 5, minSize=(18, 18))
            eyes_visible = len(eyes) >= 1

        gesture = self.blink_detector.on_frame(eyes_visible, now)
        if gesture:
            self._on_gesture(gesture)
        head_gesture = self.head_detector.on_point(self.gaze, calib, now, float(self.settings["tilt_sensitivity"]))
        if head_gesture and (self.mode == "SCROLL" or head_gesture in ("HEAD_LEFT", "HEAD_RIGHT")):
            self._on_gesture(head_gesture)
        if self.mode == "KURZOR":
            dwell_gesture = self.dwell_detector.on_point(self.gaze, now, int(self.settings["dwell_ms"]))
            if dwell_gesture:
                self._on_gesture(dwell_gesture)

    def _frame_to_pixbuf(self, frame):
        if cv2 is None or np is None:
            return None
        rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        h, w, channels = rgb.shape
        return GdkPixbuf.Pixbuf.new_from_data(rgb.tobytes(), GdkPixbuf.Colorspace.RGB, False, 8, w, h, w * channels)

    def _draw_video(self, _widget, cr):
        allocation = self.video_area.get_allocation()
        cr.set_source_rgb(0.07, 0.07, 0.14)
        cr.rectangle(0, 0, allocation.width, allocation.height)
        cr.fill()
        pixbuf = getattr(self, "_last_pixbuf", None)
        if pixbuf is not None:
            scaled = pixbuf.scale_simple(allocation.width, allocation.height, GdkPixbuf.InterpType.BILINEAR)
            if scaled is not None:
                Gdk.cairo_set_source_pixbuf(cr, scaled, 0, 0)
                cr.paint()
        cr.set_source_rgb(1, 1, 1)
        cr.set_font_size(13)
        cr.move_to(10, 20)
        cr.show_text("● TRACKING" if self.has_face else "○ STOP (demo: pohybujte myší)")
        if self.settings.get("overlay"):
            gx = self.gaze[0] * allocation.width
            gy = self.gaze[1] * allocation.height
            now = time.time()
            progress = self.dwell_detector.progress(self.gaze, now, int(self.settings["dwell_ms"]))
            if progress > 0:
                cr.set_source_rgb(0.42, 0.39, 1.0)
                cr.set_line_width(4)
                cr.arc(gx, gy, 30, -math.pi / 2, -math.pi / 2 + 2 * math.pi * progress)
                cr.stroke()
            cr.set_source_rgb(0.0, 0.83, 0.65)
            cr.arc(gx, gy, 11, 0, 2 * math.pi)
            cr.fill()
        return False


def main():
    app = TrackEyeApp()
    Gtk.main()
    if app.capture is not None:
        app.capture.release()
    return 0


if __name__ == "__main__":
    sys.exit(main())
