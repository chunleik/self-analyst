package com.selfanalyst.events.tray;

import com.selfanalyst.events.watcher.WatcherManager;

import java.awt.Desktop;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SystemTrayManager {
    private static final Logger log = LoggerFactory.getLogger(SystemTrayManager.class);
    private final WatcherManager watchers;
    private final Runnable onExit;
    private TrayIcon trayIcon;

    public SystemTrayManager(WatcherManager watchers, Runnable onExit) {
        this.watchers = watchers;
        this.onExit = onExit;
    }

    public void show() {
        if (!SystemTray.isSupported()) return;

        try {
            SystemTray tray = SystemTray.getSystemTray();
            Image image = createDefaultIcon();
            PopupMenu menu = new PopupMenu();

            MenuItem statusItem = new MenuItem("SelfAnalyst AW - Running");
            statusItem.setEnabled(false);
            menu.add(statusItem);
            menu.addSeparator();

            MenuItem startStopWatchers = new MenuItem("Start Watchers");
            startStopWatchers.addActionListener(e -> {
                watchers.startAll();
                startStopWatchers.setLabel("Watchers Running");
                startStopWatchers.setEnabled(false);
            });
            menu.add(startStopWatchers);

            MenuItem openWebUi = new MenuItem("Open Web UI");
            openWebUi.addActionListener(e -> {
                try {
                    Desktop.getDesktop().browse(java.net.URI.create("http://localhost:5600"));
                } catch (Exception ex) {
                    // silently ignore
                }
            });
            menu.add(openWebUi);

            menu.addSeparator();
            MenuItem exitItem = new MenuItem("Exit");
            exitItem.addActionListener(e -> {
                tray.remove(trayIcon);
                watchers.stopAll();
                onExit.run();
                System.exit(0);
            });
            menu.add(exitItem);

            trayIcon = new TrayIcon(image, "SelfAnalyst AW", menu);
            trayIcon.setImageAutoSize(true);
            tray.add(trayIcon);
        } catch (Exception e) {
            log.warn("Tray icon not available: {}", e.getMessage());
        }
    }

    private Image createDefaultIcon() {
        return new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
    }

    public void hide() {
        if (trayIcon != null) {
            SystemTray.getSystemTray().remove(trayIcon);
        }
    }
}
