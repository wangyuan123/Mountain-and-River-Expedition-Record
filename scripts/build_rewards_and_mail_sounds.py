#!/usr/bin/env python3
"""
Generate High-Fidelity Audio for:
1. Coins / Cash Register (领取道具、资源、邮件附件) - Option A & Option B
2. Chest / Crate Opening (打开各类宝箱) - Option A & Option B
3. Mail Send Whoosh (发送邮件 类似嗖~发送) - Option A & Option B

All sounds are mixed at 44.1kHz 16-bit PCM mono, normalized with realistic acoustics.
"""

import os
import wave
import numpy as np
import soundfile as sf
import scipy.signal as signal

SAMPLE_RATE = 44100
OUT_DIR = os.path.join(os.path.dirname(__file__), '../frontend/audio')
os.makedirs(OUT_DIR, exist_ok=True)

KENNEY_RPG = '/tmp/kenney_audio/rpg/Audio'
KENNEY_CASINO = '/tmp/kenney_audio/casino/Audio'
KENNEY_IMPACT = '/tmp/kenney_audio/impact/Audio'
KENNEY_UI = '/tmp/kenney_audio/interface/Audio'

def load_audio(path, target_sr=SAMPLE_RATE):
    data, sr = sf.read(path)
    if data.ndim > 1:
        data = data.mean(axis=1) # mono downmix
    data = data.astype(np.float32)
    if sr != target_sr:
        num_samples = int(round(len(data) * target_sr / sr))
        data = signal.resample(data, num_samples)
    return data

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
    print(f"Generated {out_path} ({len(data)/sr:.2f}s, peak={peak:.3f})")

# -------------------------------------------------------------------
# 1. 领取奖励音效 (Coins / Cash Register)
# -------------------------------------------------------------------

def build_coins_a():
    """方案 A · 金币连环碰撞与落袋沉底 (Coins Clink & Pouch Drop)"""
    sr = SAMPLE_RATE
    c1 = load_audio(os.path.join(KENNEY_RPG, 'handleCoins2.ogg'), sr)
    c2 = load_audio(os.path.join(KENNEY_RPG, 'handleCoins.ogg'), sr)
    
    dur = 0.65
    n = int(dur * sr)
    out = np.zeros(n, dtype=np.float32)
    
    # 1) Initial bright coin clink at t=0.0s (high-pass filtered for silver/gold sparkle)
    sos = signal.butter(4, 2500, 'hp', fs=sr, output='sos')
    c1_bright = signal.sosfilt(sos, c1) * 1.2
    
    # Add pure high-frequency precious metal ringing overtone (3800Hz & 5800Hz)
    t_ring = np.arange(int(0.25 * sr)) / sr
    ring = (np.sin(2 * np.pi * 3820 * t_ring) * 0.4 + np.sin(2 * np.pi * 5840 * t_ring) * 0.25) * np.exp(-28 * t_ring)
    
    s_len = min(len(c1_bright), n)
    out[:s_len] += c1_bright[:s_len] * 0.95
    out[:len(ring)] += ring * 0.35
    
    # 2) Cascade coin rattle starting at t=0.08s
    t_casc = int(0.08 * sr)
    s2_len = min(len(c2), n - t_casc)
    out[t_casc:t_casc+s2_len] += c2[:s2_len] * 0.85
    
    # 3) Secondary sharp clink at t=0.14s (another coin landing on top)
    t_c2 = int(0.14 * sr)
    s_c2 = min(len(c1), n - t_c2)
    out[t_c2:t_c2+s_c2] += c1[:s_c2] * 0.60
    
    # Leather pouch bottom thud at t=0.22s (warm 220Hz damped thump)
    t_thud = int(0.22 * sr)
    t_th = np.arange(int(0.15 * sr)) / sr
    thud = np.sin(2 * np.pi * 180 * t_th) * np.exp(-35 * t_th) * 0.3
    if t_thud + len(thud) <= n:
        out[t_thud:t_thud+len(thud)] += thud
        
    # Smooth tail fade out
    fade = int(0.15 * sr)
    out[-fade:] *= np.linspace(1.0, 0.0, fade)
    
    save_wav('coins.wav', out, sr)

