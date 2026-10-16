package com.perfpilot.mobile;

// Runs inside Shizuku's shell process (same access as `adb shell`).
interface IShellService {
    // Shizuku calls this transaction code to stop the service.
    void destroy() = 16777114;

    String exec(String command, long timeoutMs) = 1;
}
