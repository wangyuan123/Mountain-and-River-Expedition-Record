#!/usr/bin/env python3
"""
Build ~2.0s PLA Honor Guard Goose-Step (中国三军仪仗队正步) sound effects for Troop Dispatch:
100% Real Multi-Track Acoustic Boot & Platoon Foley Alignment (Zero synthetic sine oscillators, zero comb-filtering).

Option A (dispatch.wav, 2.00s, 116 BPM):
  三军仪仗队 · 长安街石板正步四连踏 (4 resolute goose-steps at 0.05s, 0.56s, 1.07s, 1.58s)
  Crisp hard-leather boot sole whip-crack on stone + steel heel-cleat snap + 200-man platoon unison + stone square reflection.

Option B (dispatch_b.wav, 2.04s, 112 BPM):
  阅兵重装步兵方阵 · 广场阔步四连踏 (4 heavy parade strides at 0.05s, 0.575s, 1.10s, 1.625s)
  Deep concrete & granite square impact + massed regiment boot weight + wide square acoustic reflections.
"""

import os
import numpy as np
import soundfile as sf
from scipy.signal import resample, butter, sosfilt

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
AUDIO_DIR = os.path.join(BASE_DIR, 'frontend', 'audio')
SOURCES_DIR = '/tmp/parade_sources'

SR = 44100

def load_44k(fname):
    path = os.path.join(SOURCES_DIR, fname)
    data, sr = sf.read(path)
    if data.ndim == 2:
        data = np.mean(data, axis=1)
    if sr != SR:
        data = resample(data, int(round(len(data) * SR / sr)))
    data = data.astype(np.float32)
    return data / (np.max(np.abs(data)) + 1e-9)

def band_filter(sig, low_hz=None, high_hz=None, order=3):
    if low_hz and high_hz:
        sos = butter(order, [low_hz, high_hz], btype='bandpass', fs=SR, output='sos')
    elif low_hz:
        sos = butter(order, low_hz, btype='highpass', fs=SR, output='sos')
    elif high_hz:
        sos = butter(order, high_hz, btype='lowpass', fs=SR, output='sos')
    else:
        return sig
    return sosfilt(sos, sig).astype(np.float32)

def extract_grain(sig, peak_sec, pre_ms=14.0, post_ms=380.0, attack_ms=5.0, hold_ms=90.0, decay_rate=3.2, search_ms=16.0):
    pre_s = int(pre_ms * SR / 1000.0)
    post_s = int(post_ms * SR / 1000.0)
    total_s = pre_s + post_s

    c = int(peak_sec * SR)
    search_w = int(search_ms * SR / 1000.0)
    w_slice = sig[max(0, c - search_w) : min(len(sig), c + search_w)]
    win2 = max(1, int(0.002 * SR))
    env = np.sqrt(np.convolve(w_slice**2, np.ones(win2)/win2, mode='same'))
    p_idx = max(0, c - search_w) + int(np.argmax(env))

    grain = np.zeros(total_s, dtype=np.float32)
    src_start = p_idx - pre_s
    src_end = p_idx + post_s

    dst_start = max(0, -src_start)
    dst_end = total_s - max(0, src_end - len(sig))
    src_start = max(0, src_start)
    src_end = min(len(sig), src_end)

    grain[dst_start:dst_end] = sig[src_start:src_end]

    env_curve = np.ones(total_s, dtype=np.float32)
    att_s = int(attack_ms * SR / 1000.0)
    if pre_s > att_s:
        env_curve[:pre_s - att_s] = 0.0
        env_curve[pre_s - att_s : pre_s] = np.linspace(0.0, 1.0, att_s) ** 1.5
    else:
        env_curve[:pre_s] = np.linspace(0.0, 1.0, pre_s)

    dec_start_idx = pre_s + int(hold_ms * SR / 1000.0)
    if dec_start_idx < total_s:
        rem = total_s - dec_start_idx
        t_fade = np.linspace(0.0, 1.0, rem)
        env_curve[dec_start_idx:] = np.exp(-decay_rate * t_fade) * np.cos(0.5 * np.pi * t_fade)

    grain *= env_curve
    pk = np.max(np.abs(grain))
    if pk > 1e-6:
        grain /= pk
    return grain

def add_grain(buf, grain, t_peak_sec, pre_ms, gain):
    peak_offset = int(pre_ms * SR / 1000.0)
    start_idx = int(t_peak_sec * SR) - peak_offset
    end_idx = start_idx + len(grain)
    dst_s = max(0, start_idx)
    dst_e = min(len(buf), end_idx)
    src_s = dst_s - start_idx
    src_e = src_s + (dst_e - dst_s)
    if dst_e > dst_s:
        buf[dst_s:dst_e] += grain[src_s:src_e] * gain

