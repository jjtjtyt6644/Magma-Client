package dev.magma.client.util;

public final class Anim {
    private Anim() {}

    /** Independent frame clock; each renderer keeps its own so they don't steal each other's delta. */
    public static final class Clock {
        private long last = System.nanoTime();

        public float tick() {
            long now = System.nanoTime();
            float dt = (now - last) / 1_000_000_000f;
            last = now;
            return Math.min(dt, 0.1f);
        }
    }

    /** Exponential smoothing toward target. speed ~ how many "lengths" per second. */
    public static float approach(float current, float target, float speed) {
        float t = 1f - (float) Math.exp(-speed);
        float v = current + (target - current) * t;
        if (Math.abs(v - target) < 0.002f) v = target;
        return v;
    }

    public static float easeOutCubic(float t) {
        t = Math.max(0, Math.min(1, t));
        float f = 1 - t;
        return 1 - f * f * f;
    }

    public static float easeInOut(float t) {
        t = Math.max(0, Math.min(1, t));
        return t < 0.5f ? 4 * t * t * t : 1 - (float) Math.pow(-2 * t + 2, 3) / 2;
    }
}
