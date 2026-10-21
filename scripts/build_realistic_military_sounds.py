#!/usr/bin/env python3
"""
Build Realistic, High-Fidelity Military Sound Effects:
Zero cartoon / video-game feel. Authentic military acoustic instruments,
live ordnance explosions, heavy industrial engineering, and RTS tactical communications.
"""

import wave
import numpy as np
import os

SAMPLE_RATE = 44100
OUT_DIR = os.path.join(os.path.dirname(__file__), '../frontend/audio')
os.makedirs(OUT_DIR, exist_ok=True)

def load_wav(path, target_sr=SAMPLE_RATE):
    if not os.path.exists(path):
        raise FileNotFoundError(f"Missing {path}")
    with wave.open(path, 'r') as w:
        ch = w.getnchannels()
        sw = w.getsampwidth()
        sr = w.getframerate()
        frames = w.readframes(w.getnframes())
        
        if sw == 1:
            data = (np.frombuffer(frames, dtype=np.uint8).astype(np.float32) - 128) / 128.0
        elif sw == 2:
            data = np.frombuffer(frames, dtype=np.int16).astype(np.float32) / 32768.0
        elif sw == 3:
            b = np.frombuffer(frames, dtype=np.uint8).reshape(-1, 3)
            raw = (b[:, 0].astype(np.int32) | (b[:, 1].astype(np.int32) << 8) | (b[:, 2].astype(np.int8).astype(np.int32) << 16))
            data = raw.astype(np.float32) / 8388608.0
        elif sw == 4:
            data = np.frombuffer(frames, dtype=np.int32).astype(np.float32) / 2147483648.0
        else:
            raise ValueError(f"Unsupported sample width: {sw}")
        
        if ch > 1:
            data = data.reshape(-1, ch).mean(axis=1) # downmix to mono
            
        if sr != target_sr:
            old_len = len(data)
            new_len = int(round(old_len * target_sr / sr))
            indices = np.linspace(0, old_len - 1, new_len)
            data = np.interp(indices, np.arange(old_len), data)
            
        return data, target_sr

def save_wav(filename, data, sr=SAMPLE_RATE, peak_limit=0.92):
    peak = np.max(np.abs(data))
    if peak > 0:
        data = data * (peak_limit / peak)
    int_data = np.clip(data * 32767.0, -32768, 32767).astype(np.int16)
    out_path = os.path.join(OUT_DIR, filename)
    with wave.open(out_path, 'w') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(int_data.tobytes())
    print(f"Generated {out_path} ({len(data)/sr:.2f}s)")

# -------------------------------------------------------------
# 1. Dispatch Sound (部队出征)
# -------------------------------------------------------------
# Option A: Ceremonial Military Band & Drum Cadence (Adjutant's Call opening)
def build_dispatch_a():
    adjutant, sr = load_wav('/tmp/adjutant.wav')
    dur = 2.4
    seg = adjutant[:int(dur * sr)].copy()
    # Smooth fade out at tail (200ms)
    fade_len = int(0.20 * sr)
    seg[-fade_len:] *= np.linspace(1.0, 0.0, fade_len)
    save_wav('dispatch.wav', seg, sr)

# Option B: Armored Tank Convoy Mobilization (Heavy Diesel Engine + Tracks + Radio Comms)
def build_dispatch_b():
    sr = SAMPLE_RATE
    dur = 2.0
    n = int(dur * sr)
    t = np.arange(n) / sr
    out = np.zeros(n, dtype=np.float32)
    
    # Radio squelch chirp at opening t=0.0s to 0.12s ("Chhk-roger")
    radio_len = int(0.12 * sr)
    r_noise = (np.random.RandomState(101).rand(radio_len) * 2 - 1).astype(np.float32)
    # Bandpass filter around 2kHz
    r_t = np.arange(radio_len) / sr
    r_tone = np.sin(2 * np.pi * 1850 * r_t) * 0.35 + np.sin(2 * np.pi * 2400 * r_t) * 0.25
    r_burst = (r_noise * 0.4 + r_tone) * np.sin(np.pi * np.linspace(0, 1, radio_len))
    out[:radio_len] += r_burst * 0.55

    # Heavy diesel engine revving up (42Hz -> 78Hz with rich piston strokes)
    freq = 42.0 + 36.0 * (1.0 - np.exp(-1.8 * t))
    phase = 2 * np.pi * np.cumsum(freq) / sr
    engine = (np.sin(phase) * 0.6 + 
              np.sin(phase * 2) * 0.35 + 
              np.sin(phase * 3) * 0.2 + 
              np.sin(phase * 4) * 0.15 +
              np.sin(phase * 0.5) * 0.4) # sub-rumble
    # Engine envelope swells up
    eng_env = np.clip(t / 0.3, 0, 1.0) * np.linspace(0.85, 1.0, n)
    out += engine * eng_env * 0.55

    # Tank track clank pulses (every ~100ms)
    for clank_t in np.arange(0.15, dur - 0.1, 0.095):
        idx = int(clank_t * sr)
        c_len = int(0.04 * sr)
        if idx + c_len < n:
            c_t = np.arange(c_len) / sr
            c_wave = (np.sin(2 * np.pi * 1450 * c_t) * 0.6 + np.sin(2 * np.pi * 820 * c_t) * 0.4) * np.exp(-55 * c_t)
            out[idx:idx+c_len] += c_wave * 0.4

    # Heavy vehicle hull vibration noise
    v_noise = (np.random.RandomState(202).rand(n) * 2 - 1).astype(np.float32)
    # 5-sample smoothing
    v_noise = np.convolve(v_noise, np.ones(8)/8.0, mode='same')
    out += v_noise * 0.18 * eng_env

    # Fade out
    fade_len = int(0.3 * sr)
    out[-fade_len:] *= np.linspace(1.0, 0.0, fade_len)
    save_wav('dispatch_b.wav', out, sr)

