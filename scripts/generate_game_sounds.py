#!/usr/bin/env python3
"""
Generate procedural WAV sound effects for Strategies-of-the-Flaming-Plains:
1. dispatch: Troop deployment / march bugle & drum
2. firefight: Interception battle autocannon & airburst explosion
3. victory: Tactical command victory fanfare
4. upgrade: Building construction complete hammer & chime
"""

import math
import struct
import wave
import os

SAMPLE_RATE = 44100

def create_wav(filename, samples, sample_rate=SAMPLE_RATE):
    # Normalize peak to 0.85
    peak = max(abs(s) for s in samples) if samples else 0
    if peak > 0:
        gain = 0.85 / peak
        samples = [s * gain for s in samples]
    
    os.makedirs(os.path.dirname(filename), exist_ok=True)
    with wave.open(filename, 'w') as wf:
        wf.setnchannels(1)        # mono
        wf.setsampwidth(2)        # 16-bit
        wf.setframerate(sample_rate)
        int_samples = [max(-32767, min(32767, int(s * 32767))) for s in samples]
        data = struct.pack(f'<{len(int_samples)}h', *int_samples)
        wf.writeframes(data)
    print(f"Generated {filename} ({len(samples)/sample_rate:.2f}s)")

def pseudo_noise(seed):
    seed = (seed * 1664525 + 1013904223) & 0xFFFFFFFF
    return ((seed / 0x80000000) - 1.0), seed

def mix_layers(duration, layers, sample_rate=SAMPLE_RATE):
    total_len = int(duration * sample_rate)
    buffer = [0.0] * total_len

    for layer in layers:
        start_idx = int(layer.get('at', 0) * sample_rate)
        count = int(layer.get('duration', 0) * sample_rate)
        end_idx = min(total_len, start_idx + count)
        
        freq_from = layer.get('from', 440)
        freq_to = layer.get('to', freq_from)
        gain = layer.get('gain', 0.2)
        wave_type = layer.get('wave', 'sine')
        envelope_type = layer.get('envelope', 'decay')
        
        phase = 0.0
        filtered = 0.0
        seed = 173 + int(freq_from * 13)

        for i in range(count):
            idx = start_idx + i
            if idx >= total_len:
                break
            progress = i / count if count > 0 else 0
            curr_freq = freq_from + (freq_to - freq_from) * progress
            phase += 2 * math.pi * curr_freq / sample_rate

            # Envelope calculation
            if envelope_type == 'decay':
                # Sharp attack, cubic decay
                attack = min(1.0, i / (sample_rate * 0.003))
                env = attack * ((1.0 - progress) ** 3)
            elif envelope_type == 'punch':
                # Very fast punch for drum/gun
                attack = min(1.0, i / (sample_rate * 0.0015))
                env = attack * ((1.0 - progress) ** 4)
            elif envelope_type == 'chime':
                # Percussive attack, long bell-like gentle decay
                attack = min(1.0, i / (sample_rate * 0.002))
                env = attack * math.exp(-3.5 * progress)
            elif envelope_type == 'brass':
                # Brass envelope: fast swell (15ms), steady sustain (70%), release tail
                if progress < 0.15:
                    env = progress / 0.15
                elif progress < 0.75:
                    env = 1.0 - (progress - 0.15) * 0.2
                else:
                    env = 0.88 * ((1.0 - progress) / 0.25) ** 1.8
            elif envelope_type == 'sustain':
                # Smooth in and out
                attack = min(1.0, i / (sample_rate * 0.02))
                release = min(1.0, (count - i) / (sample_rate * 0.04))
                env = attack * release
            elif envelope_type == 'explosion':
                # Rapid spike, slow turbulent rumble decay
                attack = min(1.0, i / (sample_rate * 0.005))
                env = attack * (0.6 * math.exp(-4.0 * progress) + 0.4 * ((1.0 - progress) ** 2))
            else:
                env = 1.0 - progress

            # Waveform synthesis
            if wave_type == 'noise':
                raw_noise, seed = pseudo_noise(seed)
                # Simple one-pole low-pass filter
                filter_coeff = min(1.0, 2 * math.pi * curr_freq / sample_rate)
                filtered += filter_coeff * (raw_noise - filtered)
                val = filtered
            elif wave_type == 'metal':
                # Rich inharmonic metal spectrum for swords, anvils, shell casings
                val = (math.sin(phase) * 0.55 + 
                       math.sin(phase * 2.76) * 0.25 + 
                       math.sin(phase * 5.40) * 0.12 + 
                       math.sin(phase * 8.91) * 0.08)
            elif wave_type == 'brass':
                # Brass horn spectrum: rich odd & even harmonics with warmth
                val = (math.sin(phase) * 0.55 + 
                       math.sin(phase * 2) * 0.25 + 
                       math.sin(phase * 3) * 0.12 + 
                       math.sin(phase * 4) * 0.08)
            elif wave_type == 'square':
                val = 1.0 if math.sin(phase) >= 0 else -1.0
            elif wave_type == 'sawtooth':
                # Band-limited-ish saw approximation (4 harmonics)
                val = (math.sin(phase) - 
                       0.5 * math.sin(phase * 2) + 
                       0.33 * math.sin(phase * 3) - 
                       0.25 * math.sin(phase * 4)) * 0.7
            elif wave_type == 'triangle':
                val = (2 / math.pi) * math.asin(math.sin(phase))
            else: # sine
                val = math.sin(phase)

            buffer[idx] += val * env * gain

    return buffer