def load_step_pools():
    d_hard = load_44k('stomping_boots_hard_626.mp3')
    d_stomp_d = load_44k('foot_stomp_d_20.mp3')
    d_stomp_85 = load_44k('stomping_boots_85.mp3')
    d_army86 = load_44k('march_of_army_86.mp3')
    d_fuerte = load_44k('marcha_fuerte_393.mp3')
    d_march3 = load_44k('marching_3_200.mp3')
    d_concrete = load_44k('marching_concrete.mp3')
    d_boots172 = load_44k('marching_boots_172.mp3')

    pools = {
        'hard': [extract_grain(d_hard, t, pre_ms=10, post_ms=220, attack_ms=3, hold_ms=45, decay_rate=3.8, search_ms=12)
                 for t in [0.345, 0.780, 1.250, 1.690, 2.555, 2.990, 3.420, 3.840]],
        'army86': [extract_grain(band_filter(d_army86, low_hz=550), t, pre_ms=14, post_ms=260, attack_ms=4, hold_ms=65, decay_rate=3.2)
                   for t in [0.327, 0.802, 1.318, 1.810, 2.307, 2.767, 3.267, 4.266]],
        'fuerte': [extract_grain(d_fuerte, t, pre_ms=14, post_ms=380, attack_ms=5, hold_ms=90, decay_rate=2.4)
                   for t in [0.648, 1.103, 1.547, 2.923, 3.397, 3.862, 4.291, 5.622]],
        'march3': [extract_grain(d_march3, t, pre_ms=14, post_ms=380, attack_ms=5, hold_ms=95, decay_rate=2.4)
                   for t in [1.108, 1.656, 2.152, 2.638, 3.156, 3.705, 5.205, 10.820]],
        'concrete': [extract_grain(band_filter(d_concrete, low_hz=45, high_hz=3500), t, pre_ms=14, post_ms=380, attack_ms=5, hold_ms=95, decay_rate=2.5)
                     for t in [0.070, 0.668, 1.248, 1.730, 2.511, 2.976, 3.441, 5.327]],
        'boots172': [extract_grain(band_filter(d_boots172, low_hz=40, high_hz=3000), t, pre_ms=14, post_ms=380, attack_ms=5, hold_ms=95, decay_rate=2.5)
                     for t in [37.096, 37.621, 38.098, 38.565, 1.799, 2.757, 4.259, 5.736]],
        'stomp85': extract_grain(band_filter(d_stomp_85, low_hz=42, high_hz=2200), 0.149, pre_ms=10, post_ms=180, attack_ms=3, hold_ms=45, decay_rate=3.6, search_ms=10),
        'stompd': extract_grain(band_filter(d_stomp_d, low_hz=90, high_hz=4800), 0.080, pre_ms=12, post_ms=220, attack_ms=4, hold_ms=55, decay_rate=3.4, search_ms=15),
    }
    sub = band_filter(pools['stomp85'], low_hz=42, high_hz=160)
    if np.max(np.abs(sub)) > 1e-6:
        sub /= np.max(np.abs(sub))
    pools['stomp85_sub'] = sub
    return pools