# -------------------------------------------------------------
# 2. Firefight Sound (拦截战交火)
# -------------------------------------------------------------
# Option A: Authentic Flak Cannon Burst & Airburst Explosion
def build_firefight_a():
    flak, sr = load_wav('/tmp/flak_fire.wav')
    heavy_c, sr = load_wav('/tmp/heavy_cannon.wav')
    expl, sr = load_wav('/tmp/ex_large7.wav')

    dur = 1.35
    n = int(dur * sr)
    out = np.zeros(n, dtype=np.float32)

    # Round 1: Flak Cannon at t=0.0s
    s1 = min(len(flak), n)
    out[:s1] += flak[:s1] * 0.75

    # Round 2: Heavy Cannon at t=0.13s
    t2 = int(0.13 * sr)
    s2 = min(len(heavy_c), n - t2)
    out[t2:t2+s2] += heavy_c[:s2] * 0.85

    # Round 3: Flak Cannon at t=0.26s
    t3 = int(0.26 * sr)
    s3 = min(len(flak), n - t3)
    out[t3:t3+s3] += flak[:s3] * 0.80

    # Airburst explosion detonation at t=0.38s
    t_ex = int(0.38 * sr)
    s_ex = min(len(expl), n - t_ex)
    out[t_ex:t_ex+s_ex] += expl[:s_ex] * 0.95

    # Fade out tail
    fade = int(0.25 * sr)
    out[-fade:] *= np.linspace(1.0, 0.0, fade)
    save_wav('firefight.wav', out, sr)

# Option B: Heavy .50 Cal / 20mm Autocannon Salvo with Rich Shell Ejection
def build_firefight_b():
    heavy_c, sr = load_wav('/tmp/heavy_cannon.wav')
    expl, sr = load_wav('/tmp/ex_large7.wav')
    dur = 1.2
    n = int(dur * sr)
    out = np.zeros(n, dtype=np.float32)

    # 4-round rapid fire burst at 0.0, 0.08, 0.16, 0.25
    shots = [0.0, 0.085, 0.17, 0.26]
    for idx, st in enumerate(shots):
        t_idx = int(st * sr)
        # Sharp supersonic bullet crack
        c_len = int(0.04 * sr)
        c_noise = (np.random.RandomState(idx * 7 + 13).rand(c_len) * 2 - 1).astype(np.float32)
        c_env = np.exp(-60 * (np.arange(c_len) / sr))
        out[t_idx:t_idx+c_len] += c_noise * c_env * 0.7

        # Mechanical bolt report & muzzle punch
        s_len = min(len(heavy_c), n - t_idx)
        out[t_idx:t_idx+s_len] += heavy_c[:s_len] * (0.65 - idx * 0.05)

    # Impact explosion at 0.35s
    t_ex = int(0.35 * sr)
    s_ex = min(len(expl), n - t_ex)
    out[t_ex:t_ex+s_ex] += expl[:s_ex] * 0.80

    # Fade out
    fade = int(0.25 * sr)
    out[-fade:] *= np.linspace(1.0, 0.0, fade)
    save_wav('firefight_b.wav', out, sr)

# -------------------------------------------------------------
# 3. Victory Sound (战斗指挥战胜)
# -------------------------------------------------------------
# Option A: Grand Ceremonial Victory Fanfare by USAF Concert Band
def build_victory_a():
    fanfare, sr = load_wav('/tmp/ceremonial_fanfare.wav')
    # Extract the climax at 36.6s to 41.3s
    start_f = int(36.6 * sr)
    end_f = int(41.4 * sr)
    seg = fanfare[start_f:end_f].copy()
    
    fade_in = np.linspace(0.0, 1.0, int(0.08 * sr))
    seg[:len(fade_in)] *= fade_in
    fade_out = np.linspace(1.0, 0.0, int(0.45 * sr))
    seg[-len(fade_out):] *= fade_out
    save_wav('victory.wav', seg, sr)