def generate_dispatch_sound():
    """
    Troop Dispatch / March Sound:
    Military marching drum cadences + resolute brass bugle charge fanfare.
    Duration ~1.1s.
    """
    layers = [
        # Marching snare/drum cadence 1 (at 0.0s)
        { 'wave': 'sine', 'at': 0.00, 'duration': 0.12, 'from': 120, 'to': 45, 'gain': 0.35, 'envelope': 'punch' },
        { 'wave': 'noise', 'at': 0.00, 'duration': 0.08, 'from': 3200, 'to': 800, 'gain': 0.25, 'envelope': 'decay' },
        
        # Marching snare/drum cadence 2 (at 0.14s)
        { 'wave': 'sine', 'at': 0.14, 'duration': 0.10, 'from': 110, 'to': 45, 'gain': 0.30, 'envelope': 'punch' },
        { 'wave': 'noise', 'at': 0.14, 'duration': 0.07, 'from': 3400, 'to': 900, 'gain': 0.22, 'envelope': 'decay' },

        # Military Bugle Fanfare Motif: G3 (196Hz) -> C4 (261Hz) -> E4 (329Hz) -> G4 (392Hz)
        # Note 1: G3
        { 'wave': 'brass', 'at': 0.22, 'duration': 0.14, 'from': 196, 'to': 196, 'gain': 0.32, 'envelope': 'brass' },
        # Note 2: C4
        { 'wave': 'brass', 'at': 0.36, 'duration': 0.14, 'from': 261.6, 'to': 261.6, 'gain': 0.35, 'envelope': 'brass' },
        # Note 3: E4
        { 'wave': 'brass', 'at': 0.50, 'duration': 0.14, 'from': 329.6, 'to': 329.6, 'gain': 0.38, 'envelope': 'brass' },
        # Note 4: G4 (Sustained resonant charge note with octave backing)
        { 'wave': 'brass', 'at': 0.64, 'duration': 0.42, 'from': 392.0, 'to': 392.0, 'gain': 0.42, 'envelope': 'brass' },
        { 'wave': 'sine',  'at': 0.64, 'duration': 0.40, 'from': 196.0, 'to': 196.0, 'gain': 0.20, 'envelope': 'brass' },

        # Resolute marching bass thud on final note (at 0.64s)
        { 'wave': 'sine', 'at': 0.64, 'duration': 0.22, 'from': 140, 'to': 40, 'gain': 0.40, 'envelope': 'punch' },
        { 'wave': 'noise', 'at': 0.64, 'duration': 0.15, 'from': 4000, 'to': 600, 'gain': 0.20, 'envelope': 'decay' },
    ]
    return mix_layers(1.10, layers)