def build_coins_b():
    """方案 B · 经典收银机卡扣开匣与硬币滑落 (Mechanical Cash Register & Coin Cascade)"""
    sr = SAMPLE_RATE
    bell = load_audio(os.path.join(KENNEY_IMPACT, 'impactBell_heavy_002.ogg'), sr)
    c2 = load_audio(os.path.join(KENNEY_RPG, 'handleCoins.ogg'), sr)
    latch = load_audio(os.path.join(KENNEY_RPG, 'metalLatch.ogg'), sr)
    
    dur = 0.75
    n = int(dur * sr)
    out = np.zeros(n, dtype=np.float32)
    
    # 1) Cash register mechanical drawer catch pop at t=0.00s
    s_latch = min(len(latch), n)
    out[:s_latch] += latch[:s_latch] * 0.75
    
    # 2) Vintage cash register bell ring at t=0.02s ("Ka-ching!")
    # Bell filtered for clear register chime (C7 ~2093Hz and G7 ~3136Hz)
    t_bell = int(0.02 * sr)
    s_bell = min(len(bell), n - t_bell)
    out[t_bell:t_bell+s_bell] += bell[:s_bell] * 0.90
    
    # Synthetic dual-tone bell resonance (C7 + G7)
    t_chime = np.arange(int(0.55 * sr)) / sr
    chime = (np.sin(2 * np.pi * 2093.0 * t_chime) * 0.45 + 
             np.sin(2 * np.pi * 3135.9 * t_chime) * 0.35 +
             np.sin(2 * np.pi * 4186.0 * t_chime) * 0.15) * np.exp(-8.5 * t_chime)
    out[t_bell:t_bell+len(chime)] += chime * 0.55
    
    # 3) Coins sliding into drawer tray at t=0.10s
    t_coins = int(0.10 * sr)
    s_coins = min(len(c2), n - t_coins)
    out[t_coins:t_coins+s_coins] += c2[:s_coins] * 0.70
    
    # Smooth tail fade
    fade = int(0.18 * sr)
    out[-fade:] *= np.linspace(1.0, 0.0, fade)
    
    save_wav('coins_b.wav', out, sr)

# -------------------------------------------------------------------
# 2. 打开各类宝箱 (Chest / Crate Opening)
# -------------------------------------------------------------------

def build_chest_a():
    """方案 A · 战术军需重箱双扣弹开与铁盖开启 (Tactical Metal Crate & Dual Spring Latches)"""
    sr = SAMPLE_RATE
    latch = load_audio(os.path.join(KENNEY_RPG, 'metalLatch.ogg'), sr)
    click = load_audio(os.path.join(KENNEY_RPG, 'metalClick.ogg'), sr)
    door = load_audio(os.path.join(KENNEY_RPG, 'doorOpen_1.ogg'), sr)
    
    dur = 0.75
    n = int(dur * sr)
    out = np.zeros(n, dtype=np.float32)
    
    # 1) Latch 1: Heavy spring clamp snap at t=0.0s ("Clack!")
    s1 = min(len(latch), n)
    out[:s1] += latch[:s1] * 0.95
    
    # 2) Latch 2: Second clamp popping open at t=0.09s ("Click!")
    t2 = int(0.09 * sr)
    s2 = min(len(click), n - t2)
    out[t2:t2+s2] += click[:s2] * 0.85
    s_latch2 = min(len(latch), n - t2)
    out[t2:t2+s_latch2] += latch[:s_latch2] * 0.65
    
    # 3) Heavy metal lid lift + hinge swivel starting at t=0.18s
    t_door = int(0.18 * sr)
    s_door = min(len(door), n - t_door)
    out[t_door:t_door+s_door] += door[:s_door] * 0.80
    
    # Sub-bass heavy crate mass resonance (110Hz body vibration)
    t_res = np.arange(int(0.20 * sr)) / sr
    sub = np.sin(2 * np.pi * 110 * t_res) * np.exp(-15 * t_res) * 0.25
    out[t_door:t_door+len(sub)] += sub
    
    # Smooth tail fade out
    fade = int(0.15 * sr)
    out[-fade:] *= np.linspace(1.0, 0.0, fade)
    
    save_wav('chest.wav', out, sr)

