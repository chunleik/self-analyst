package com.selfanalyst.content.uia;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;

/**
 * Manual smoke check for the accessibility sidecar.
 *
 * <p>Validates SPEC-AXS-T04 (latency: cold vs warm) and prints the extracted
 * text for a window so the walk output can be eyeballed.
 *
 * <p>Run with the built binary, e.g.:
 * <pre>
 *   java -Dcontent.axsidecar.path=self-analyst-axsidecar/target/release/axsidecar.exe \
 *        -cp &lt;content-classpath&gt; com.selfanalyst.content.uia.AxSidecarSmoke
 * </pre>
 * Optional arg: a window handle (hex or decimal). Defaults to the foreground
 * window, falling back to the desktop window.
 */
public final class AxSidecarSmoke {

    public static void main(String[] args) {
        long handle = (args.length > 0) ? Long.decode(args[0]) : foregroundHandle();
        System.out.println("[smoke] handle = 0x" + Long.toHexString(handle));

        AxSidecarClient client = new AxSidecarClient();
        if (!client.isAvailable()) {
            System.out.println("[smoke] no sidecar binary; set -Dcontent.axsidecar.path=...");
            return;
        }

        // Cold query (process spawn + COM init).
        long t0 = System.nanoTime();
        UiaNode cold = client.query(handle);
        long coldMs = (System.nanoTime() - t0) / 1_000_000;

        // Warm query (process already resident).
        long t1 = System.nanoTime();
        UiaNode warm = client.query(handle);
        long warmMs = (System.nanoTime() - t1) / 1_000_000;

        System.out.println("[smoke] cold = " + coldMs + " ms, warm = " + warmMs + " ms");
        report("sidecar", warm);
        System.out.println("[smoke] extracted text:\n" + extract(warm));
    }

    private static long foregroundHandle() {
        HWND h = User32.INSTANCE.GetForegroundWindow();
        if (h == null) h = User32.INSTANCE.GetDesktopWindow();
        return Pointer.nativeValue(h.getPointer());
    }

    private static void report(String tag, UiaNode root) {
        if (root == null) {
            System.out.println("[" + tag + "] root = null");
            return;
        }
        System.out.println("[" + tag + "] nodes = " + count(root)
                + ", text chars = " + extract(root).length());
    }

    private static int count(UiaNode n) {
        int c = 1;
        for (UiaNode k : n.children()) c += count(k);
        return c;
    }

    private static String extract(UiaNode root) {
        if (root == null) return "";
        StringBuilder sb = new StringBuilder();
        UiaTreeWalker.extractText(root, sb);
        return sb.toString().trim();
    }

    private AxSidecarSmoke() {
    }
}