def generate_firefight_sound():
    """
    Interception Clash / Firefight Sound:
    Rapid 3-round autocannon burst (Da-Da-Da!) followed by flak explosion shockwave.
    Duration ~1.0s.
    """
    layers = []
    # 3-round autocannon burst at 0.0s, 0.09s, 0.18s
    shots = [0.0, 0.09, 0.18]
    for t in shots:
        # Sharp supersonic bullet crack / mechanical recoil
        layers.append({ 'wave': 'noise', 'at': t, 'duration': 0.045, 'from': 6500, 'to': 1800, 'gain': 0.45, 'envelope': 'punch' })
        layers.append({ 'wave': 'metal', 'at': t, 'duration': 0.06,  'from': 1850, 'to': 450,  'gain': 0.35, 'envelope': 'decay' })
        layers.append({ 'wave': 'sine',  'at': t, 'duration': 0.08,  'from': 260,  'to': 80,   'gain': 0.40, 'envelope': 'punch' })

    # Aerial flyby / shell ricochet scream
    layers.append({ 'wave': 'sine', 'at': 0.15, 'duration': 0.25, 'from': 2200, 'to': 750, 'gain': 0.18, 'envelope': 'sustain' })

    # Heavy flak airburst explosion at 0.26s
    # Blast wave transient
    layers.append({ 'wave': 'noise', 'at': 0.26, 'duration': 0.65, 'from': 2200, 'to': 150, 'gain': 0.45, 'envelope': 'explosion' })
    # Deep rumble sub-bass
    layers.append({ 'wave': 'sine',  'at': 0.26, 'duration': 0.55, 'from': 130,  'to': 32,  'gain': 0.50, 'envelope': 'explosion' })
    # Metal debris / shockwave ring
    layers.append({ 'wave': 'metal', 'at': 0.28, 'duration': 0.20, 'from': 980,  'to': 350, 'gain': 0.15, 'envelope': 'decay' })

    return mix_layers(1.0, layers)

def generate_victory_sound():
    """
    Tactical Battle Victory Sound:
    Inspiring triumphant brass chords + shining celebratory glockenspiel sparkle.
    Cadence: C5/E5 -> D5/F#5 -> G5/B5 -> High C6 Triumph Chord.
    Duration ~1.4s.
    """
    layers = [
        # Chord 1 (0.0s - 0.18s): C5 (523Hz) + E5 (659Hz)
        { 'wave': 'brass', 'at': 0.00, 'duration': 0.18, 'from': 523.25, 'to': 523.25, 'gain': 0.30, 'envelope': 'brass' },
        { 'wave': 'brass', 'at': 0.00, 'duration': 0.18, 'from': 659.25, 'to': 659.25, 'gain': 0.28, 'envelope': 'brass' },

        # Chord 2 (0.16s - 0.34s): E5 (659Hz) + G5 (784Hz)
        { 'wave': 'brass', 'at': 0.16, 'duration': 0.18, 'from': 659.25, 'to': 659.25, 'gain': 0.32, 'envelope': 'brass' },
        { 'wave': 'brass', 'at': 0.16, 'duration': 0.18, 'from': 783.99, 'to': 783.99, 'gain': 0.30, 'envelope': 'brass' },

        # Chord 3 (0.32s - 0.52s): G5 (784Hz) + B5 (988Hz)
        { 'wave': 'brass', 'at': 0.32, 'duration': 0.20, 'from': 783.99, 'to': 783.99, 'gain': 0.35, 'envelope': 'brass' },
        { 'wave': 'brass', 'at': 0.32, 'duration': 0.20, 'from': 987.77, 'to': 987.77, 'gain': 0.32, 'envelope': 'brass' },

        # Final Grand Victory Chord (0.50s - 1.4s): C4 root (261Hz) + G4 (392Hz) + C5 (523Hz) + E5 (659Hz) + C6 (1046Hz)
        { 'wave': 'brass', 'at': 0.50, 'duration': 0.85, 'from': 261.63, 'to': 261.63, 'gain': 0.30, 'envelope': 'brass' },
        { 'wave': 'brass', 'at': 0.50, 'duration': 0.85, 'from': 523.25, 'to': 523.25, 'gain': 0.38, 'envelope': 'brass' },
        { 'wave': 'brass', 'at': 0.50, 'duration': 0.85, 'from': 659.25, 'to': 659.25, 'gain': 0.32, 'envelope': 'brass' },
        { 'wave': 'brass', 'at': 0.50, 'duration': 0.85, 'from': 1046.5, 'to': 1046.5, 'gain': 0.35, 'envelope': 'brass' },
        
        # Shining victory glockenspiel / chime sparkle on final beat
        { 'wave': 'sine',  'at': 0.50, 'duration': 0.60, 'from': 2093.0, 'to': 2093.0, 'gain': 0.18, 'envelope': 'chime' },
        { 'wave': 'sine',  'at': 0.56, 'duration': 0.55, 'from': 2637.0, 'to': 2637.0, 'gain': 0.15, 'envelope': 'chime' },
        { 'wave': 'metal', 'at': 0.50, 'duration': 0.40, 'from': 1568.0, 'to': 1568.0, 'gain': 0.14, 'envelope': 'decay' },

        # Timpani / war drum boom on final chord impact
        { 'wave': 'sine',  'at': 0.50, 'duration': 0.45, 'from': 130, 'to': 55, 'gain': 0.35, 'envelope': 'punch' },
    ]
    return mix_layers(1.40, layers)

