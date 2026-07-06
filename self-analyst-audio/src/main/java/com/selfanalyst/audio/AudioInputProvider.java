package com.selfanalyst.audio;

@FunctionalInterface
interface AudioInputProvider {
    AudioInput open(int chunkSeconds, float vadThreshold, String source);
}