def build_chest_b():
    """方案 B · 沉重宝库铁锁滑落与厚木箱盖开启 (Treasury Heavy Chest & Creaking Oak Lid)"""
    sr = SAMPLE_RATE
    latch = load_audio(os.path.join(KENNEY_RPG, 'metalLatch.ogg'), sr)
    creak = load_audio(os.path.join(KENNEY_RPG, 'creak1.ogg'), sr)
    door = load_audio(os.path.join(KENNEY_RPG, 'doorOpen_2.ogg'), sr)
    
    dur = 0.90
    n = int(dur * sr)
    out = np.zeros(n, dtype=np.float32)
    
    # 1) Heavy iron padlock tumbler unlocking click at t=0.00s
    s_l = min(len(latch), n)
    out[:s_l] += latch[:s_l] * 0.90
    
    # Low frequency padlock mechanism click (180Hz)
    t_cl = np.arange(int(0.08 * sr)) / sr
    out[:len(t_cl)] += np.sin(2 * np.pi * 180 * t_cl) * np.exp(-40 * t_cl) * 0.35
    
    # 2) Heavy wooden chest creak starting at t=0.12s
    t_cr = int(0.12 * sr)
    s_cr = min(len(creak), n - t_cr)
    out[t_cr:t_cr+s_cr] += creak[:s_cr] * 0.85
    
    # 3) Heavy oak chest lid swinging open at t=0.22s
    t_d = int(0.22 * sr)
    s_d = min(len(door), n - t_d)
    out[t_d:t_d+s_d] += door[:s_d] * 0.65
    
    # Smooth tail fade
    fade = int(0.20 * sr)
    out[-fade:] *= np.linspace(1.0, 0.0, fade)
    
    save_wav('chest_b.wav', out, sr)

# -------------------------------------------------------------------
# 3. 发送邮件音效 (Mail Send Whoosh 类似嗖~ 发送)
# -------------------------------------------------------------------

def build_mail_a():
    """方案 A · 极速破空飞掠 (Aerodynamic High-Speed Whoosh & Dispatch)"""
    sr = SAMPLE_RATE
    dur = 0.45
    n = int(dur * sr)
    t = np.arange(n) / sr
    out = np.zeros(n, dtype=np.float32)
    
    # 1) Aerodynamic noise whoosh with continuous bandpass sweep:
    # Frequency ramps rapidly: 800Hz -> 4800Hz -> 1400Hz (parabolic pitch curve)
    center_freq = 900.0 + 4200.0 * np.sin(np.pi * (t / dur) ** 0.8)
    
    # Generate white noise with random seed
    raw_noise = (np.random.RandomState(42).rand(n) * 2 - 1).astype(np.float32)
    
    # Time-varying filter using block-by-block filtering
    block_size = 256
    filtered_whoosh = np.zeros(n, dtype=np.float32)
    for b in range(0, n, block_size):
        end = min(b + block_size, n)
        mid_t = (b + end) / 2.0 / sr
        cf = 900.0 + 4200.0 * np.sin(np.pi * (mid_t / dur) ** 0.8)
        bw = max(350.0, cf * 0.45)
        low = max(50.0, cf - bw / 2)
        high = min(sr / 2 - 100, cf + bw / 2)
        sos = signal.butter(2, [low, high], 'bandpass', fs=sr, output='sos')
        chunk = raw_noise[max(0, b - 128):min(n, end + 128)]
        f_chunk = signal.sosfilt(sos, chunk)
        offset = b - max(0, b - 128)
        filtered_whoosh[b:end] = f_chunk[offset:offset + (end - b)]
        
    # Asymmetric whoosh envelope: sharp rush in (peak at 35%), smooth dissipation
    whoosh_env = (t / (0.35 * dur)) ** 1.8 * np.exp(-3.5 * (t / dur))
    whoosh_env = whoosh_env / np.max(whoosh_env)
    out += filtered_whoosh * whoosh_env * 0.95
    
    # 2) Swift air blade whistling harmonic (whistling wind effect)
    f_whistle = 1800 + 3600 * np.sin(np.pi * (t / dur) ** 0.85)
    phase_whistle = 2 * np.pi * np.cumsum(f_whistle) / sr
    whistle = np.sin(phase_whistle) * (whoosh_env ** 1.5) * 0.22
    out += whistle
    
    # 3) Subtle paper/parchment flick snap at takeoff (t=0.01s)
    t_flick = int(0.015 * sr)
    t_fl = np.arange(int(0.035 * sr)) / sr
    flick = (np.sin(2 * np.pi * 3200 * t_fl) * 0.6 + (np.random.RandomState(99).rand(len(t_fl))*2-1)*0.4) * np.exp(-120 * t_fl)
    out[t_flick:t_flick+len(flick)] += flick * 0.35
    
    # Tail fade out
    fade = int(0.12 * sr)
    out[-fade:] *= np.linspace(1.0, 0.0, fade)
    
    save_wav('send_mail.wav', out, sr)