def generate_upgrade_sound():
    """
    Building Upgrade Complete Sound:
    Crisp architectural steel hammer strikes + uplifting ascending major chord chime.
    Strikes at 0.0s and 0.12s, followed by sparkling triad E5->G#5->B5->E6.
    Duration ~1.1s.
    """
    layers = [
        # Hammer strike 1 (at 0.0s)
        { 'wave': 'noise', 'at': 0.00, 'duration': 0.025, 'from': 6800, 'to': 2400, 'gain': 0.35, 'envelope': 'punch' },
        { 'wave': 'metal', 'at': 0.00, 'duration': 0.16,  'from': 1480, 'to': 1400, 'gain': 0.38, 'envelope': 'decay' },
        { 'wave': 'sine',  'at': 0.00, 'duration': 0.10,  'from': 320,  'to': 180,  'gain': 0.20, 'envelope': 'punch' },

        # Hammer strike 2 (at 0.12s) - slightly higher pitch
        { 'wave': 'noise', 'at': 0.12, 'duration': 0.025, 'from': 7200, 'to': 2600, 'gain': 0.38, 'envelope': 'punch' },
        { 'wave': 'metal', 'at': 0.12, 'duration': 0.18,  'from': 1760, 'to': 1680, 'gain': 0.42, 'envelope': 'decay' },
        { 'wave': 'sine',  'at': 0.12, 'duration': 0.10,  'from': 440,  'to': 220,  'gain': 0.22, 'envelope': 'punch' },

        # Ascending architectural chime progression:
        # Note 1: E5 (659Hz) at 0.26s
        { 'wave': 'sine',  'at': 0.26, 'duration': 0.50, 'from': 659.25, 'to': 659.25, 'gain': 0.30, 'envelope': 'chime' },
        { 'wave': 'metal', 'at': 0.26, 'duration': 0.30, 'from': 1318.5, 'to': 1318.5, 'gain': 0.12, 'envelope': 'decay' },

        # Note 2: G#5 (830.6Hz) at 0.38s
        { 'wave': 'sine',  'at': 0.38, 'duration': 0.55, 'from': 830.61, 'to': 830.61, 'gain': 0.32, 'envelope': 'chime' },
        { 'wave': 'metal', 'at': 0.38, 'duration': 0.32, 'from': 1661.2, 'to': 1661.2, 'gain': 0.14, 'envelope': 'decay' },

        # Note 3: B5 (987.8Hz) at 0.50s
        { 'wave': 'sine',  'at': 0.50, 'duration': 0.55, 'from': 987.77, 'to': 987.77, 'gain': 0.34, 'envelope': 'chime' },
        { 'wave': 'metal', 'at': 0.50, 'duration': 0.35, 'from': 1975.5, 'to': 1975.5, 'gain': 0.15, 'envelope': 'decay' },

        # Note 4: High E6 (1318.5Hz) at 0.62s - Bright sustained crystal chime
        { 'wave': 'sine',  'at': 0.62, 'duration': 0.50, 'from': 1318.51, 'to': 1318.51, 'gain': 0.38, 'envelope': 'chime' },
        { 'wave': 'sine',  'at': 0.62, 'duration': 0.45, 'from': 2637.02, 'to': 2637.02, 'gain': 0.16, 'envelope': 'chime' },
        { 'wave': 'metal', 'at': 0.62, 'duration': 0.40, 'from': 3955.5,  'to': 3955.5,  'gain': 0.10, 'envelope': 'decay' },
    ]
    return mix_layers(1.15, layers)