# Option B: Heroic Brass Cadence & Timpani Roll (Epic 3.2s segment)
def build_victory_b():
    fanfare, sr = load_wav('/tmp/ceremonial_fanfare.wav')
    # Extract punchy triumphant theme at 25.0s to 28.5s
    start_f = int(25.0 * sr)
    end_f = int(28.5 * sr)
    seg = fanfare[start_f:end_f].copy()
    
    fade_in = np.linspace(0.0, 1.0, int(0.05 * sr))
    seg[:len(fade_in)] *= fade_in
    fade_out = np.linspace(1.0, 0.0, int(0.35 * sr))
    seg[-len(fade_out):] *= fade_out
    save_wav('victory_b.wav', seg, sr)

# -------------------------------------------------------------
# 4. Building Upgrade Sound (建筑升级完成)
# -------------------------------------------------------------
# Option A: Heavy Industrial Girder Forge & Pneumatic Pressure Lock
def build_upgrade_a():
    sr = SAMPLE_RATE
    dur = 1.3
    n = int(dur * sr)
    out = np.zeros(n, dtype=np.float32)

    # 1) Heavy girder impact (65Hz sub-bass shock)
    t_vec = np.arange(int(0.35 * sr)) / sr
    heavy_girder = np.sin(2 * np.pi * (65 - 35 * (t_vec / 0.35)) * t_vec) * np.exp(-12 * t_vec)

    # 2) Resonant forged steel plate modes (480Hz, 1150Hz, 2380Hz, 4120Hz)
    anvil_mode1 = np.sin(2 * np.pi * 480 * t_vec) * np.exp(-7 * t_vec) * 0.5
    anvil_mode2 = np.sin(2 * np.pi * 1150 * t_vec) * np.exp(-9 * t_vec) * 0.4
    anvil_mode3 = np.sin(2 * np.pi * 2380 * t_vec) * np.exp(-14 * t_vec) * 0.25
    anvil_mode4 = np.sin(2 * np.pi * 4120 * t_vec) * np.exp(-22 * t_vec) * 0.15
    iron_hit = heavy_girder * 0.75 + (anvil_mode1 + anvil_mode2 + anvil_mode3 + anvil_mode4) * 0.5

    # Strike 1 at t=0.0s
    out[:len(iron_hit)] += iron_hit * 0.75
    # Strike 2 (solid lock) at t=0.18s
    t_hit2 = int(0.18 * sr)
    out[t_hit2:t_hit2+len(iron_hit)] += iron_hit * 0.9

    # 3) High-pressure pneumatic steam exhaust starting at t=0.22s
    t_steam = int(0.22 * sr)
    steam_len = int(0.65 * sr)
    noise = (np.random.RandomState(42).rand(steam_len) * 2.0 - 1.0).astype(np.float32)
    kernel = np.ones(15) / 15.0
    filtered_steam = np.convolve(noise, kernel, mode='same')
    steam_env = np.linspace(0.0, 1.0, int(0.05 * sr))
    steam_env = np.concatenate([steam_env, np.exp(-4.5 * np.linspace(0, 1, steam_len - len(steam_env)))])
    out[t_steam:t_steam+steam_len] += filtered_steam * steam_env * 0.35

    # 4) Armored bulkhead mechanical latch lock at t=0.55s
    t_latch = int(0.55 * sr)
    latch_len = int(0.25 * sr)
    t_l = np.arange(latch_len) / sr
    latch = (np.sin(2 * np.pi * 140 * t_l) + 0.5 * np.sin(2 * np.pi * 320 * t_l)) * np.exp(-18 * t_l)
    out[t_latch:t_latch+latch_len] += latch * 0.65

    fade = int(0.2 * sr)
    out[-fade:] *= np.linspace(1.0, 0.0, fade)
    save_wav('upgrade.wav', out, sr)

# Option B: RTS Tactical Radio Advisor ("Construction Complete") over Fortress Latch
def build_upgrade_b():
    cc_voice, sr = load_wav('/tmp/construction_complete.wav')
    dur = 1.6
    n = int(dur * sr)
    out = np.zeros(n, dtype=np.float32)
    
    # Industrial latch & girder clank at t=0.0s
    t_vec = np.arange(int(0.25 * sr)) / sr
    iron_clank = (np.sin(2 * np.pi * 80 * t_vec) * 0.6 + np.sin(2 * np.pi * 650 * t_vec) * 0.4) * np.exp(-14 * t_vec)
    out[:len(iron_clank)] += iron_clank * 0.55
    
    # Tactical radio advisor voice at t=0.15s
    t_vc = int(0.15 * sr)
    s_vc = min(len(cc_voice), n - t_vc)
    out[t_vc:t_vc+s_vc] += cc_voice[:s_vc] * 0.88

    save_wav('upgrade_b.wav', out, sr)

if __name__ == '__main__':
    print("Building realistic military sound effects...")
    build_dispatch_a()
    build_dispatch_b()
    build_firefight_a()
    build_firefight_b()
    build_victory_a()
    build_victory_b()
    build_upgrade_a()
    build_upgrade_b()
    print("All realistic military sounds successfully generated in frontend/audio/!")