def build_mail_b():
    """方案 B · 战地气压信道投递发送 (Pneumatic Dispatch Tube Whoosh)"""
    sr = SAMPLE_RATE
    dur = 0.52
    n = int(dur * sr)
    t = np.arange(n) / sr
    out = np.zeros(n, dtype=np.float32)
    
    # 1) Pneumatic tube cap insertion punch ("Popp-thump" at t=0.00s)
    t_pop = np.arange(int(0.06 * sr)) / sr
    pop = (np.sin(2 * np.pi * (160 - 100 * (t_pop / 0.06)) * t_pop) * 0.7 +
           (np.random.RandomState(88).rand(len(t_pop))*2-1) * 0.3) * np.exp(-50 * t_pop)
    out[:len(pop)] += pop * 0.65
    
    # 2) High-speed compressed air suction & release ("Psssshhh-Whoosh!")
    # Sweep from 1200Hz up to 3600Hz and rush into the distance
    raw_noise = (np.random.RandomState(77).rand(n) * 2 - 1).astype(np.float32)
    
    block_size = 256
    air_rush = np.zeros(n, dtype=np.float32)
    for b in range(0, n, block_size):
        end = min(b + block_size, n)
        mid_t = (b + end) / 2.0 / sr
        # Starts after pop (t > 0.03)
        progress = max(0.0, (mid_t - 0.03) / (dur - 0.03))
        cf = 1200.0 + 3200.0 * np.sin(np.pi * (progress ** 0.7))
        bw = max(400.0, cf * 0.5)
        low = max(80.0, cf - bw / 2)
        high = min(sr / 2 - 100, cf + bw / 2)
        sos = signal.butter(2, [low, high], 'bandpass', fs=sr, output='sos')
        chunk = raw_noise[max(0, b - 128):min(n, end + 128)]
        f_chunk = signal.sosfilt(sos, chunk)
        offset = b - max(0, b - 128)
        air_rush[b:end] = f_chunk[offset:offset + (end - b)]
        
    rush_env = np.where(t < 0.03, 0.0, ((t - 0.03) / 0.15) * np.exp(-3.2 * (t - 0.03) / (dur - 0.03)))
    rush_env = rush_env / (np.max(rush_env) + 1e-6)
    out += air_rush * rush_env * 0.85
    
    # 3) Tube hollow acoustic resonance (tube column air modes ~ 450Hz & 920Hz)
    t_res = np.where(t < 0.03, 0.0, t - 0.03)
    tube_res = (np.sin(2 * np.pi * 450 * t_res) * 0.35 + np.sin(2 * np.pi * 920 * t_res) * 0.2) * (rush_env ** 1.3)
    out += tube_res * 0.35
    
    # Fade tail
    fade = int(0.14 * sr)
    out[-fade:] *= np.linspace(1.0, 0.0, fade)
    
    save_wav('send_mail_b.wav', out, sr)

if __name__ == '__main__':
    print("=== Generating Coins Sounds ===")
    build_coins_a()
    build_coins_b()
    
    print("=== Generating Chest Sounds ===")
    build_chest_a()
    build_chest_b()
    
    print("=== Generating Mail Send Sounds ===")
    build_mail_a()
    build_mail_b()
    print("=== Done! ===")