def generate_dispatch_sound_b():
    """
    Variant B: Armored Column Mobilization
    Diesel engine acceleration roar + metallic tank tread rattle + departure horn whistle.
    Duration ~1.1s.
    """
    layers = [
        # Heavy diesel engine low-frequency roar (45Hz -> 85Hz)
        { 'wave': 'sawtooth', 'at': 0.00, 'duration': 1.05, 'from': 48, 'to': 82, 'gain': 0.35, 'envelope': 'sustain' },
        { 'wave': 'sine',     'at': 0.00, 'duration': 1.05, 'from': 96, 'to': 164, 'gain': 0.25, 'envelope': 'sustain' },
        { 'wave': 'noise',    'at': 0.00, 'duration': 1.05, 'from': 500, 'to': 250, 'gain': 0.18, 'envelope': 'sustain' },

        # Metallic tread clanking pulses (spaced ~110ms)
        { 'wave': 'metal', 'at': 0.08, 'duration': 0.05, 'from': 1850, 'to': 920, 'gain': 0.28, 'envelope': 'punch' },
        { 'wave': 'metal', 'at': 0.20, 'duration': 0.05, 'from': 1720, 'to': 880, 'gain': 0.30, 'envelope': 'punch' },
        { 'wave': 'metal', 'at': 0.33, 'duration': 0.05, 'from': 1950, 'to': 950, 'gain': 0.32, 'envelope': 'punch' },
        { 'wave': 'metal', 'at': 0.46, 'duration': 0.05, 'from': 1800, 'to': 900, 'gain': 0.34, 'envelope': 'punch' },
        { 'wave': 'metal', 'at': 0.60, 'duration': 0.05, 'from': 1900, 'to': 930, 'gain': 0.36, 'envelope': 'punch' },

        # Tactical mobilization whistle / horn (at 0.25s)
        { 'wave': 'sine',  'at': 0.25, 'duration': 0.35, 'from': 1174.66, 'to': 1174.66, 'gain': 0.28, 'envelope': 'brass' },
        { 'wave': 'sine',  'at': 0.25, 'duration': 0.35, 'from': 1760.0,  'to': 1760.0,  'gain': 0.18, 'envelope': 'brass' },
    ]
    return mix_layers(1.10, layers)

def generate_firefight_sound_b():
    """
    Variant B: Anti-Aircraft Flak Barrage
    5-round rapid salvo from anti-air battery with supersonic shell whines.
    Duration ~1.1s.
    """
    layers = []
    salvos = [0.00, 0.08, 0.16, 0.24, 0.33]
    for idx, t in enumerate(salvos):
        # Muzzle blast punch
        layers.append({ 'wave': 'noise', 'at': t, 'duration': 0.06, 'from': 7500, 'to': 1200, 'gain': 0.40, 'envelope': 'punch' })
        layers.append({ 'wave': 'metal', 'at': t, 'duration': 0.08, 'from': 2200 - idx*80, 'to': 600, 'gain': 0.30, 'envelope': 'decay' })
        layers.append({ 'wave': 'sine',  'at': t, 'duration': 0.09, 'from': 310, 'to': 85, 'gain': 0.35, 'envelope': 'punch' })

    # Shell whine passing overhead
    layers.append({ 'wave': 'sine',  'at': 0.18, 'duration': 0.30, 'from': 2800, 'to': 900, 'gain': 0.18, 'envelope': 'sustain' })
    # Heavy airburst detonation at 0.40s
    layers.append({ 'wave': 'noise', 'at': 0.40, 'duration': 0.65, 'from': 2600, 'to': 120, 'gain': 0.45, 'envelope': 'explosion' })
    layers.append({ 'wave': 'sine',  'at': 0.40, 'duration': 0.60, 'from': 160,  'to': 28,  'gain': 0.50, 'envelope': 'explosion' })

    return mix_layers(1.10, layers)

