#!/usr/bin/env python3
import wave
import numpy as np
import os

def load_wav(path, target_sr=44100):
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
            
        # simple linear resample if sample rates differ
        if sr != target_sr:
            old_len = len(data)
            new_len = int(round(old_len * target_sr / sr))
            indices = np.linspace(0, old_len - 1, new_len)
            data = np.interp(indices, np.arange(old_len), data)
            
        return data, target_sr

def save_wav(path, data, sr=44100, peak_limit=0.92):
    peak = np.max(np.abs(data))
    if peak > 0:
        data = data * (peak_limit / peak)
    int_data = np.clip(data * 32767.0, -32768, 32767).astype(np.int16)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with wave.open(path, 'w') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(int_data.tobytes())
    print(f"Saved: {path} ({len(data)/sr:.2f}s)")

# 1. Dispatch (Troop March) - Real military ceremonial brass & snare drum from Adjutant's Call
adjutant, sr = load_wav('/tmp/adjutant.wav')
# Clip 0.0s to 2.4s (snare drum cadence + opening brass command call)
dispatch_a = adjutant[:int(2.4 * sr)].copy()
# Smooth fade out at the end (150ms)
fade_len = int(0.15 * sr)
fade = np.linspace(1.0, 0.0, fade_len)
dispatch_a[-fade_len:] *= fade
save_wav('/tmp/test_dispatch_a.wav', dispatch_a, sr)

# 2. Victory (Tactical Command Victory) - Grand finale from Ceremonial Fanfare
fanfare, sr = load_wav('/tmp/ceremonial_fanfare.wav')
# Climax at 36.6s to 41.2s (triumphant brass choir + timpani roll + cymbal crash + final majestic chord)
start_f = int(36.6 * sr)
end_f = int(41.4 * sr)
victory_a = fanfare[start_f:end_f].copy()
fade_in = np.linspace(0.0, 1.0, int(0.08 * sr))
victory_a[:len(fade_in)] *= fade_in
fade_out = np.linspace(1.0, 0.0, int(0.4 * sr))
victory_a[-len(fade_out):] *= fade_out
save_wav('/tmp/test_victory_a.wav', victory_a, sr)

# Also create a shorter punchy Victory B from 25.0s to 28.5s
victory_b = fanfare[int(25.0 * sr):int(28.5 * sr)].copy()
v_fade_in = np.linspace(0.0, 1.0, int(0.05 * sr))
victory_b[:len(v_fade_in)] *= v_fade_in
v_fade_out = np.linspace(1.0, 0.0, int(0.3 * sr))
victory_b[-len(v_fade_out):] *= v_fade_out
save_wav('/tmp/test_victory_b.wav', victory_b, sr)

# 3. Firefight (Interception Clash) - Real Flak cannon burst + Heavy Cannon + Explosion
flak, sr = load_wav('/tmp/flak_fire.wav')
heavy_c, sr = load_wav('/tmp/heavy_cannon.wav')
expl, sr = load_wav('/tmp/ex_large7.wav')

dur_ff = 1.3
ff_len = int(dur_ff * sr)
firefight_a = np.zeros(ff_len, dtype=np.float32)

# Shot 1: flak at t=0.0s
s1 = min(len(flak), ff_len)
firefight_a[:s1] += flak[:s1] * 0.7

# Shot 2: heavy cannon at t=0.14s
t2 = int(0.14 * sr)
s2 = min(len(heavy_c), ff_len - t2)
firefight_a[t2:t2+s2] += heavy_c[:s2] * 0.85

# Shot 3: second flak at t=0.28s
t3 = int(0.28 * sr)
s3 = min(len(flak), ff_len - t3)
firefight_a[t3:t3+s3] += flak[:s3] * 0.8

# Airburst explosion detonation at t=0.38s
t_ex = int(0.38 * sr)
s_ex = min(len(expl), ff_len - t_ex)
firefight_a[t_ex:t_ex+s_ex] += expl[:s_ex] * 0.95

