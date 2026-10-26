#!/usr/bin/env python3
"""
Build 3 Sets of Seamlessly Looping Slow-Tempo Light Background Music (BGM):
1. 套系 1 · 《山河行思》· 室内管弦与从容低音 (USAF Strings - Bach Air on G String)
2. 套系 2 · 《平原微光》· 纯净沉思钢琴独奏 (Erik Satie - Gymnopédie No. 1)
3. 套系 3 · 《烽烟晚照》· 塞外落日与静谧夜色 (Chopin - Prelude in E minor Op. 28 No. 4)

Technique:
- Equal-power crossfade (3.0s) between tail and head for seamless endless looping without pops or jarring seams.
- Mastered to 44.1kHz stereo, normalized to peak 0.68 for gentle non-intrusive background listening.
- Exported as both web-optimized MP3 and lossless WAV into frontend/audio/.
"""

import os
import numpy as np
import soundfile as sf

OUT_DIR = os.path.join(os.path.dirname(__file__), '../frontend/audio')
os.makedirs(OUT_DIR, exist_ok=True)

SR = 44100
TARGET_PEAK = 0.68

def make_seamless_loop(audio, sr=SR, fade_sec=3.0):
    """
    Apply equal-power circular crossfade between the tail and head
    so that playing with audio.loop = true loops infinitely and seamlessly.
    """
    fade_len = int(fade_sec * sr)
    if len(audio) <= fade_len * 2:
        return audio
    
    n = len(audio)
    loop_len = n - fade_len
    
    # Extract head and tail overlapping regions
    head = audio[:fade_len].copy()
    tail = audio[loop_len:n].copy()
    
    # Equal-power quarter-sine fade curves
    t = np.linspace(0, np.pi / 2, fade_len)
    fade_in = np.sin(t)[:, np.newaxis] if audio.ndim > 1 else np.sin(t)
    fade_out = np.cos(t)[:, np.newaxis] if audio.ndim > 1 else np.cos(t)
    
    # Crossfaded loop start
    blended_head = head * fade_in + tail * fade_out
    
    # Construct the seamlessly looping track
    out = np.zeros((loop_len, audio.shape[1]), dtype=np.float32) if audio.ndim > 1 else np.zeros(loop_len, dtype=np.float32)
    out[:fade_len] = blended_head
    out[fade_len:] = audio[fade_len:loop_len]
    
    return out

def process_and_export(src_path, base_name, fade_sec=3.0, custom_crop=None):
    print(f"Processing {base_name} from {src_path}...")
    data, sr = sf.read(src_path)
    if sr != SR:
        import scipy.signal as signal
        num_samples = int(round(len(data) * SR / sr))
        data = signal.resample(data, num_samples)
        sr = SR
        
    if custom_crop:
        start_f = int(custom_crop[0] * sr)
        end_f = int(custom_crop[1] * sr) if custom_crop[1] else len(data)
        data = data[start_f:end_f]
        
    # Ensure float32
    data = data.astype(np.float32)
    
    # Apply seamless loop
    looped = make_seamless_loop(data, sr=sr, fade_sec=fade_sec)
    
    # Normalize peak to TARGET_PEAK
    peak = np.max(np.abs(looped))
    if peak > 0:
        looped = looped * (TARGET_PEAK / peak)
        
    dur = len(looped) / sr
    
    # Export MP3
    mp3_path = os.path.join(OUT_DIR, f"{base_name}.mp3")
    sf.write(mp3_path, looped, sr, format='MP3')
    mp3_size = os.path.getsize(mp3_path) / (1024 * 1024)
    
    # Export WAV
    wav_path = os.path.join(OUT_DIR, f"{base_name}.wav")
    sf.write(wav_path, looped, sr, format='WAV', subtype='PCM_16')
    wav_size = os.path.getsize(wav_path) / (1024 * 1024)
    
    print(f" -> Exported {base_name}: {dur:.1f}s | MP3: {mp3_size:.2f}MB | WAV: {wav_size:.2f}MB")

if __name__ == '__main__':
    # 1. 套系 1: 《山河行思》
    process_and_export('/tmp/bgm_sources/air_force_strings.mp3', 'bgm_strings', fade_sec=3.5)
    
    # 2. 套系 2: 《平原微光》
    process_and_export('/tmp/bgm_sources/gymnopedie_no1.ogg', 'bgm_piano', fade_sec=3.5)
    
    # 3. 套系 3: 《烽烟晚照》
    # Trim the silent applause/gap at the tail (starts around 1.0s, ends around 125s)
    process_and_export('/tmp/bgm_sources/chopin_prelude_op28_no4.wav', 'bgm_evening', fade_sec=3.0, custom_crop=(1.2, 125.0))
    
    print("\nAll 3 BGM suites built successfully!")