def generate_victory_sound_b():
    """
    Variant B: Decisive Tactical Victory Sting
    Resolute staccato trumpet fanfare + deep war drum finale.
    Duration ~1.1s.
    """
    layers = [
        # Staccato Fanfare: C5 (0.0s) -> G4 (0.12s) -> C5 (0.24s) -> E5 (0.36s) -> High G5 (0.48s - 1.1s)
        { 'wave': 'brass', 'at': 0.00, 'duration': 0.11, 'from': 523.25, 'to': 523.25, 'gain': 0.35, 'envelope': 'punch' },
        { 'wave': 'brass', 'at': 0.12, 'duration': 0.11, 'from': 392.00, 'to': 392.00, 'gain': 0.32, 'envelope': 'punch' },
        { 'wave': 'brass', 'at': 0.24, 'duration': 0.11, 'from': 523.25, 'to': 523.25, 'gain': 0.36, 'envelope': 'punch' },
        { 'wave': 'brass', 'at': 0.36, 'duration': 0.11, 'from': 659.25, 'to': 659.25, 'gain': 0.38, 'envelope': 'punch' },
        
        # Sustained High G5 Grand Chord (0.48s - 1.1s)
        { 'wave': 'brass', 'at': 0.48, 'duration': 0.60, 'from': 783.99, 'to': 783.99, 'gain': 0.45, 'envelope': 'brass' },
        { 'wave': 'brass', 'at': 0.48, 'duration': 0.60, 'from': 523.25, 'to': 523.25, 'gain': 0.38, 'envelope': 'brass' },
        { 'wave': 'brass', 'at': 0.48, 'duration': 0.60, 'from': 261.63, 'to': 261.63, 'gain': 0.30, 'envelope': 'brass' },

        # Heavy battle drum punch on final resolution
        { 'wave': 'sine',  'at': 0.48, 'duration': 0.40, 'from': 140, 'to': 45, 'gain': 0.45, 'envelope': 'punch' },
        { 'wave': 'noise', 'at': 0.48, 'duration': 0.15, 'from': 3500, 'to': 500, 'gain': 0.22, 'envelope': 'decay' },
    ]
    return mix_layers(1.10, layers)

def generate_upgrade_sound_b():
    """
    Variant B: Heavy Mechanical Lock & Citadel Chime
    Mechanical gears lock into place + heavy foundation thud + resonant civic bell.
    Duration ~1.2s.
    """
    layers = [
        # Gear ratchet & lock (0.0s - 0.15s)
        { 'wave': 'metal', 'at': 0.00, 'duration': 0.04, 'from': 2400, 'to': 800, 'gain': 0.30, 'envelope': 'punch' },
        { 'wave': 'metal', 'at': 0.05, 'duration': 0.04, 'from': 2800, 'to': 900, 'gain': 0.32, 'envelope': 'punch' },
        { 'wave': 'metal', 'at': 0.11, 'duration': 0.05, 'from': 3200, 'to': 1000, 'gain': 0.35, 'envelope': 'punch' },
        # Solid latch lock thud (0.16s)
        { 'wave': 'sine',  'at': 0.16, 'duration': 0.15, 'from': 240, 'to': 60, 'gain': 0.45, 'envelope': 'punch' },
        { 'wave': 'metal', 'at': 0.16, 'duration': 0.18, 'from': 1600, 'to': 500, 'gain': 0.40, 'envelope': 'decay' },

        # Majestic civic bell chime (F#5 740Hz + C#6 1108Hz + high overtone 2217Hz)
        { 'wave': 'sine',  'at': 0.28, 'duration': 0.85, 'from': 739.99, 'to': 739.99, 'gain': 0.40, 'envelope': 'chime' },
        { 'wave': 'sine',  'at': 0.28, 'duration': 0.80, 'from': 1108.7, 'to': 1108.7, 'gain': 0.25, 'envelope': 'chime' },
        { 'wave': 'metal', 'at': 0.28, 'duration': 0.55, 'from': 2217.4, 'to': 2217.4, 'gain': 0.16, 'envelope': 'chime' },
    ]
    return mix_layers(1.20, layers)

if __name__ == '__main__':
    out_dir = os.path.join(os.path.dirname(__file__), '../frontend/audio')
    # Option A (Recommended)
    create_wav(os.path.join(out_dir, 'dispatch.wav'), generate_dispatch_sound())
    create_wav(os.path.join(out_dir, 'firefight.wav'), generate_firefight_sound())
    create_wav(os.path.join(out_dir, 'victory.wav'), generate_victory_sound())
    create_wav(os.path.join(out_dir, 'upgrade.wav'), generate_upgrade_sound())
    
    # Option B (Alternative)
    create_wav(os.path.join(out_dir, 'dispatch_b.wav'), generate_dispatch_sound_b())
    create_wav(os.path.join(out_dir, 'firefight_b.wav'), generate_firefight_sound_b())
    create_wav(os.path.join(out_dir, 'victory_b.wav'), generate_victory_sound_b())
    create_wav(os.path.join(out_dir, 'upgrade_b.wav'), generate_upgrade_sound_b())
    print("All sounds (Option A & B) generated successfully!")