# Fade out tail
ff_fade = int(0.25 * sr)
firefight_a[-ff_fade:] *= np.linspace(1.0, 0.0, ff_fade)
save_wav('/tmp/test_firefight_a.wav', firefight_a, sr)

# 4. Building Upgrade - Heavy Industrial Armor Anvil + Hydraulic Steam Lock
dur_up = 1.25
up_len = int(dur_up * sr)
upgrade_a = np.zeros(up_len, dtype=np.float32)

# Heavy hydraulic steam burst from Zero-K steam
# Let's synthesize realistic physical modeling for the heavy anvil / girder impact:
# 1) Low sub-bass concussive girder strike (65Hz -> 30Hz)
t_vec = np.arange(int(0.35 * sr)) / sr
heavy_girder = np.sin(2 * np.pi * (65 - 35 * (t_vec / 0.35)) * t_vec) * np.exp(-12 * t_vec)

# 2) Resonant forged iron impact with rich physical non-harmonic modes (480Hz, 1150Hz, 2380Hz, 4120Hz)
anvil_mode1 = np.sin(2 * np.pi * 480 * t_vec) * np.exp(-7 * t_vec) * 0.5
anvil_mode2 = np.sin(2 * np.pi * 1150 * t_vec) * np.exp(-9 * t_vec) * 0.4
anvil_mode3 = np.sin(2 * np.pi * 2380 * t_vec) * np.exp(-14 * t_vec) * 0.25
anvil_mode4 = np.sin(2 * np.pi * 4120 * t_vec) * np.exp(-22 * t_vec) * 0.15
iron_hit = heavy_girder * 0.7 + (anvil_mode1 + anvil_mode2 + anvil_mode3 + anvil_mode4) * 0.5

# Strike 1 at t=0.0s
upgrade_a[:len(iron_hit)] += iron_hit * 0.75
# Strike 2 (solid lock) at t=0.18s
t_hit2 = int(0.18 * sr)
upgrade_a[t_hit2:t_hit2+len(iron_hit)] += iron_hit * 0.9

# 3) High-pressure pneumatic steam exhaust (hiss) starting at t=0.22s
t_steam = int(0.22 * sr)
steam_len = int(0.65 * sr)
noise = (np.random.RandomState(42).rand(steam_len) * 2.0 - 1.0).astype(np.float32)
# Low-pass filter for steam (running average)
kernel = np.ones(15) / 15.0
filtered_steam = np.convolve(noise, kernel, mode='same')
steam_env = np.linspace(0.0, 1.0, int(0.05 * sr))
steam_env = np.concatenate([steam_env, np.exp(-4.5 * np.linspace(0, 1, steam_len - len(steam_env)))])
upgrade_a[t_steam:t_steam+steam_len] += filtered_steam * steam_env * 0.35

# 4) Heavy armored bulkhead latch / bolt sliding into place at t=0.55s
t_latch = int(0.55 * sr)
latch_len = int(0.25 * sr)
t_l = np.arange(latch_len) / sr
latch = (np.sin(2 * np.pi * 140 * t_l) + 0.5 * np.sin(2 * np.pi * 320 * t_l)) * np.exp(-18 * t_l)
upgrade_a[t_latch:t_latch+latch_len] += latch * 0.65

save_wav('/tmp/test_upgrade_a.wav', upgrade_a, sr)

# Variant B for Upgrade: Tactical Radio Advisor ("Construction Complete") over mechanical latch
cc_voice, sr = load_wav('/tmp/construction_complete.wav')
dur_up_b = 1.6
up_b_len = int(dur_up_b * sr)
upgrade_b = np.zeros(up_b_len, dtype=np.float32)
# First industrial latch clank
upgrade_b[:len(iron_hit)] += iron_hit * 0.6
# Followed by tactical radio advisor voice at t=0.15s
t_vc = int(0.15 * sr)
s_vc = min(len(cc_voice), up_b_len - t_vc)
upgrade_b[t_vc:t_vc+s_vc] += cc_voice[:s_vc] * 0.85
save_wav('/tmp/test_upgrade_b.wav', upgrade_b, sr)

print("Generated test audio files in /tmp/")