def synthesize_goose_step(pools, step_times, dur_sec, style='A'):
    n = int(dur_sec * SR)
    mix = np.zeros(n, dtype=np.float32)

    for k, t0 in enumerate(step_times):
        step_buf = np.zeros(n, dtype=np.float32)
        idx1 = (2 * k) % 8
        idx2 = (2 * k + 1) % 8
        idx3 = (2 * k + 3) % 8

        if style == 'A':
            # 1. Hard leather boot sole whip-crack on Chang'an Avenue stone pavement
            add_grain(step_buf, pools['hard'][idx1], t0 + 0.000, 10.0, 0.95)
            add_grain(step_buf, pools['hard'][idx2], t0 + 0.004, 10.0, 0.78)
            add_grain(step_buf, pools['hard'][idx3], t0 + 0.008, 10.0, 0.65)
            add_grain(step_buf, pools['stompd'],     t0 + 0.002, 12.0, 0.65)

            # 2. Steel heel-cleat & hobnail snap ("铿锵！")
            add_grain(step_buf, pools['army86'][idx1], t0 + 0.001, 14.0, 0.75)
            add_grain(step_buf, pools['army86'][idx2], t0 + 0.006, 14.0, 0.60)

            # 3. 200-man Honor Guard formation unison body
            add_grain(step_buf, pools['fuerte'][idx1],   t0 + 0.001, 14.0, 0.85)
            add_grain(step_buf, pools['fuerte'][idx2],   t0 + 0.006, 14.0, 0.70)
            add_grain(step_buf, pools['march3'][idx1],   t0 + 0.002, 14.0, 0.85)
            add_grain(step_buf, pools['march3'][idx2],   t0 + 0.007, 14.0, 0.70)
            add_grain(step_buf, pools['concrete'][idx1], t0 + 0.000, 14.0, 0.80)
            add_grain(step_buf, pools['concrete'][idx2], t0 + 0.005, 14.0, 0.65)

            # 4. Real acoustic boot low-end weight
            add_grain(step_buf, pools['stomp85'],        t0 + 0.000, 10.0, 0.75)
            add_grain(step_buf, pools['stomp85_sub'],    t0 + 0.002, 10.0, 0.70)
            add_grain(step_buf, pools['boots172'][idx1], t0 + 0.003, 14.0, 0.70)
        else:
            # Scheme B: Heavy Parade Regiment on Granite Square
            add_grain(step_buf, pools['hard'][idx2],     t0 + 0.000, 10.0, 0.82)
            add_grain(step_buf, pools['hard'][idx3],     t0 + 0.005, 10.0, 0.70)
            add_grain(step_buf, pools['stompd'],         t0 + 0.001, 12.0, 0.75)

            add_grain(step_buf, pools['army86'][idx2],   t0 + 0.002, 14.0, 0.65)
            add_grain(step_buf, pools['fuerte'][idx1],   t0 + 0.000, 14.0, 0.90)
            add_grain(step_buf, pools['fuerte'][idx2],   t0 + 0.006, 14.0, 0.80)
            add_grain(step_buf, pools['fuerte'][idx3],   t0 + 0.011, 14.0, 0.65)

            add_grain(step_buf, pools['march3'][idx2],   t0 + 0.002, 14.0, 0.85)
            add_grain(step_buf, pools['march3'][idx3],   t0 + 0.009, 14.0, 0.75)

            add_grain(step_buf, pools['concrete'][idx1], t0 + 0.000, 14.0, 0.95)
            add_grain(step_buf, pools['concrete'][idx2], t0 + 0.006, 14.0, 0.85)
            add_grain(step_buf, pools['boots172'][idx1], t0 + 0.001, 14.0, 0.95)
            add_grain(step_buf, pools['boots172'][idx2], t0 + 0.008, 14.0, 0.80)

            add_grain(step_buf, pools['stomp85'],        t0 + 0.000, 10.0, 0.90)
            add_grain(step_buf, pools['stomp85_sub'],    t0 + 0.002, 10.0, 0.85)

        # Stone Square Early Reflections
        refl_src = band_filter(step_buf, low_hz=240, high_hz=4500)
        er_taps = [
            (0.017, 0.24), (0.031, 0.16), (0.048, 0.11),
            (0.068, 0.07), (0.092, 0.045), (0.120, 0.025)
        ] if style == 'A' else [
            (0.019, 0.28), (0.036, 0.20), (0.055, 0.14),
            (0.078, 0.09), (0.105, 0.06), (0.138, 0.035)
        ]
        for d_sec, g in er_taps:
            d_samp = int(d_sec * SR)
            if d_samp < n:
                step_buf[d_samp:] += refl_src[:-d_samp] * g

        mix += step_buf

    pk = np.max(np.abs(mix))
    if pk > 0:
        mix = mix / pk
    mix = np.tanh(mix * 1.45).astype(np.float32)

    fade = int(0.16 * SR)
    mix[-fade:] *= np.linspace(1.0, 0.0, fade)

    mix = (mix * (0.88 / np.max(np.abs(mix)))).astype(np.float32)
    return mix

def main():
    pools = load_step_pools()

    out_a = synthesize_goose_step(pools, [0.05, 0.56, 1.07, 1.58], 2.00, style='A')
    path_a = os.path.join(AUDIO_DIR, 'dispatch.wav')
    sf.write(path_a, out_a, SR, subtype='PCM_16')
    print(f'Option A exported: {path_a} ({len(out_a)/SR:.2f}s, peak={np.max(np.abs(out_a)):.2f})')

    out_b = synthesize_goose_step(pools, [0.05, 0.575, 1.10, 1.625], 2.04, style='B')
    path_b = os.path.join(AUDIO_DIR, 'dispatch_b.wav')
    sf.write(path_b, out_b, SR, subtype='PCM_16')
    print(f'Option B exported: {path_b} ({len(out_b)/SR:.2f}s, peak={np.max(np.abs(out_b)):.2f})')

if __name__ == '__main__':
    main()
